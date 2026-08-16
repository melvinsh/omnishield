//! Layer 3: network-rule blocking and cosmetic filtering of decrypted HTTP.
//!
//! Two simplifications keep this tractable and are worth stating plainly:
//!
//! 1. **ALPN is pinned to `http/1.1`** on both sides of the MITM, so we never have to speak
//!    HTTP/2 framing.
//! 2. **`Connection: close` is forced** on every forwarded request, making each TLS
//!    connection carry exactly one exchange. The response body then simply runs to EOF, which
//!    removes the entire keep-alive framing problem (Content-Length vs chunked vs neither).
//!    The cost is more connections per page; the benefit is that body handling cannot
//!    desynchronise and corrupt a stream.

use std::io::Read;

use adblock::lists::{FilterSet, ParseOptions};
use adblock::request::Request;
use adblock::Engine;
use flate2::read::{GzDecoder, ZlibDecoder};

/// Largest HTML body we will buffer for rewriting. Anything larger is passed through
/// untouched rather than held in memory.
const MAX_REWRITE_BYTES: usize = 4 * 1024 * 1024;

pub struct ContentFilter {
    engine: Engine,
    rules: usize,
}

impl ContentFilter {
    pub fn new() -> Self {
        Self {
            engine: Engine::new_with_filter_set(FilterSet::new(false)),
            rules: 0,
        }
    }

    pub fn rules(&self) -> usize {
        self.rules
    }

    /// Replaces the engine with one built from `lists` (ABP syntax, e.g. EasyList).
    ///
    /// `list` is moved into the parser rather than cloned. It used to be `list.clone()`, which
    /// held a second full copy of every rule list alive alongside the caller's — several MB,
    /// at the same moment the old and new engines both existed.
    pub fn load(&mut self, lists: Vec<String>) {
        let mut set = FilterSet::new(false);
        let mut count = 0usize;
        for list in lists {
            count += list.lines().filter(|l| is_rule_line(l)).count();
            set.add_filter_list(list, ParseOptions::default());
        }
        self.engine = Engine::new_with_filter_set(set);
        self.rules = count;
    }

    /// Serialises the built engine so a later start can skip parsing the rule text.
    ///
    /// Building this engine from ~135k raw rules is the single most expensive step of a tunnel
    /// start; `adblock` can round-trip its own compiled form far more cheaply.
    pub fn to_cache_bytes(&self) -> Vec<u8> {
        let mut out = (self.rules as u64).to_le_bytes().to_vec();
        out.extend_from_slice(&self.engine.serialize());
        out
    }

    /// Restores an engine written by [`to_cache_bytes`].
    ///
    /// Returns false on anything unusable so the caller falls back to parsing. A rejected cache
    /// costs a slow start; a trusted bad one would mean filtering silently doing nothing.
    pub fn load_cache_bytes(&mut self, buf: &[u8]) -> bool {
        if buf.len() < 8 {
            return false;
        }
        let rules = u64::from_le_bytes(match buf[0..8].try_into() {
            Ok(b) => b,
            Err(_) => return false,
        }) as usize;
        let mut engine = Engine::new_with_filter_set(FilterSet::new(false));
        if engine.deserialize(&buf[8..]).is_err() {
            return false;
        }
        self.engine = engine;
        self.rules = rules;
        true
    }

    /// True when `url` should be refused outright.
    pub fn blocks(&self, url: &str, source_url: &str, request_type: &str) -> bool {
        match Request::new(url, source_url, request_type, "get") {
            // should_block() accounts for @@ exception rules, which a bare `filter.is_some()`
            // would ignore and over-block on.
            Ok(req) => self.engine.check_network_request(&req).should_block(),
            Err(_) => false,
        }
    }

    /// Domain-specific hiding rules for `url`, or `None` if there are none.
    ///
    /// This is only half the picture — see [`cosmetic_css_for_document`], which is what
    /// callers holding the page body should use.
    ///
    /// [`cosmetic_css_for_document`]: Self::cosmetic_css_for_document
    pub fn cosmetic_css(&self, url: &str) -> Option<String> {
        let resources = self.engine.url_cosmetic_resources(url);
        if resources.hide_selectors.is_empty() {
            return None;
        }
        Some(build_css(resources.hide_selectors.iter().map(String::as_str)))
    }

