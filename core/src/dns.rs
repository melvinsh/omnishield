//! DNS query parsing and synthesized responses.
//!
//! We only need two things from a DNS message: the queried name (to test against the
//! blocklist) and enough structure to synthesize a refusal. Everything else is relayed
//! upstream untouched as opaque bytes, so there is no need for a full resolver library.

pub const TYPE_A: u16 = 1;
pub const TYPE_AAAA: u16 = 28;
pub const TYPE_HTTPS: u16 = 65;

const HEADER_LEN: usize = 12;
const FLAG_RESPONSE: u16 = 0x8000;
const FLAG_RECURSION_DESIRED: u16 = 0x0100;
const FLAG_RECURSION_AVAILABLE: u16 = 0x0080;
const FLAG_TRUNCATED: u16 = 0x0200;
const RCODE_NXDOMAIN: u16 = 3;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Query {
    pub id: u16,
    pub name: String,
    pub qtype: u16,
    /// Byte offset just past the question section — where a synthesized answer would start.
    pub question_end: usize,
}

/// Parses the first question of a standard query. Returns `None` for anything malformed,
/// truncated, or not a query, so callers relay it upstream rather than guess.
pub fn parse_query(buf: &[u8]) -> Option<Query> {
    let q = parse_question(buf)?;
    let flags = u16::from_be_bytes([buf[2], buf[3]]);
    if flags & FLAG_RESPONSE != 0 {
        return None; // already a response
    }
    Some(q)
}

/// The same question, read out of a *response*.
///
/// Needed because a cached answer has to be keyed by what was asked, and the only reliable
/// statement of that inside a reply is its echoed question section — a UDP session can in
/// principle carry more than one lookup, so keying off the session would be a guess.
pub fn parse_response_question(buf: &[u8]) -> Option<Query> {
    let q = parse_question(buf)?;
    let flags = u16::from_be_bytes([buf[2], buf[3]]);
    if flags & FLAG_RESPONSE == 0 {
        return None; // not a response
    }
    Some(q)
}

/// Question-section parse shared by both directions, with no opinion on the QR bit.
fn parse_question(buf: &[u8]) -> Option<Query> {
    if buf.len() < HEADER_LEN {
        return None;
    }
    let id = u16::from_be_bytes([buf[0], buf[1]]);
    let qdcount = u16::from_be_bytes([buf[4], buf[5]]);
    if qdcount == 0 {
        return None;
    }

    let (name, next) = read_name(buf, HEADER_LEN)?;
    if next + 4 > buf.len() {
        return None;
    }
    let qtype = u16::from_be_bytes([buf[next], buf[next + 1]]);

    Some(Query {
        id,
        name,
        qtype,
        question_end: next + 4,
    })
}

/// Smallest TTL across the answer section, or `None` if this reply must not be cached.
///
/// Deliberately conservative: only a successful reply carrying at least one answer record is
/// eligible. Negative and error replies are refused rather than cached against an SOA minimum,
/// because getting negative caching subtly wrong means a domain stays broken after the user
/// fixes whatever caused the failure — a far worse outcome than re-asking upstream.
pub fn response_min_ttl(buf: &[u8]) -> Option<u32> {
    if buf.len() < HEADER_LEN {
        return None;
    }
    let flags = u16::from_be_bytes([buf[2], buf[3]]);
    if flags & FLAG_RESPONSE == 0 || flags & 0x000f != 0 {
        return None; // a query, or a non-zero RCODE
    }
    if flags & FLAG_TRUNCATED != 0 {
        return None; // the client will retry over TCP; caching half an answer helps nobody
    }
    let qdcount = u16::from_be_bytes([buf[4], buf[5]]);
    let ancount = u16::from_be_bytes([buf[6], buf[7]]);
    if ancount == 0 {
        return None;
    }

    let mut pos = HEADER_LEN;
    for _ in 0..qdcount {
        let (_, next) = read_name(buf, pos)?;
        pos = next + 4; // QTYPE + QCLASS
        if pos > buf.len() {
            return None;
        }
    }

    let mut min: Option<u32> = None;
    for _ in 0..ancount {
        let (_, next) = read_name(buf, pos)?;
        // TYPE(2) CLASS(2) TTL(4) RDLENGTH(2)
        if next + 10 > buf.len() {
            return None;
        }
        let ttl = u32::from_be_bytes([buf[next + 4], buf[next + 5], buf[next + 6], buf[next + 7]]);
        let rdlen = u16::from_be_bytes([buf[next + 8], buf[next + 9]]) as usize;
        pos = next + 10 + rdlen;
        if pos > buf.len() {
            return None;
        }
        min = Some(min.map_or(ttl, |m: u32| m.min(ttl)));
    }
    min
}

