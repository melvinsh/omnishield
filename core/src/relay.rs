//! Connection state for the tunnel loop, and the sweep that retires it.
//!
//! Everything here is the android-free half of `runtime.rs`: the per-connection structs, the
//! rules for when a connection or session is finished, and the sweep that enforces them. It
//! lives in its own module so those rules compile — and are tested — on the host, while the
//! loop that drives them stays behind `cfg(target_os = "android")` with the JNI it needs.

use std::collections::HashMap;
use std::net::{IpAddr, SocketAddr};
use std::os::unix::io::RawFd;
use std::sync::Arc;
use std::time::{Duration, Instant as StdInstant};

use smoltcp::iface::{SocketHandle, SocketSet};
use smoltcp::socket::tcp;

use crate::mitm::MitmSession;

pub(crate) const UDP_SESSION_TTL: Duration = Duration::from_secs(30);
pub(crate) const HANDSHAKE_TTL: Duration = Duration::from_secs(10);

#[derive(Hash, PartialEq, Eq, Clone, Copy, Debug)]
pub(crate) struct FourTuple {
    pub(crate) src: IpAddr,
    pub(crate) src_port: u16,
    pub(crate) dst: IpAddr,
    pub(crate) dst_port: u16,
}

pub(crate) struct TcpConn {
    pub(crate) fd: RawFd,
    pub(crate) remote: SocketAddr,
    pub(crate) connecting: bool,
    /// app → upstream
    pub(crate) to_remote: Vec<u8>,
    /// upstream → app
    pub(crate) from_remote: Vec<u8>,
    pub(crate) uid: i32,
    pub(crate) app: Arc<str>,
    pub(crate) remote_eof: bool,
    /// Present only for connections opted in to TLS interception.
    pub(crate) mitm: Option<Box<MitmSession>>,
    /// Set once `finish_response` has released the (possibly rewritten) body to the app.
    pub(crate) mitm_finished: bool,
}

pub(crate) struct UdpSession {
    pub(crate) fd: RawFd,
    /// Where to send the reply — the app's own endpoint.
    pub(crate) app_addr: IpAddr,
    pub(crate) app_port: u16,
    /// The address the app addressed, which must be the source of our reply.
    pub(crate) orig_dst: IpAddr,
    pub(crate) orig_port: u16,
    /// Refreshed on every packet in either direction. Expiry is measured from here: a session
    /// timed from creation tears a *live* flow down every TTL, and each rebuild is a fresh
    /// socket, a JNI `protect()` round trip, and a new source port the far end sees as a NAT
    /// rebind.
    pub(crate) last_seen: StdInstant,
    /// Whether this session carries DNS. Replies on a DNS session are eligible for the answer
    /// cache; everything else is relayed untouched.
    pub(crate) is_dns: bool,
    /// Attribution for the socket that opened this session, resolved once. `-1` means it was
    /// never resolved, in which case callers fall back to a fresh lookup.
    pub(crate) uid: i32,
    pub(crate) app: Arc<str>,
}

/// The upstream socket is gone for good: close it, withdraw it from the poll set (negative
/// fds are skipped by `poll()`), and drop anything queued toward it. `from_remote` is kept —
/// bytes already received still belong to the app — and `remote_eof` lets the relay half-close
/// the app side once they are delivered.
pub(crate) fn kill_upstream(conn: &mut TcpConn) {
    if conn.fd >= 0 {
        unsafe { libc::close(conn.fd) };
        conn.fd = -1;
    }
    conn.remote_eof = true;
    conn.to_remote.clear();
}