    /// Hiding rules for `url`, including the **generic** ones that apply to `html`.
    ///
    /// `url_cosmetic_resources` deliberately returns only domain-specific selectors. Generic
    /// rules like `##.adsbox` number in the hundreds of thousands, so adblock-rust withholds
    /// them and expects the caller to look up just those matching the class and id attributes
    /// actually present in the document. Skipping that step is why a page's `.adsbox` bait
    /// element stays visible even with EasyList fully loaded — the selector is never in the
    /// stylesheet we inject.
    ///
    /// A `$generichide` exception on the page suppresses the generic half, as it should.
    ///
    /// This costs a full parse of `html` whose output is discarded, and [`inject_css`] then
    /// parses the same document again. That is not redundancy that can be optimised away: the
    /// stylesheet is injected into `<head>`, which a streaming rewriter emits long before it
    /// has seen the class attributes further down the page. Knowing the selectors up front
    /// requires having already read the document.
    ///
    /// What *was* wasteful is now fixed — the size guard used to live only in `inject_css`, so
    /// an oversized document was fully parsed and had its selectors computed here before being
    /// rejected downstream.
    pub fn cosmetic_css_for_document(&self, url: &str, html: &[u8]) -> Option<String> {
        if html.len() > MAX_REWRITE_BYTES {
            return None;
        }
        let resources = self.engine.url_cosmetic_resources(url);

        let mut selectors: Vec<String> = resources.hide_selectors.into_iter().collect();

        if !resources.generichide {
            let (classes, ids) = collect_class_ids(html);
            if !classes.is_empty() || !ids.is_empty() {
                selectors.extend(self.engine.hidden_class_id_selectors(
                    &classes,
                    &ids,
                    &resources.exceptions,
                ));
            }
        }

        if selectors.is_empty() {
            return None;
        }
        Some(build_css(selectors.iter().map(String::as_str)))
    }
}

fn build_css<'a>(selectors: impl Iterator<Item = &'a str>) -> String {
    let mut list: Vec<&str> = selectors.collect();
    list.sort_unstable();
    list.dedup();
    format!("{}{{display:none !important;}}", list.join(","))
}

/// Collects every `class` token and `id` value in a document.
///
/// Uses lol_html rather than a regex so malformed markup, odd quoting and duplicated
/// attributes are handled by a real parser. Over-collecting is harmless: a class that matches
/// no rule simply yields no selector.
fn collect_class_ids(html: &[u8]) -> (Vec<String>, Vec<String>) {
    use lol_html::{element, HtmlRewriter, Settings};
    use std::cell::RefCell;
    use std::collections::HashSet;
    use std::rc::Rc;

    let classes: Rc<RefCell<HashSet<String>>> = Rc::new(RefCell::new(HashSet::new()));
    let ids: Rc<RefCell<HashSet<String>>> = Rc::new(RefCell::new(HashSet::new()));

    {
        let c = Rc::clone(&classes);
        let i = Rc::clone(&ids);
        let settings = Settings::new()
            .append_element_content_handler(element!("[class]", move |el| {
                if let Some(value) = el.get_attribute("class") {
                    let mut set = c.borrow_mut();
                    for token in value.split_whitespace() {
                        set.insert(token.to_string());
                    }
                }
                Ok(())
            }))
            .append_element_content_handler(element!("[id]", move |el| {
                if let Some(value) = el.get_attribute("id") {
                    i.borrow_mut().insert(value);
                }
                Ok(())
            }));

        // Output is discarded; this pass only harvests attributes.
        let mut rewriter = HtmlRewriter::new(settings, |_: &[u8]| {});
        if rewriter.write(html).is_err() {
            return (Vec::new(), Vec::new());
        }
        let _ = rewriter.end();
    }

    let classes = Rc::try_unwrap(classes).map(RefCell::into_inner).unwrap_or_default();
    let ids = Rc::try_unwrap(ids).map(RefCell::into_inner).unwrap_or_default();
    (classes.into_iter().collect(), ids.into_iter().collect())
}

