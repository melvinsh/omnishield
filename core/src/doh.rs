//! DNS-over-HTTPS upstream (RFC 8484).
//!
//! Without this the app sinkholes trackers and then announces every surviving query in
//! cleartext to the local network — which undercuts the entire premise.
//!
//! **Threading.** DoH runs on its own thread with blocking I/O rather than being woven into
//! the tunnel's `poll()` loop. DNS is a low-rate, request/response protocol that is nowhere
//! near the packet hot path, and keeping a TLS state machine out of the packet loop avoids
//! entangling two very different lifetimes. The tunnel hands over `(token, query)` and later
//! collects `(token, answer)`; the token carries the UDP session so replies can be routed back
//! to the app that asked.
//!
//! **Failure is visible.** A DoH failure falls back to plaintext UDP *and* raises a degraded
//! flag that the UI shows. Silently downgrading the transport would leave the user believing
//! their queries are encrypted when they are not.

use std::net::IpAddr;

#[cfg(target_os = "android")]
use std::time::Duration;

#[cfg(target_os = "android")]
const CONNECT_TIMEOUT: Duration = Duration::from_secs(5);
#[cfg(target_os = "android")]
const IO_TIMEOUT: Duration = Duration::from_secs(5);

const MAX_RESPONSE: usize = 64 * 1024;

/// Where to send DoH queries.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DohEndpoint {
    /// Host used for SNI and the `Host:` header. Usually an IP literal — see [`parse`].
    pub host: String,
    pub port: u16,
    pub path: String,
    /// Resolved address to connect to.
    pub addr: IpAddr,
}

/// Parses a DoH URL.
///
/// Only endpoints whose host is an **IP literal** are accepted. Resolving a DoH server's own
/// hostname would require DNS, which is precisely the thing being established — a
/// chicken-and-egg that would either deadlock or leak the bootstrap query in cleartext. The
/// well-known providers all publish IP-literal endpoints with matching IP SANs in their
/// certificates, so this costs nothing in practice.
pub fn parse(url: &str) -> Option<DohEndpoint> {
    let rest = url.strip_prefix("https://")?;
    let (authority, path) = match rest.find('/') {
        Some(i) => (&rest[..i], &rest[i..]),
        None => (rest, "/dns-query"),
    };

    let (host, port) = if let Some(stripped) = authority.strip_prefix('[') {
        // Bracketed IPv6 literal, optionally with :port.
        let close = stripped.find(']')?;
        let host = &stripped[..close];
        let port = stripped[close + 1..]
            .strip_prefix(':')
            .and_then(|p| p.parse().ok())
            .unwrap_or(443);
        (host.to_string(), port)
    } else {
        match authority.rsplit_once(':') {
            Some((h, p)) if p.chars().all(|c| c.is_ascii_digit()) => {
                (h.to_string(), p.parse().unwrap_or(443))
            }
            _ => (authority.to_string(), 443u16),
        }
    };

    let addr: IpAddr = host.parse().ok()?;
    Some(DohEndpoint {
        host,
        port,
        path: path.to_string(),
        addr,
    })
}

/// Builds the HTTP/1.1 request for a wire-format DNS query.
pub fn build_request(endpoint: &DohEndpoint, query: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(query.len() + 200);
    out.extend_from_slice(
        format!(
            "POST {} HTTP/1.1\r\n\
             Host: {}\r\n\
             Accept: application/dns-message\r\n\
             Content-Type: application/dns-message\r\n\
             Content-Length: {}\r\n\
             Connection: keep-alive\r\n\r\n",
            endpoint.path,
            endpoint.host,
            query.len(),
        )
        .as_bytes(),
    );
    out.extend_from_slice(query);
    out
}

/// Extracts the DNS message from an HTTP response, or `None` if it is incomplete.
///
/// Returns `Err` for a response that is complete but unusable (non-2xx, or chunked, which the
/// DoH providers do not use for this endpoint) so the caller can drop the connection rather
/// than wait forever for bytes that will never arrive.
pub fn parse_response(buf: &[u8]) -> Result<Option<Vec<u8>>, &'static str> {
    let mut headers = [httparse::EMPTY_HEADER; 32];
    let mut res = httparse::Response::new(&mut headers);
    let head_len = match res.parse(buf) {
        Ok(httparse::Status::Complete(n)) => n,
        Ok(httparse::Status::Partial) => return Ok(None),
        Err(_) => return Err("malformed response"),
    };

    let status = res.code.unwrap_or(0);
    if !(200..300).contains(&status) {
        return Err("non-2xx from resolver");
    }

    let mut content_length: Option<usize> = None;
    for h in res.headers.iter() {
        if h.name.eq_ignore_ascii_case("content-length") {
            content_length = std::str::from_utf8(h.value).ok().and_then(|v| v.trim().parse().ok());
        } else if h.name.eq_ignore_ascii_case("transfer-encoding")
            && std::str::from_utf8(h.value)
                .map(|v| v.to_ascii_lowercase().contains("chunked"))
                .unwrap_or(false)
        {
            return Err("chunked DoH response unsupported");
        }
    }

    let len = content_length.ok_or("missing content-length")?;
    if len > MAX_RESPONSE {
        return Err("oversized response");
    }
    if buf.len() < head_len + len {
        return Ok(None);
    }
    Ok(Some(buf[head_len..head_len + len].to_vec()))
}

