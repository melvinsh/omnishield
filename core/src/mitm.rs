//! Layer 2: TLS termination and re-origination.
//!
//! Scope reality check, restated because it governs every design choice here: since Android 7
//! apps ignore user-installed CAs unless they opt in via `network_security_config.xml`. On a
//! stock device this reaches Chrome-family browsers and essentially nothing else. Interception
//! is therefore **opt-in per UID and bypassed by default** — see `mitm_uids` in the config.
//!
//! Both sides pin ALPN to `http/1.1`. Negotiating h2 would mean implementing HTTP/2 framing
//! for no filtering benefit.

use std::io::{Read, Write};
use std::sync::Arc;

use rustls::pki_types::ServerName;
use rustls::server::{ClientHello, ResolvesServerCert};
use rustls::sign::CertifiedKey;
use rustls::{ClientConfig, ClientConnection, RootCertStore, ServerConfig, ServerConnection};

use crate::ca::CertAuthority;
use crate::content::{self, ContentFilter, ResponseHead};

/// Installs the ring crypto provider exactly once. rustls 0.23 requires a process-wide
/// provider before any config can be built.
pub fn init_crypto() {
    use std::sync::Once;
    static ONCE: Once = Once::new();
    ONCE.call_once(|| {
        let _ = rustls::crypto::ring::default_provider().install_default();
    });
}

/// Mints a leaf for whatever SNI the client asked for, on demand.
struct SniResolver {
    ca: Arc<CertAuthority>,
}

// rustls requires ResolvesServerCert: Debug. Implemented by hand so CertAuthority — which
// holds private key material — never gets a derived Debug that could print it.
impl std::fmt::Debug for SniResolver {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("SniResolver")
    }
}

impl ResolvesServerCert for SniResolver {
    fn resolve(&self, hello: ClientHello<'_>) -> Option<Arc<CertifiedKey>> {
        let sni = hello.server_name()?;
        match self.ca.leaf_for(sni) {
            Ok(key) => Some(key),
            Err(e) => {
                log::warn!("cannot mint leaf for {sni}: {e}");
                None
            }
        }
    }
}

pub fn server_config(ca: Arc<CertAuthority>) -> Arc<ServerConfig> {
    init_crypto();
    let mut config = ServerConfig::builder()
        .with_no_client_auth()
        .with_cert_resolver(Arc::new(SniResolver { ca }));
    config.alpn_protocols = vec![b"http/1.1".to_vec()];
    Arc::new(config)
}

pub fn client_config() -> Arc<ClientConfig> {
    init_crypto();
    let mut roots = RootCertStore::empty();
    roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
    let mut config = ClientConfig::builder()
        .with_root_certificates(roots)
        .with_no_client_auth();
    config.alpn_protocols = vec![b"http/1.1".to_vec()];
    Arc::new(config)
}

/// One intercepted TLS connection.
pub struct MitmSession {
    server: ServerConnection,
    client: Option<ClientConnection>,
    client_config: Arc<ClientConfig>,
    pub sni: Option<String>,

    /// Plaintext accumulated from the app until the request head is complete.
    req_buf: Vec<u8>,
    req_forwarded: bool,

    /// Plaintext accumulated from the origin. Held until EOF so the body can be rewritten.
    resp_buf: Vec<u8>,
    resp_head: Option<ResponseHead>,
    resp_flushed: bool,

    /// Set when the app rejected our certificate — the pinning escape hatch.
    pub handshake_rejected: bool,
    pub blocked_url: Option<String>,
}

impl MitmSession {
    pub fn new(server_config: Arc<ServerConfig>, client_config: Arc<ClientConfig>) -> Option<Self> {
        let mut server = ServerConnection::new(server_config).ok()?;

        // rustls caps buffered plaintext at 64 KiB by default and returns WouldBlock past it.
        // A rewritten HTML page is routinely larger than that, and silently truncating it
        // while the rewritten Content-Length advertises the full size renders as a blank
        // page. We already hold the whole body in memory to rewrite it, so removing the cap
        // costs nothing extra.
        server.set_buffer_limit(None);

        Some(Self {
            server,
            client: None,
            client_config,
            sni: None,
            req_buf: Vec::new(),
            req_forwarded: false,
            resp_buf: Vec::new(),
            resp_head: None,
            resp_flushed: false,
            handshake_rejected: false,
            blocked_url: None,
        })
    }

    /// Feeds ciphertext arriving from the app.
    pub fn app_in(&mut self, data: &[u8]) {
        let mut cursor = std::io::Cursor::new(data);
        while (cursor.position() as usize) < data.len() {
            if self.server.read_tls(&mut cursor).is_err() {
                break;
            }
            if let Err(e) = self.server.process_new_packets() {
                // A client that refuses our certificate lands here. That is the signal the
                // firewall uses to bypass this app permanently rather than leave it broken.
                log::debug!("client rejected interception ({:?}): {e}", self.sni);
                self.handshake_rejected = true;
                return;
            }
        }
        if self.sni.is_none() {
            self.sni = self.server.server_name().map(str::to_string);
        }
    }