impl Default for ContentFilter {
    fn default() -> Self {
        Self::new()
    }
}

fn is_rule_line(line: &str) -> bool {
    let t = line.trim();
    !t.is_empty() && !t.starts_with('!') && !t.starts_with('[')
}

// ---------------------------------------------------------------------------
// HTTP message handling
// ---------------------------------------------------------------------------

#[derive(Debug, Clone)]
pub struct RequestHead {
    pub method: String,
    pub path: String,
    pub host: String,
    pub headers_len: usize,
}

/// Parses request headers once they are complete. `None` means "need more bytes".
pub fn parse_request(buf: &[u8]) -> Option<RequestHead> {
    let mut headers = [httparse::EMPTY_HEADER; 64];
    let mut req = httparse::Request::new(&mut headers);
    match req.parse(buf) {
        Ok(httparse::Status::Complete(len)) => {
            let host = req
                .headers
                .iter()
                .find(|h| h.name.eq_ignore_ascii_case("host"))
                .and_then(|h| std::str::from_utf8(h.value).ok())
                .unwrap_or_default()
                .to_string();
            Some(RequestHead {
                method: req.method.unwrap_or("GET").to_string(),
                path: req.path.unwrap_or("/").to_string(),
                host,
                headers_len: len,
            })
        }
        _ => None,
    }
}

/// Rewrites a request head for forwarding.
///
/// `Accept-Encoding` is narrowed to gzip/deflate so we never have to decode Brotli or zstd to
/// rewrite HTML, and `Connection: close` is forced so the response body runs to EOF.
pub fn rewrite_request(buf: &[u8], head: &RequestHead) -> Vec<u8> {
    let raw = String::from_utf8_lossy(&buf[..head.headers_len]);
    let mut out = String::with_capacity(raw.len() + 64);
    let mut lines = raw.split("\r\n");

    if let Some(request_line) = lines.next() {
        out.push_str(request_line);
        out.push_str("\r\n");
    }
    for line in lines {
        if line.is_empty() {
            continue;
        }
        let lower = line.to_ascii_lowercase();
        if lower.starts_with("accept-encoding:")
            || lower.starts_with("connection:")
            || lower.starts_with("keep-alive:")
            || lower.starts_with("upgrade-insecure-requests:")
            || lower.starts_with("te:")
        {
            continue;
        }
        out.push_str(line);
        out.push_str("\r\n");
    }
    out.push_str("Accept-Encoding: gzip, deflate\r\n");
    out.push_str("Connection: close\r\n\r\n");

    let mut bytes = out.into_bytes();
    bytes.extend_from_slice(&buf[head.headers_len..]);
    bytes
}

#[derive(Debug, Clone)]
pub struct ResponseHead {
    pub status: u16,
    pub headers_len: usize,
    pub content_type: String,
    pub content_encoding: String,
    pub transfer_encoding: String,
}

impl ResponseHead {
    pub fn is_chunked(&self) -> bool {
        self.transfer_encoding.contains("chunked")
    }
}

pub fn parse_response(buf: &[u8]) -> Option<ResponseHead> {
    let mut headers = [httparse::EMPTY_HEADER; 64];
    let mut res = httparse::Response::new(&mut headers);
    match res.parse(buf) {
        Ok(httparse::Status::Complete(len)) => {
            let get = |name: &str| {
                res.headers
                    .iter()
                    .find(|h| h.name.eq_ignore_ascii_case(name))
                    .and_then(|h| std::str::from_utf8(h.value).ok())
                    .unwrap_or_default()
                    .to_ascii_lowercase()
            };
            Some(ResponseHead {
                status: res.code.unwrap_or(0),
                headers_len: len,
                content_type: get("content-type"),
                content_encoding: get("content-encoding"),
                transfer_encoding: get("transfer-encoding"),
            })
        }
        _ => None,
    }
}