pub(crate) fn reap(
    sockets: &mut SocketSet<'static>,
    conns: &mut HashMap<SocketHandle, TcpConn>,
    pending: &mut HashMap<FourTuple, (SocketHandle, StdInstant)>,
    udp: &mut HashMap<FourTuple, UdpSession>,
    dying: &mut Vec<SocketHandle>,
) {
    let dead: Vec<SocketHandle> = conns
        .iter()
        .filter(|(h, c)| {
            let s = sockets.get::<tcp::Socket>(**h);
            // Never reap while bytes are still queued toward the app — an intercepted
            // connection writes its whole rewritten response *after* the origin EOF. Unless
            // they can never be delivered: an app socket that was reset or aborted will not
            // accept another byte, and holding the connection for its sake pinned dead
            // connections (and their buffers) for the life of the tunnel.
            let undeliverable = !c.from_remote.is_empty() && !s.may_send();
            (c.from_remote.is_empty() || undeliverable)
                && (!s.is_open() || (c.remote_eof && !s.is_active()))
        })
        .map(|(h, _)| *h)
        .collect();
    for h in dead {
        if let Some(c) = conns.remove(&h) {
            if c.fd >= 0 {
                unsafe { libc::close(c.fd) };
            }
        }
        sockets.remove(h);
    }

    // Sockets aborted before they ever became connections — firewall-refused or failed to
    // dial. They belong to neither `conns` nor `pending`, so without this they stayed in the
    // SocketSet forever: 32 KiB of buffers each, iterated by every `iface.poll`. The RST the
    // abort queued has been emitted by now (`iface.poll` runs between the abort and the
    // sweep), so removal is safe.
    for h in dying.drain(..) {
        sockets.remove(h);
    }

    // Handshakes that never completed would otherwise leak a socket each.
    let stale: Vec<FourTuple> = pending
        .iter()
        .filter(|(_, (h, t))| {
            t.elapsed() > HANDSHAKE_TTL && !sockets.get::<tcp::Socket>(*h).is_active()
        })
        .map(|(k, _)| *k)
        .collect();
    for k in stale {
        if let Some((h, _)) = pending.remove(&k) {
            sockets.remove(h);
        }
    }

    let expired: Vec<FourTuple> = udp
        .iter()
        .filter(|(_, s)| s.last_seen.elapsed() > UDP_SESSION_TTL)
        .map(|(k, _)| *k)
        .collect();
    for k in expired {
        if let Some(s) = udp.remove(&k) {
            unsafe { libc::close(s.fd) };
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::Ipv4Addr;

    fn test_conn(from_remote: Vec<u8>, remote_eof: bool) -> TcpConn {
        TcpConn {
            fd: -1,
            remote: SocketAddr::new(IpAddr::V4(Ipv4Addr::new(192, 0, 2, 1)), 80),
            connecting: false,
            to_remote: Vec::new(),
            from_remote,
            uid: -1,
            app: Arc::from(""),
            remote_eof,
            mitm: None,
            mitm_finished: false,
        }
    }

    fn new_socket() -> tcp::Socket<'static> {
        tcp::Socket::new(
            tcp::SocketBuffer::new(vec![0u8; 64]),
            tcp::SocketBuffer::new(vec![0u8; 64]),
        )
    }

    /// A dead app-side socket with bytes still queued toward it must be reaped: the bytes can
    /// never be delivered, and holding the connection for their sake pinned it (and, before
    /// `kill_upstream`, its fd) for the life of the tunnel.
    #[test]
    fn reap_collects_undeliverable_backlog() {
        let mut sockets = SocketSet::new(Vec::new());
        let handle = sockets.add(new_socket()); // state Closed: cannot send, not open
        let mut conns = HashMap::new();
        conns.insert(handle, test_conn(vec![1, 2, 3], true));
        let mut pending = HashMap::new();
        let mut udp = HashMap::new();
        let mut dying = Vec::new();

        reap(&mut sockets, &mut conns, &mut pending, &mut udp, &mut dying);

        assert!(conns.is_empty(), "undeliverable connection must be reaped");
        assert_eq!(sockets.iter().count(), 0, "its socket must leave the set");
    }

    /// A live connection with an open app-side socket stays.
    #[test]
    fn reap_keeps_deliverable_connection() {
        let mut sockets = SocketSet::new(Vec::new());
        let mut sock = new_socket();
        sock.listen(smoltcp::wire::IpListenEndpoint {
            addr: None,
            port: 80,
        })
        .unwrap(); // open
        let handle = sockets.add(sock);
        let mut conns = HashMap::new();
        conns.insert(handle, test_conn(Vec::new(), false));
        let mut pending = HashMap::new();
        let mut udp = HashMap::new();
        let mut dying = Vec::new();

        reap(&mut sockets, &mut conns, &mut pending, &mut udp, &mut dying);

        assert_eq!(conns.len(), 1, "open connection must not be reaped");
        assert_eq!(sockets.iter().count(), 1);
    }

    /// Handles aborted before ever becoming connections (firewall-refused, failed dial) are
    /// handed to the sweep via `dying`; without that they leaked in the SocketSet forever.
    #[test]
    fn reap_removes_dying_handles() {
        let mut sockets = SocketSet::new(Vec::new());
        let handle = sockets.add(new_socket());
        let mut conns: HashMap<SocketHandle, TcpConn> = HashMap::new();
        let mut pending = HashMap::new();
        let mut udp = HashMap::new();
        let mut dying = vec![handle];

        reap(&mut sockets, &mut conns, &mut pending, &mut udp, &mut dying);

        assert!(dying.is_empty(), "dying list must be drained");
        assert_eq!(
            sockets.iter().count(),
            0,
            "orphaned handle must leave the set"
        );
    }

    /// A UDP session's TTL runs from its last packet, not from creation — a session timed
    /// from creation tore down a live flow every 30 s.
    #[test]
    fn udp_ttl_runs_from_last_seen() {
        let mut sockets = SocketSet::new(Vec::new());
        let mut conns = HashMap::new();
        let mut pending = HashMap::new();
        let mut dying = Vec::new();
        let mut udp = HashMap::new();
        let tuple = FourTuple {
            src: IpAddr::V4(Ipv4Addr::new(10, 0, 0, 2)),
            dst: IpAddr::V4(Ipv4Addr::new(192, 0, 2, 1)),
            src_port: 40000,
            dst_port: 33434,
        };
        udp.insert(
            tuple,
            UdpSession {
                fd: -1,
                app_addr: tuple.src,
                app_port: tuple.src_port,
                orig_dst: tuple.dst,
                orig_port: tuple.dst_port,
                last_seen: StdInstant::now(),
                is_dns: false,
                uid: -1,
                app: Arc::from(""),
            },
        );

        reap(&mut sockets, &mut conns, &mut pending, &mut udp, &mut dying);
        assert_eq!(udp.len(), 1, "fresh session must survive the sweep");

        udp.get_mut(&tuple).unwrap().last_seen = StdInstant::now() - UDP_SESSION_TTL * 2;
        reap(&mut sockets, &mut conns, &mut pending, &mut udp, &mut dying);
        assert!(udp.is_empty(), "idle session must expire");
    }

    /// `kill_upstream` withdraws the fd, seals the upstream direction, and keeps what the app
    /// is still owed.
    #[test]
    fn kill_upstream_keeps_app_bound_bytes() {
        let mut conn = test_conn(vec![9, 9], false);
        conn.to_remote = vec![1, 2, 3];

        kill_upstream(&mut conn);

        assert_eq!(conn.fd, -1);
        assert!(conn.remote_eof);
        assert!(
            conn.to_remote.is_empty(),
            "upstream-bound bytes are dropped"
        );
        assert_eq!(conn.from_remote, vec![9, 9], "app-bound bytes are kept");
    }
}