    /// Ciphertext to send back to the app.
    pub fn app_out(&mut self) -> Vec<u8> {
        let mut out = Vec::new();
        while self.server.wants_write() {
            if self.server.write_tls(&mut out).is_err() {
                break;
            }
        }
        out
    }

    pub fn origin_in(&mut self, data: &[u8]) {
        let Some(client) = self.client.as_mut() else {
            return;
        };
        let mut cursor = std::io::Cursor::new(data);
        while (cursor.position() as usize) < data.len() {
            if client.read_tls(&mut cursor).is_err() {
                break;
            }
            if let Err(e) = client.process_new_packets() {
                log::debug!("upstream TLS error for {:?}: {e}", self.sni);
                return;
            }
        }
    }

    pub fn origin_out(&mut self) -> Vec<u8> {
        let Some(client) = self.client.as_mut() else {
            return Vec::new();
        };
        let mut out = Vec::new();
        while client.wants_write() {
            if client.write_tls(&mut out).is_err() {
                break;
            }
        }
        out
    }

    /// True once the app-facing handshake is done and an upstream connection is warranted.
    pub fn needs_upstream(&self) -> bool {
        self.client.is_none() && !self.server.is_handshaking() && self.sni.is_some()
    }

    pub fn start_upstream(&mut self) -> bool {
        let Some(sni) = self.sni.clone() else {
            return false;
        };
        let Ok(name) = ServerName::try_from(sni.clone()) else {
            return false;
        };
        match ClientConnection::new(Arc::clone(&self.client_config), name.to_owned()) {
            Ok(mut c) => {
                c.set_buffer_limit(None); // same truncation hazard on request bodies
                self.client = Some(c);
                true
            }
            Err(e) => {
                log::warn!("cannot open upstream TLS to {sni}: {e}");
                false
            }
        }
    }

    /// Moves plaintext between the two sides, applying the filter.
    pub fn pump(&mut self, filter: &ContentFilter) {
        self.pump_request(filter);
        self.pump_response(filter);
    }

    fn pump_request(&mut self, filter: &ContentFilter) {
        let mut buf = [0u8; 16 * 1024];
        loop {
            match self.server.reader().read(&mut buf) {
                Ok(0) => break,
                Ok(n) => self.req_buf.extend_from_slice(&buf[..n]),
                Err(_) => break,
            }
        }
        if self.req_forwarded || self.req_buf.is_empty() {
            return;
        }

        let Some(head) = content::parse_request(&self.req_buf) else {
            return; // headers still incomplete
        };
        let sni = self.sni.clone().unwrap_or_default();
        let host = if head.host.is_empty() {
            sni.clone()
        } else {
            head.host.clone()
        };
        let url = format!("https://{host}{}", head.path);
        let source = format!("https://{host}/");

        if filter.blocks(&url, &source, request_type_for(&head.path)) {
            self.blocked_url = Some(url);
            let _ = self.server.writer().write_all(&content::blocked_response());
            self.server.send_close_notify();
            self.req_forwarded = true;
            return;
        }

        if let Some(client) = self.client.as_mut() {
            let forwarded = content::rewrite_request(&self.req_buf, &head);
            let _ = client.writer().write_all(&forwarded);
            self.req_forwarded = true;
            log::debug!("mitm req {} {}{}", head.method, host, head.path);
        }
    }

    fn pump_response(&mut self, _filter: &ContentFilter) {
        let Some(client) = self.client.as_mut() else {
            return;
        };
        let mut buf = [0u8; 16 * 1024];
        loop {
            match client.reader().read(&mut buf) {
                Ok(0) => break,
                Ok(n) => self.resp_buf.extend_from_slice(&buf[..n]),
                Err(_) => break,
            }
        }
        if self.resp_head.is_none() {
            self.resp_head = content::parse_response(&self.resp_buf);
        }
    }