/// A minimal refusal served in place of a blocked subresource.
///
/// 204 rather than a connection reset: a browser treats a reset as a network error and may
/// retry or surface it, whereas an empty 204 is a clean "nothing here".
pub fn blocked_response() -> Vec<u8> {
    b"HTTP/1.1 204 No Content\r\n\
      Content-Length: 0\r\n\
      X-OmniShield: blocked\r\n\
      Connection: close\r\n\r\n"
        .to_vec()
}

/// Removes HTTP/1.1 chunked transfer framing.
///
/// Forcing `Connection: close` bounds the body at EOF but does *not* stop a server using
/// chunked encoding, and the chunk-size lines are not part of the entity. Feeding them
/// straight to the HTML rewriter produces garbage and a `Content-Length` that does not match
/// the real body — which renders as a blank page. Returns `None` on malformed framing so the
/// caller relays the original bytes rather than emitting something corrupt.
pub fn dechunk(body: &[u8]) -> Option<Vec<u8>> {
    let mut out = Vec::with_capacity(body.len());
    let mut pos = 0usize;

    loop {
        let line_end = find_crlf(body, pos)?;
        let size_line = std::str::from_utf8(&body[pos..line_end]).ok()?;
        // A chunk extension may follow the size after a ';'.
        let size_str = size_line.split(';').next()?.trim();
        let size = usize::from_str_radix(size_str, 16).ok()?;
        pos = line_end + 2;

        if size == 0 {
            return Some(out); // trailers, if any, are dropped with the framing
        }
        if pos + size > body.len() {
            return None; // truncated
        }
        out.extend_from_slice(&body[pos..pos + size]);
        pos += size;

        // Each chunk is followed by its own CRLF.
        if body.get(pos) == Some(&b'\r') && body.get(pos + 1) == Some(&b'\n') {
            pos += 2;
        } else {
            return None;
        }
    }
}

fn find_crlf(buf: &[u8], from: usize) -> Option<usize> {
    let mut i = from;
    while i + 1 < buf.len() {
        if buf[i] == b'\r' && buf[i + 1] == b'\n' {
            return Some(i);
        }
        i += 1;
    }
    None
}

pub fn decompress(body: &[u8], encoding: &str) -> Option<Vec<u8>> {
    let mut out = Vec::new();
    let result = if encoding.contains("gzip") {
        GzDecoder::new(body).read_to_end(&mut out)
    } else if encoding.contains("deflate") {
        ZlibDecoder::new(body).read_to_end(&mut out)
    } else {
        return Some(body.to_vec());
    };
    result.ok().map(|_| out)
}

/// Injects `css` into `<head>` of an HTML document.
///
/// Uses lol_html's streaming rewriter rather than string surgery, so malformed markup, odd
/// casing and attributes inside `<head>` are handled by a real parser.
pub fn inject_css(html: &[u8], css: &str) -> Option<Vec<u8>> {
    use lol_html::html_content::ContentType;
    use lol_html::{element, HtmlRewriter, Settings};

    if html.len() > MAX_REWRITE_BYTES {
        return None;
    }
    let style = format!("<style id=\"omnishield-cosmetic\">{css}</style>");

    // lol_html 3 made Settings' fields private; construction is a consuming builder now.
    let settings = Settings::new().append_element_content_handler(element!("head", |el| {
        el.prepend(&style, ContentType::Html);
        Ok(())
    }));

    let mut output = Vec::with_capacity(html.len() + style.len());
    let mut rewriter = HtmlRewriter::new(settings, |chunk: &[u8]| {
        output.extend_from_slice(chunk)
    });

    if rewriter.write(html).is_err() {
        return None;
    }
    if rewriter.end().is_err() {
        return None;
    }
    Some(output)
}