/// Reads a (possibly compressed) domain name, returning it and the offset just past it.
///
/// Compression pointers are followed with a hard budget so a self-referential or cyclic
/// message cannot spin here — a hostile packet must not be able to hang the tunnel thread.
fn read_name(buf: &[u8], start: usize) -> Option<(String, usize)> {
    let mut name = String::new();
    let mut pos = start;
    let mut end_of_name: Option<usize> = None;
    let mut jumps = 0;

    loop {
        if pos >= buf.len() {
            return None;
        }
        let len = buf[pos] as usize;

        if len == 0 {
            let end = end_of_name.unwrap_or(pos + 1);
            return Some((name, end));
        }

        if len & 0xc0 == 0xc0 {
            if pos + 1 >= buf.len() {
                return None;
            }
            jumps += 1;
            if jumps > 16 {
                return None; // cyclic or absurdly chained compression
            }
            let ptr = ((len & 0x3f) << 8) | buf[pos + 1] as usize;
            if end_of_name.is_none() {
                end_of_name = Some(pos + 2);
            }
            if ptr >= buf.len() {
                return None;
            }
            pos = ptr;
            continue;
        }

        if len > 63 || pos + 1 + len > buf.len() {
            return None;
        }
        if !name.is_empty() {
            name.push('.');
        }
        for &b in &buf[pos + 1..pos + 1 + len] {
            name.push(b as char);
        }
        pos += 1 + len;

        if name.len() > 253 {
            return None;
        }
    }
}

/// Builds an NXDOMAIN reply for `query`.
///
/// NXDOMAIN is preferred over answering `0.0.0.0`: clients give up immediately instead of
/// opening a connection to a black-hole address and waiting for it to time out, which is both
/// faster for the app and cheaper for us (no dead socket to tear down).
pub fn nxdomain_response(query_buf: &[u8], query: &Query) -> Vec<u8> {
    let end = query.question_end.min(query_buf.len());
    let mut out = Vec::with_capacity(end);
    out.extend_from_slice(&query_buf[..end]);

    let req_flags = u16::from_be_bytes([out[2], out[3]]);
    let mut flags = FLAG_RESPONSE | FLAG_RECURSION_AVAILABLE | RCODE_NXDOMAIN;
    flags |= req_flags & FLAG_RECURSION_DESIRED;
    // Preserve OPCODE from the request.
    flags |= req_flags & 0x7800;
    out[2..4].copy_from_slice(&flags.to_be_bytes());

    out[4..6].copy_from_slice(&1u16.to_be_bytes()); // qdcount
    out[6..8].copy_from_slice(&0u16.to_be_bytes()); // ancount
    out[8..10].copy_from_slice(&0u16.to_be_bytes()); // nscount
    out[10..12].copy_from_slice(&0u16.to_be_bytes()); // arcount
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Query for `ads.example.com`, type A.
    fn query_bytes() -> Vec<u8> {
        let mut q = vec![
            0x12, 0x34, // id
            0x01, 0x00, // standard query, RD
            0x00, 0x01, // qdcount 1
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        ];
        for label in ["ads", "example", "com"] {
            q.push(label.len() as u8);
            q.extend_from_slice(label.as_bytes());
        }
        q.push(0);
        q.extend_from_slice(&TYPE_A.to_be_bytes());
        q.extend_from_slice(&1u16.to_be_bytes()); // IN
        q
    }

    #[test]
    fn parses_question() {
        let q = parse_query(&query_bytes()).expect("parses");
        assert_eq!(q.id, 0x1234);
        assert_eq!(q.name, "ads.example.com");
        assert_eq!(q.qtype, TYPE_A);
        assert_eq!(q.question_end, query_bytes().len());
    }

    #[test]
    fn builds_nxdomain() {
        let buf = query_bytes();
        let q = parse_query(&buf).unwrap();
        let r = nxdomain_response(&buf, &q);

        assert_eq!(&r[0..2], &buf[0..2], "transaction id must be echoed");
        let flags = u16::from_be_bytes([r[2], r[3]]);
        assert_ne!(flags & FLAG_RESPONSE, 0, "QR must be set");
        assert_eq!(flags & 0x000f, RCODE_NXDOMAIN);
        assert_ne!(flags & FLAG_RECURSION_DESIRED, 0, "RD echoed from query");
        assert_eq!(u16::from_be_bytes([r[6], r[7]]), 0, "no answers");
        // The question section is preserved verbatim.
        assert_eq!(&r[HEADER_LEN..], &buf[HEADER_LEN..]);
    }

    #[test]
    fn rejects_response_and_truncated() {
        let mut resp = query_bytes();
        resp[2] |= 0x80;
        assert!(parse_query(&resp).is_none());
        assert!(parse_query(&[0u8; 4]).is_none());
        assert!(parse_query(&[]).is_none());
    }

    #[test]
    fn rejects_cyclic_compression_pointer() {
        // A name at offset 12 that points back to itself must terminate, not spin.
        let mut buf = vec![0u8; 12];
        buf[4] = 0;
        buf[5] = 1;
        buf.push(0xc0);
        buf.push(12);
        assert!(parse_query(&buf).is_none());
    }

    #[test]
    fn rejects_oversized_label() {
        let mut buf = vec![0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0];
        buf.push(64); // label length > 63
        buf.extend_from_slice(&[b'a'; 64]);
        buf.push(0);
        assert!(parse_query(&buf).is_none());
    }
}