// The transport below needs `VpnService.protect()`, so it only exists on device. Everything
// above this line is pure protocol handling and is unit-tested on the host.
#[cfg(target_os = "android")]
pub use transport::*;

#[cfg(target_os = "android")]
mod transport {
    use super::{build_request, parse_response, DohEndpoint, CONNECT_TIMEOUT, IO_TIMEOUT, MAX_RESPONSE};
    use crate::jvm::JavaBridge;
    use crate::net;
    use rustls::pki_types::ServerName;
    use rustls::{ClientConnection, StreamOwned};
    use std::io::{Read, Write};
    use std::os::unix::io::{FromRawFd, RawFd};
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::sync::mpsc::{self, Receiver, Sender, TryRecvError};
    use std::sync::Arc;

pub struct DohRequest {
    pub token: u64,
    pub query: Vec<u8>,
}

pub struct DohAnswer {
    pub token: u64,
    /// `None` when DoH failed and the caller should fall back to plaintext UDP.
    pub answer: Option<Vec<u8>>,
}

/// Handle held by the tunnel thread.
pub struct DohResolver {
    tx: Sender<DohRequest>,
    rx: Receiver<DohAnswer>,
    degraded: Arc<AtomicBool>,
}

impl DohResolver {
    /// `waker` is how an answer reaches a sleeping tunnel loop.
    ///
    /// Answers arrive on an mpsc channel that the loop only drains when it happens to be
    /// awake. That was fine when it woke five times a second regardless; now that it sleeps
    /// until something happens, an unannounced answer would sit in the channel until an
    /// unrelated packet arrived — every DNS lookup would appear to hang.
    pub fn start(
        endpoint: DohEndpoint,
        jvm: Arc<JavaBridge>,
        degraded: Arc<AtomicBool>,
        waker: Option<Arc<crate::wake::Waker>>,
    ) -> Self {
        let (req_tx, req_rx) = mpsc::channel::<DohRequest>();
        let (ans_tx, ans_rx) = mpsc::channel::<DohAnswer>();

        let worker_degraded = Arc::clone(&degraded);
        std::thread::Builder::new()
            .name("omnishield-doh".into())
            .spawn(move || worker(endpoint, jvm, req_rx, ans_tx, worker_degraded, waker))
            .ok();

        Self {
            tx: req_tx,
            rx: ans_rx,
            degraded,
        }
    }

    /// Queues a query. Returns false if the worker has died, so the caller can fall back.
    pub fn submit(&self, token: u64, query: Vec<u8>) -> bool {
        self.tx.send(DohRequest { token, query }).is_ok()
    }

    /// Non-blocking drain, called once per tunnel loop iteration.
    pub fn poll(&self) -> Vec<DohAnswer> {
        let mut out = Vec::new();
        loop {
            match self.rx.try_recv() {
                Ok(a) => out.push(a),
                Err(TryRecvError::Empty) | Err(TryRecvError::Disconnected) => break,
            }
        }
        out
    }

