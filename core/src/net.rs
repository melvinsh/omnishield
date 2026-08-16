//! Packet construction and raw socket helpers.
//!
//! Synthesized DNS replies bypass smoltcp entirely — there is no connection state to keep for
//! a single request/response exchange, so building the IP+UDP frame directly is both simpler
//! and cheaper than driving a UDP socket through the stack. Checksums come from smoltcp's
//! `wire` layer rather than hand-rolled arithmetic.

use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};
use std::os::unix::io::RawFd;
use std::time::Duration;

use smoltcp::phy::ChecksumCapabilities;
use smoltcp::wire::{
    IpProtocol, Ipv4Address, Ipv4Packet, Ipv4Repr, Ipv6Address, Ipv6Packet, Ipv6Repr, UdpPacket,
    UdpRepr,
};

/// Builds a complete IPv4 or IPv6 UDP datagram with valid checksums.
///
/// `src`/`dst` are from the perspective of the packet being written, i.e. to answer a query
/// the caller swaps the original endpoints.
pub fn build_udp_packet(
    src: IpAddr,
    src_port: u16,
    dst: IpAddr,
    dst_port: u16,
    payload: &[u8],
) -> Option<Vec<u8>> {
    let checksum_caps = ChecksumCapabilities::default();
    let udp_repr = UdpRepr {
        src_port,
        dst_port,
    };
    let udp_len = udp_repr.header_len() + payload.len();

    match (src, dst) {
        (IpAddr::V4(s), IpAddr::V4(d)) => {
            let ip_repr = Ipv4Repr {
                src_addr: Ipv4Address::from(s),
                dst_addr: Ipv4Address::from(d),
                next_header: IpProtocol::Udp,
                payload_len: udp_len,
                hop_limit: 64,
            };
            let mut buf = vec![0u8; ip_repr.buffer_len() + udp_len];
            let mut ip_packet = Ipv4Packet::new_unchecked(&mut buf);
            ip_repr.emit(&mut ip_packet, &checksum_caps);

            let mut udp_packet = UdpPacket::new_unchecked(ip_packet.payload_mut());
            udp_repr.emit(
                &mut udp_packet,
                &ip_repr.src_addr.into(),
                &ip_repr.dst_addr.into(),
                payload.len(),
                |b| b.copy_from_slice(payload),
                &checksum_caps,
            );
            Some(buf)
        }
        (IpAddr::V6(s), IpAddr::V6(d)) => {
            let ip_repr = Ipv6Repr {
                src_addr: Ipv6Address::from(s),
                dst_addr: Ipv6Address::from(d),
                next_header: IpProtocol::Udp,
                payload_len: udp_len,
                hop_limit: 64,
            };
            let mut buf = vec![0u8; ip_repr.buffer_len() + udp_len];
            let mut ip_packet = Ipv6Packet::new_unchecked(&mut buf);
            ip_repr.emit(&mut ip_packet);

            let mut udp_packet = UdpPacket::new_unchecked(ip_packet.payload_mut());
            udp_repr.emit(
                &mut udp_packet,
                &ip_repr.src_addr.into(),
                &ip_repr.dst_addr.into(),
                payload.len(),
                |b| b.copy_from_slice(payload),
                &checksum_caps,
            );
            Some(buf)
        }
        // Mixed families are a caller bug, not a packet we should invent.
        _ => None,
    }
}

pub fn set_nonblocking(fd: RawFd) -> io::Result<()> {
    // SOCK_NONBLOCK is Linux-only; fcntl keeps this compiling on the macOS dev host too.
    unsafe {
        let flags = libc::fcntl(fd, libc::F_GETFL, 0);
        if flags < 0 {
            return Err(io::Error::last_os_error());
        }
        if libc::fcntl(fd, libc::F_SETFL, flags | libc::O_NONBLOCK) < 0 {
            return Err(io::Error::last_os_error());
        }
    }
    Ok(())
}

/// Creates a non-blocking socket of the family matching `addr`.
pub fn new_socket(addr: &IpAddr, sock_type: i32) -> io::Result<RawFd> {
    let domain = match addr {
        IpAddr::V4(_) => libc::AF_INET,
        IpAddr::V6(_) => libc::AF_INET6,
    };
    let fd = unsafe { libc::socket(domain, sock_type, 0) };
    if fd < 0 {
        return Err(io::Error::last_os_error());
    }
    if let Err(e) = set_nonblocking(fd) {
        unsafe { libc::close(fd) };
        return Err(e);
    }
    Ok(fd)
}

/// Fills a `sockaddr_storage` for `addr:port`, returning it plus the length to pass to
/// `connect`/`sendto`.
pub fn sockaddr(addr: &IpAddr, port: u16) -> (libc::sockaddr_storage, libc::socklen_t) {
    let mut storage: libc::sockaddr_storage = unsafe { std::mem::zeroed() };
    match addr {
        IpAddr::V4(v4) => {
            let sin = &mut storage as *mut _ as *mut libc::sockaddr_in;
            unsafe {
                (*sin).sin_family = libc::AF_INET as libc::sa_family_t;
                (*sin).sin_port = port.to_be();
                (*sin).sin_addr.s_addr = u32::from_ne_bytes(v4.octets());
            }
            (storage, std::mem::size_of::<libc::sockaddr_in>() as libc::socklen_t)
        }
        IpAddr::V6(v6) => {
            let sin6 = &mut storage as *mut _ as *mut libc::sockaddr_in6;
            unsafe {
                (*sin6).sin6_family = libc::AF_INET6 as libc::sa_family_t;
                (*sin6).sin6_port = port.to_be();
                (*sin6).sin6_addr.s6_addr = v6.octets();
            }
            (storage, std::mem::size_of::<libc::sockaddr_in6>() as libc::socklen_t)
        }
    }
}