    /// Called when the origin closes. Because `Connection: close` is forced, EOF delimits the
    /// body exactly, so this is where the response is rewritten and released to the app.
    pub fn finish_response(&mut self, filter: &ContentFilter) -> bool {
        if self.resp_flushed || self.blocked_url.is_some() {
            return false;
        }
        self.resp_flushed = true;

        log::debug!(
            "mitm resp sni={:?} bytes={} head={:?}",
            self.sni,
            self.resp_buf.len(),
            self.resp_head.as_ref().map(|h| (
                h.status,
                h.content_type.clone(),
                h.is_chunked(),
                h.content_encoding.clone()
            ))
        );

        let Some(head) = self.resp_head.clone() else {
            // Never got a parseable response; relay whatever arrived verbatim.
            if !self.resp_buf.is_empty() {
                let body = std::mem::take(&mut self.resp_buf);
                let _ = self.server.writer().write_all(&body);
            }
            self.server.send_close_notify();
            return false;
        };

        let mut rewritten = false;

        // The overwhelming majority of an intercepted connection's responses are images,
        // scripts, fonts and stylesheets, none of which are rewritten. That path used to end
        // in `self.resp_buf.clone()` — a full copy of every asset on the page, made only to be
        // written out and dropped. Taking the buffer instead costs nothing.
        if !head.content_type.contains("text/html") {
            let out = std::mem::take(&mut self.resp_buf);
            self.emit(&out, rewritten);
            return false;
        }

        // Headers are small, so owning them is cheap — and it releases the borrow on
        // `resp_buf`, which is what lets the fallbacks below move the buffer rather than clone
        // it.
        let raw_head = self.resp_buf[..head.headers_len].to_vec();

        // 1. strip chunked framing, 2. decompress, 3. inject. Every step can bail; any failure
        // relays the original bytes verbatim rather than emitting something half-transformed.
        let plain = {
            let body = &self.resp_buf[head.headers_len..];
            let unframed = if head.is_chunked() {
                content::dechunk(body)
            } else {
                Some(body.to_vec())
            };
            unframed.and_then(|b| content::decompress(&b, &head.content_encoding))
        };

        let sni = self.sni.clone().unwrap_or_default();
        let url = format!("https://{sni}/");

        let out = match plain {
            Some(plain) => {
                // Document-aware: picks up the generic rules matching classes/ids that are
                // actually in this page, not just the domain-specific ones.
                let injected = filter
                    .cosmetic_css_for_document(&url, &plain)
                    .and_then(|css| content::inject_css(&plain, &css));
                match injected {
                    Some(html) => {
                        rewritten = true;
                        let mut v = content::rewrite_response_headers(&raw_head, html.len());
                        v.extend_from_slice(&html);
                        v
                    }
                    // Nothing to hide on this domain, but the body was de-framed or
                    // decompressed, so the original headers no longer describe it.
                    None if head.is_chunked() || !head.content_encoding.is_empty() => {
                        let mut v = content::rewrite_response_headers(&raw_head, plain.len());
                        v.extend_from_slice(&plain);
                        v
                    }
                    None => std::mem::take(&mut self.resp_buf),
                }
            }
            None => std::mem::take(&mut self.resp_buf),
        };

        self.emit(&out, rewritten);
        rewritten
    }

    /// Writes the final body to the app and closes the TLS session.
    fn emit(&mut self, out: &[u8], rewritten: bool) {
        // Never ignore this result: a short write here means the client receives fewer bytes
        // than Content-Length promises, which is indistinguishable from a corrupt page.
        if let Err(e) = self.server.writer().write_all(out) {
            log::error!(
                "truncated response to {:?} ({} bytes): {e}",
                self.sni,
                out.len()
            );
        }
        log::debug!(
            "mitm emit sni={:?} bytes={} rewritten={rewritten}",
            self.sni,
            out.len()
        );
        self.server.send_close_notify();
        self.resp_buf.clear();
    }

    pub fn upstream_started(&self) -> bool {
        self.client.is_some()
    }
}

/// Maps a path to an ABP request type so network rules with `$script`/`$image` options match.
fn request_type_for(path: &str) -> &'static str {
    let p = path.split('?').next().unwrap_or(path).to_ascii_lowercase();
    if p.ends_with(".js") {
        "script"
    } else if p.ends_with(".css") {
        "stylesheet"
    } else if p.ends_with(".png")
        || p.ends_with(".jpg")
        || p.ends_with(".jpeg")
        || p.ends_with(".gif")
        || p.ends_with(".webp")
        || p.ends_with(".svg")
    {
        "image"
    } else if p.ends_with(".woff") || p.ends_with(".woff2") || p.ends_with(".ttf") {
        "font"
    } else {
        "document"
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn classifies_request_types() {
        assert_eq!(request_type_for("/a/b.js"), "script");
        assert_eq!(request_type_for("/a/b.js?v=2"), "script");
        assert_eq!(request_type_for("/img/x.PNG"), "image");
        assert_eq!(request_type_for("/style.css"), "stylesheet");
        assert_eq!(request_type_for("/page"), "document");
    }

    #[test]
    fn builds_configs_without_panicking() {
        let dir = std::env::temp_dir().join(format!("omnishield-mitm-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let ca = Arc::new(CertAuthority::load_or_create(&dir).expect("ca"));

        let sc = server_config(Arc::clone(&ca));
        assert_eq!(sc.alpn_protocols, vec![b"http/1.1".to_vec()]);

        let cc = client_config();
        assert_eq!(cc.alpn_protocols, vec![b"http/1.1".to_vec()]);

        // A session must be constructible from those configs.
        assert!(MitmSession::new(sc, cc).is_some());
        let _ = std::fs::remove_dir_all(&dir);
    }
}