    pub fn is_degraded(&self) -> bool {
        self.degraded.load(Ordering::Relaxed)
    }
}

fn worker(
    endpoint: DohEndpoint,
    jvm: Arc<JavaBridge>,
    requests: Receiver<DohRequest>,
    answers: Sender<DohAnswer>,
    degraded: Arc<AtomicBool>,
    waker: Option<Arc<crate::wake::Waker>>,
) {
    let config = crate::mitm::client_config();
    let mut stream: Option<StreamOwned<ClientConnection, std::net::TcpStream>> = None;

    while let Ok(req) = requests.recv() {
        // One retry: a pooled connection the server closed between queries is the common
        // case, and failing the query for it would be needlessly fragile.
        let mut answer = None;
        for attempt in 0..2 {
            if stream.is_none() {
                stream = connect(&endpoint, &jvm, &config);
            }
            let Some(s) = stream.as_mut() else { break };

            match exchange(s, &endpoint, &req.query) {
                Ok(bytes) => {
                    answer = Some(bytes);
                    break;
                }
                Err(e) => {
                    log::debug!("doh exchange failed (attempt {attempt}): {e}");
                    stream = None;
                }
            }
        }

        if answer.is_none() {
            // Raise the flag but keep serving: the caller falls back to plaintext UDP so
            // resolution still works, and the UI tells the user it is no longer encrypted.
            if !degraded.swap(true, Ordering::Relaxed) {
                log::warn!("DoH unavailable; falling back to plaintext UDP");
            }
        } else {
            degraded.store(false, Ordering::Relaxed);
        }

        if answers.send(DohAnswer { token: req.token, answer }).is_err() {
            break; // tunnel gone
        }
        // Announce it. The send above only queues; the loop has to be running to drain it.
        if let Some(w) = &waker {
            w.wake();
        }
    }
    log::info!("doh worker finished");
}

fn connect(
    endpoint: &DohEndpoint,
    jvm: &Arc<JavaBridge>,
    config: &Arc<rustls::ClientConfig>,
) -> Option<StreamOwned<ClientConnection, std::net::TcpStream>> {
    let fd: RawFd = net::new_blocking_socket(&endpoint.addr, libc::SOCK_STREAM).ok()?;

    // Must escape the tunnel, or our own DoH traffic would be routed back into the TUN.
    if !jvm.protect(fd) {
        unsafe { libc::close(fd) };
        log::warn!("protect() failed for the DoH socket");
        return None;
    }
    net::set_timeouts(fd, CONNECT_TIMEOUT, IO_TIMEOUT).ok()?;

    let (sa, len) = net::sockaddr(&endpoint.addr, endpoint.port);
    let rc = unsafe { libc::connect(fd, &sa as *const _ as *const libc::sockaddr, len) };
    if rc < 0 {
        unsafe { libc::close(fd) };
        return None;
    }

    let tcp = unsafe { std::net::TcpStream::from_raw_fd(fd) };
    // ServerName accepts an IP literal and rustls will then require a matching IP SAN, which
    // is exactly the verification we want for an IP-addressed endpoint.
    let name = ServerName::try_from(endpoint.host.clone()).ok()?;
    let conn = ClientConnection::new(Arc::clone(config), name).ok()?;
    Some(StreamOwned::new(conn, tcp))
}

fn exchange(
    stream: &mut StreamOwned<ClientConnection, std::net::TcpStream>,
    endpoint: &DohEndpoint,
    query: &[u8],
) -> Result<Vec<u8>, String> {
    stream
        .write_all(&build_request(endpoint, query))
        .map_err(|e| e.to_string())?;
    stream.flush().map_err(|e| e.to_string())?;

    let mut buf = Vec::with_capacity(2048);
    let mut chunk = [0u8; 4096];
    loop {
        match parse_response(&buf) {
            Ok(Some(answer)) => return Ok(answer),
            Ok(None) => {}
            Err(e) => return Err(e.to_string()),
        }
        let n = stream.read(&mut chunk).map_err(|e| e.to_string())?;
        if n == 0 {
            return Err("connection closed before a complete response".into());
        }
        buf.extend_from_slice(&chunk[..n]);
        if buf.len() > MAX_RESPONSE {
            return Err("response too large".into());
        }
    }
}
} // mod transport

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_ip_literal_endpoints() {
        let e = parse("https://1.1.1.1/dns-query").expect("v4");
        assert_eq!(e.host, "1.1.1.1");
        assert_eq!(e.port, 443);
        assert_eq!(e.path, "/dns-query");

        let e = parse("https://9.9.9.9:8443/dns-query").expect("explicit port");
        assert_eq!(e.port, 8443);

        let e = parse("https://[2606:4700:4700::1111]/dns-query").expect("v6");
        assert_eq!(e.host, "2606:4700:4700::1111");
        assert_eq!(e.port, 443);
    }

    #[test]
    fn rejects_hostname_endpoints() {
        // Resolving the resolver would need DNS, which is the thing being set up.
        assert!(parse("https://cloudflare-dns.com/dns-query").is_none());
        assert!(parse("http://1.1.1.1/dns-query").is_none(), "plaintext is not DoH");
        assert!(parse("1.1.1.1/dns-query").is_none());
    }

    #[test]
    fn defaults_the_path() {
        assert_eq!(parse("https://1.1.1.1").unwrap().path, "/dns-query");
    }

    #[test]
    fn builds_rfc8484_request() {
        let e = parse("https://1.1.1.1/dns-query").unwrap();
        let req = String::from_utf8_lossy(&build_request(&e, &[0xAB, 0xCD])).to_string();
        assert!(req.starts_with("POST /dns-query HTTP/1.1\r\n"));
        assert!(req.contains("Host: 1.1.1.1\r\n"));
        assert!(req.contains("Content-Type: application/dns-message\r\n"));
        assert!(req.contains("Accept: application/dns-message\r\n"));
        assert!(req.contains("Content-Length: 2\r\n"));
    }

    #[test]
    fn extracts_body_once_complete() {
        let mut resp = b"HTTP/1.1 200 OK\r\nContent-Type: application/dns-message\r\nContent-Length: 3\r\n\r\n".to_vec();
        // Headers present, body still missing.
        assert_eq!(parse_response(&resp).unwrap(), None);
        resp.extend_from_slice(&[1, 2, 3]);
        assert_eq!(parse_response(&resp).unwrap(), Some(vec![1, 2, 3]));
    }

    #[test]
    fn partial_headers_are_not_an_error() {
        assert_eq!(parse_response(b"HTTP/1.1 200 O").unwrap(), None);
    }

    #[test]
    fn rejects_unusable_responses() {
        let err = parse_response(
            b"HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n",
        );
        assert!(err.is_err(), "non-2xx must not be treated as an answer");

        let chunked = parse_response(
            b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n",
        );
        assert!(chunked.is_err());

        let no_len = parse_response(b"HTTP/1.1 200 OK\r\nContent-Type: x\r\n\r\n");
        assert!(no_len.is_err(), "a body of unknown length would hang the worker");
    }
}