/// Creates a *blocking* socket. Used by the DoH worker, which runs on its own thread and
/// wants straightforward blocking I/O rather than participating in the tunnel's poll loop.
pub fn new_blocking_socket(addr: &IpAddr, sock_type: i32) -> io::Result<RawFd> {
    let domain = match addr {
        IpAddr::V4(_) => libc::AF_INET,
        IpAddr::V6(_) => libc::AF_INET6,
    };
    let fd = unsafe { libc::socket(domain, sock_type, 0) };
    if fd < 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(fd)
}

/// Applies send/receive timeouts.
///
/// Essential for the DoH worker: without them a resolver that accepts a connection and then
/// goes silent would block the thread forever, and every DNS query behind it would stall
/// rather than falling back to plaintext UDP.
pub fn set_timeouts(fd: RawFd, connect: Duration, io_timeout: Duration) -> io::Result<()> {
    let to_timeval = |d: Duration| libc::timeval {
        tv_sec: d.as_secs() as libc::time_t,
        tv_usec: d.subsec_micros() as libc::suseconds_t,
    };
    let snd = to_timeval(connect);
    let rcv = to_timeval(io_timeout);
    unsafe {
        if libc::setsockopt(
            fd,
            libc::SOL_SOCKET,
            libc::SO_SNDTIMEO,
            &snd as *const _ as *const libc::c_void,
            std::mem::size_of::<libc::timeval>() as libc::socklen_t,
        ) < 0
        {
            return Err(io::Error::last_os_error());
        }
        if libc::setsockopt(
            fd,
            libc::SOL_SOCKET,
            libc::SO_RCVTIMEO,
            &rcv as *const _ as *const libc::c_void,
            std::mem::size_of::<libc::timeval>() as libc::socklen_t,
        ) < 0
        {
            return Err(io::Error::last_os_error());
        }
    }
    Ok(())
}

/// Reads and clears `SO_ERROR`, used to resolve the outcome of a non-blocking connect.
pub fn socket_error(fd: RawFd) -> i32 {
    let mut err: libc::c_int = 0;
    let mut len = std::mem::size_of::<libc::c_int>() as libc::socklen_t;
    let rc = unsafe {
        libc::getsockopt(
            fd,
            libc::SOL_SOCKET,
            libc::SO_ERROR,
            &mut err as *mut _ as *mut libc::c_void,
            &mut len,
        )
    };
    if rc < 0 {
        -1
    } else {
        err
    }
}

pub const LOCALHOST_V4: Ipv4Addr = Ipv4Addr::new(127, 0, 0, 1);
pub const UNSPEC_V6: Ipv6Addr = Ipv6Addr::UNSPECIFIED;

#[cfg(test)]
mod tests {
    use super::*;
    use smoltcp::wire::{IpAddress, Ipv4Packet, UdpPacket};

    #[test]
    fn builds_valid_ipv4_udp() {
        let payload = b"hello dns";
        let buf = build_udp_packet(
            IpAddr::V4(Ipv4Addr::new(10, 0, 0, 53)),
            53,
            IpAddr::V4(Ipv4Addr::new(10, 0, 0, 2)),
            41234,
            payload,
        )
        .expect("v4 packet");

        let ip = Ipv4Packet::new_checked(&buf[..]).expect("valid ipv4 header");
        assert!(ip.verify_checksum(), "IPv4 header checksum must be valid");
        assert_eq!(ip.next_header(), IpProtocol::Udp);

        let udp = UdpPacket::new_checked(ip.payload()).expect("valid udp header");
        assert_eq!(udp.src_port(), 53);
        assert_eq!(udp.dst_port(), 41234);
        assert!(
            udp.verify_checksum(
                &IpAddress::from(ip.src_addr()),
                &IpAddress::from(ip.dst_addr())
            ),
            "UDP checksum must be valid"
        );
        assert_eq!(udp.payload(), payload);
    }

    #[test]
    fn rejects_mixed_address_families() {
        assert!(build_udp_packet(
            IpAddr::V4(Ipv4Addr::LOCALHOST),
            1,
            IpAddr::V6(Ipv6Addr::LOCALHOST),
            2,
            b"x"
        )
        .is_none());
    }

    #[test]
    fn sockaddr_encodes_port_big_endian() {
        let (storage, len) = sockaddr(&IpAddr::V4(Ipv4Addr::new(1, 1, 1, 1)), 53);
        assert_eq!(len as usize, std::mem::size_of::<libc::sockaddr_in>());
        let sin = &storage as *const _ as *const libc::sockaddr_in;
        unsafe {
            assert_eq!(u16::from_be((*sin).sin_port), 53);
        }
    }
}