/// Rebuilds response headers for a body we rewrote: the original `Content-Length` is wrong
/// once bytes are injected, and the body is no longer compressed.
pub fn rewrite_response_headers(raw_head: &[u8], new_len: usize) -> Vec<u8> {
    let raw = String::from_utf8_lossy(raw_head);
    let mut out = String::with_capacity(raw.len() + 64);
    let mut lines = raw.split("\r\n");

    if let Some(status_line) = lines.next() {
        out.push_str(status_line);
        out.push_str("\r\n");
    }
    for line in lines {
        if line.is_empty() {
            continue;
        }
        let lower = line.to_ascii_lowercase();
        if lower.starts_with("content-length:")
            || lower.starts_with("content-encoding:")
            || lower.starts_with("transfer-encoding:")
            || lower.starts_with("connection:")
        {
            continue;
        }
        out.push_str(line);
        out.push_str("\r\n");
    }
    out.push_str(&format!("Content-Length: {new_len}\r\n"));
    out.push_str("X-OmniShield: filtered\r\n");
    out.push_str("Connection: close\r\n\r\n");
    out.into_bytes()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn blocks_by_network_rule() {
        let mut f = ContentFilter::new();
        f.load(vec!["||ads.example.com^".to_string()]);
        assert!(f.blocks(
            "https://ads.example.com/banner.js",
            "https://news.example.org/",
            "script"
        ));
        assert!(!f.blocks(
            "https://cdn.example.org/app.js",
            "https://news.example.org/",
            "script"
        ));
    }

    #[test]
    fn blocks_by_path_rule() {
        let mut f = ContentFilter::new();
        f.load(vec!["||example.com/ads/*".to_string()]);
        assert!(f.blocks("https://example.com/ads/track.gif", "https://example.com/", "image"));
        assert!(!f.blocks("https://example.com/img/logo.png", "https://example.com/", "image"));
    }

    #[test]
    fn produces_cosmetic_css() {
        let mut f = ContentFilter::new();
        f.load(vec!["news.example.org##.ad-banner".to_string()]);
        let css = f.cosmetic_css("https://news.example.org/index.html");
        assert!(css.is_some(), "expected a hiding rule for this domain");
        assert!(css.unwrap().contains("display:none"));
    }

    #[test]
    fn parses_request_and_forces_close() {
        let raw = b"GET /page HTTP/1.1\r\nHost: example.com\r\nAccept-Encoding: br, zstd\r\nConnection: keep-alive\r\n\r\n";
        let head = parse_request(raw).expect("complete request");
        assert_eq!(head.method, "GET");
        assert_eq!(head.host, "example.com");

        let out = String::from_utf8(rewrite_request(raw, &head)).unwrap();
        assert!(out.contains("Connection: close"));
        assert!(out.contains("Accept-Encoding: gzip, deflate"));
        assert!(!out.contains("br, zstd"), "brotli/zstd must be negotiated away");
        assert!(!out.contains("keep-alive"));
    }

    #[test]
    fn incomplete_request_returns_none() {
        assert!(parse_request(b"GET /page HTTP/1.1\r\nHost: exa").is_none());
    }

    #[test]
    fn parses_response_head() {
        let raw = b"HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Encoding: gzip\r\n\r\nbody";
        let head = parse_response(raw).expect("complete response");
        assert_eq!(head.status, 200);
        assert!(head.content_type.contains("text/html"));
        assert_eq!(head.content_encoding, "gzip");
    }

    #[test]
    fn injects_css_into_head() {
        let html = b"<html><head><title>x</title></head><body>hi</body></html>";
        let out = inject_css(html, ".ad{display:none}").expect("rewritten");
        let s = String::from_utf8(out).unwrap();
        assert!(s.contains("omnishield-cosmetic"));
        assert!(s.contains(".ad{display:none}"));
        assert!(s.contains("<title>x</title>"), "original content preserved");
    }

    #[test]
    fn rewritten_headers_fix_length_and_drop_encoding() {
        let head = b"HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: 5\r\nContent-Encoding: gzip\r\n\r\n";
        let out = String::from_utf8(rewrite_response_headers(head, 1234)).unwrap();
        assert!(out.contains("Content-Length: 1234"));
        assert!(!out.to_ascii_lowercase().contains("content-encoding"));
        assert!(out.contains("Content-Type: text/html"));
    }

    #[test]
    fn dechunks_body() {
        // "Wiki" + "pedia" split across two chunks, then the terminator.
        let body = b"4\r\nWiki\r\n5\r\npedia\r\n0\r\n\r\n";
        assert_eq!(dechunk(body).unwrap(), b"Wikipedia");
    }

    #[test]
    fn dechunk_handles_extensions_and_empty_body() {
        assert_eq!(dechunk(b"4;name=value\r\nWiki\r\n0\r\n\r\n").unwrap(), b"Wiki");
        assert_eq!(dechunk(b"0\r\n\r\n").unwrap(), b"");
    }

    #[test]
    fn dechunk_rejects_malformed_rather_than_guessing() {
        // Truncated chunk — must not return a partial body that would then be given a
        // Content-Length describing bytes the client never receives.
        assert!(dechunk(b"9\r\nWiki\r\n").is_none());
        assert!(dechunk(b"zz\r\nWiki\r\n").is_none());
        assert!(dechunk(b"4\r\nWiki").is_none());
    }

    #[test]
    fn detects_chunked_header() {
        let raw = b"HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nTransfer-Encoding: chunked\r\n\r\n";
        assert!(parse_response(raw).unwrap().is_chunked());
        let plain = b"HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n";
        assert!(!parse_response(plain).unwrap().is_chunked());
    }

    #[test]
    fn chunked_gzip_html_survives_full_pipeline() {
        use flate2::write::GzEncoder;
        use flate2::Compression;
        use std::io::Write;

        let html = b"<html><head></head><body>hi</body></html>";
        let mut enc = GzEncoder::new(Vec::new(), Compression::default());
        enc.write_all(html).unwrap();
        let gz = enc.finish().unwrap();

        // Frame the gzip stream as two chunks.
        let mid = gz.len() / 2;
        let mut chunked = Vec::new();
        chunked.extend_from_slice(format!("{:x}\r\n", mid).as_bytes());
        chunked.extend_from_slice(&gz[..mid]);
        chunked.extend_from_slice(b"\r\n");
        chunked.extend_from_slice(format!("{:x}\r\n", gz.len() - mid).as_bytes());
        chunked.extend_from_slice(&gz[mid..]);
        chunked.extend_from_slice(b"\r\n0\r\n\r\n");

        let unframed = dechunk(&chunked).expect("dechunk");
        let plain = decompress(&unframed, "gzip").expect("gunzip");
        assert_eq!(plain, html);

        let injected = inject_css(&plain, ".ad{display:none}").expect("inject");
        assert!(String::from_utf8_lossy(&injected).contains("omnishield-cosmetic"));
    }

    #[test]
    fn gzip_roundtrip() {
        use flate2::write::GzEncoder;
        use flate2::Compression;
        use std::io::Write;

        let mut enc = GzEncoder::new(Vec::new(), Compression::default());
        enc.write_all(b"<html></html>").unwrap();
        let compressed = enc.finish().unwrap();

        assert_eq!(decompress(&compressed, "gzip").unwrap(), b"<html></html>");
        // Identity passthrough.
        assert_eq!(decompress(b"plain", "").unwrap(), b"plain");
    }
}

#[cfg(test)]
mod cache_tests {
    use super::*;

    #[test]
    fn engine_cache_round_trip_preserves_verdicts() {
        let mut original = ContentFilter::new();
        original.load(vec![
            "||ads.example.com^\n||tracker.test/pixel\n@@||ads.example.com/allowed^\n".to_string(),
        ]);

        let bytes = original.to_cache_bytes();
        let mut restored = ContentFilter::new();
        assert!(restored.load_cache_bytes(&bytes), "round trip must succeed");

        assert_eq!(original.rules(), restored.rules());
        for (url, src) in [
            ("https://ads.example.com/banner.png", "https://news.test/"),
            ("https://tracker.test/pixel", "https://news.test/"),
            ("https://safe.example.com/logo.png", "https://news.test/"),
        ] {
            assert_eq!(
                original.blocks(url, src, "image"),
                restored.blocks(url, src, "image"),
                "verdict diverged for {url}"
            );
        }
    }

    #[test]
    fn rejects_unusable_engine_cache() {
        let mut f = ContentFilter::new();
        assert!(!f.load_cache_bytes(&[]), "empty");
        assert!(!f.load_cache_bytes(&[0u8; 4]), "too short for the header");
        assert!(!f.load_cache_bytes(&[0u8; 64]), "header but garbage payload");
    }
}
