//! A small TTL-respecting cache of upstream DNS answers.
//!
//! Before this existed every lookup went upstream, every time: a page touching thirty hosts
//! cost ninety round trips (A, AAAA and HTTPS per host), ninety radio wakeups, and — because
//! attribution ran per query — ninety JNI calls. On a phone the radio wakeups alone dominate
//! everything else in the packet path.
//!
//! Three things make this safe to do inside a *filtering* resolver rather than a plain one:
//!
//!   * Only successful, non-truncated, answer-bearing replies are stored. Failures are never
//!     cached, so a transient upstream problem cannot pin a domain into a broken state.
//!   * Entries carry the answer's own minimum TTL, capped by [`MAX_TTL`]. They are served
//!     as stored rather than with TTLs counted down, so the cap is what bounds how stale a
//!     client's own cache can become.
//!   * Anything that could change a verdict — a user rule, a list reload, a config change —
//!     flushes the whole cache. A stale *allow* surviving a user's decision to block would be
//!     a correctness bug, not a performance trade-off, so flushing is deliberately blunt.
//!
//! Sinkholed names never reach here: they are answered locally from the filter and cost
//! nothing upstream already.

use std::num::NonZeroUsize;
use std::time::{Duration, Instant};

use lru::LruCache;

/// Upper bound on how long an entry is served, regardless of the TTL upstream advertised.
///
/// Answers are handed back byte-for-byte rather than with their TTL fields decremented, so a
/// client that caches for the full advertised TTL could hold an answer for this long past our
/// own expiry. Five minutes keeps that bounded without giving up most of the benefit.
const MAX_TTL: Duration = Duration::from_secs(300);

/// Entries are ~100–500 bytes each, so this is a few hundred kB at worst — small against the
/// tens of MB the filter lists occupy, and far more than a phone's working set of hostnames.
const CAPACITY: usize = 2048;

struct Entry {
    /// The full response as it arrived, including its question section.
    bytes: Vec<u8>,
    /// Offset just past the question, so a fresh question can be spliced over the stored one.
    question_end: usize,
    expires: Instant,
}

pub struct DnsCache {
    entries: LruCache<(String, u16), Entry>,
    hits: u64,
    misses: u64,
}

impl DnsCache {
    pub fn new() -> Self {
        Self {
            entries: LruCache::new(NonZeroUsize::new(CAPACITY).expect("capacity is nonzero")),
            hits: 0,
            misses: 0,
        }
    }

    /// Looks up an answer for `query`, rebuilt so it is a valid reply *to this query*.
    ///
    /// Two fields have to be rewritten. The transaction ID is obvious. The question section is
    /// less so: a resolver may randomise the case of the name it sends ("0x20 encoding") and
    /// then check that the reply echoes that exact casing, so replaying the stored question
    /// verbatim would look like a spoofed answer. Both names are the same length — the cache
    /// key is the lowercased name — so the splice cannot disturb any compression pointers in
    /// the answer section.
    pub fn get(
        &mut self,
        query_buf: &[u8],
        name: &str,
        qtype: u16,
        now: Instant,
    ) -> Option<Vec<u8>> {
        let key = (name.to_ascii_lowercase(), qtype);
        let entry = self.entries.get(&key)?;
        if entry.expires <= now {
            self.entries.pop(&key);
            self.misses += 1;
            return None;
        }

        let mut out = entry.bytes.clone();
        let q_end = entry.question_end;
        out[0] = query_buf[0];
        out[1] = query_buf[1];
        if query_buf.len() >= q_end && out.len() >= q_end {
            out[HEADER_LEN..q_end].copy_from_slice(&query_buf[HEADER_LEN..q_end]);
        }
        self.hits += 1;
        Some(out)
    }

    /// Stores a reply, if it is one of the kinds worth storing.
    ///
    /// Everything about eligibility is decided by [`crate::dns::response_min_ttl`]; a `None`
    /// from it is a silent, deliberate no-op.
    pub fn put(&mut self, response: &[u8], now: Instant) {
        let Some(ttl) = crate::dns::response_min_ttl(response) else {
            return;
        };
        if ttl == 0 {
            return; // explicitly "do not cache"
        }
        let Some(q) = crate::dns::parse_response_question(response) else {
            return;
        };
        let lifetime = Duration::from_secs(ttl as u64).min(MAX_TTL);
        self.entries.put(
            (q.name.to_ascii_lowercase(), q.qtype),
            Entry {
                bytes: response.to_vec(),
                question_end: q.question_end,
                expires: now + lifetime,
            },
        );
    }

    /// Drops everything. Called whenever a verdict could have changed.
    pub fn flush(&mut self) {
        self.entries.clear();
    }

    #[allow(dead_code)]
    pub fn len(&self) -> usize {
        self.entries.len()
    }

    #[allow(dead_code)]
    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    #[allow(dead_code)]
    pub fn stats(&self) -> (u64, u64) {
        (self.hits, self.misses)
    }

    /// Counts a lookup that never reached [`get`] because the name was not cached at all.
    pub fn record_miss(&mut self) {
        self.misses += 1;
    }
}

impl Default for DnsCache {
    fn default() -> Self {
        Self::new()
    }
}

const HEADER_LEN: usize = 12;

#[cfg(test)]
mod tests {
    use super::*;
    use crate::dns::TYPE_A;

    fn encode_name(out: &mut Vec<u8>, name: &str) {
        for label in name.split('.') {
            out.push(label.len() as u8);
            out.extend_from_slice(label.as_bytes());
        }
        out.push(0);
    }

    fn query(name: &str, id: u16) -> Vec<u8> {
        let mut q = Vec::new();
        q.extend_from_slice(&id.to_be_bytes());
        q.extend_from_slice(&0x0100u16.to_be_bytes()); // standard query, RD
        q.extend_from_slice(&1u16.to_be_bytes()); // qdcount
        q.extend_from_slice(&[0, 0, 0, 0, 0, 0]);
        encode_name(&mut q, name);
        q.extend_from_slice(&TYPE_A.to_be_bytes());
        q.extend_from_slice(&1u16.to_be_bytes());
        q
    }

    /// A reply with one A record at `ttl`.
    fn response(name: &str, id: u16, ttl: u32) -> Vec<u8> {
        let mut r = Vec::new();
        r.extend_from_slice(&id.to_be_bytes());
        r.extend_from_slice(&0x8180u16.to_be_bytes()); // response, RD, RA, NOERROR
        r.extend_from_slice(&1u16.to_be_bytes()); // qdcount
        r.extend_from_slice(&1u16.to_be_bytes()); // ancount
        r.extend_from_slice(&[0, 0, 0, 0]);
        encode_name(&mut r, name);
        r.extend_from_slice(&TYPE_A.to_be_bytes());
        r.extend_from_slice(&1u16.to_be_bytes());
        // answer
        encode_name(&mut r, name);
        r.extend_from_slice(&TYPE_A.to_be_bytes());
        r.extend_from_slice(&1u16.to_be_bytes());
        r.extend_from_slice(&ttl.to_be_bytes());
        r.extend_from_slice(&4u16.to_be_bytes());
        r.extend_from_slice(&[93, 184, 216, 34]);
        r
    }

    fn rcode(buf: &[u8], code: u16) -> Vec<u8> {
        let mut b = buf.to_vec();
        let flags = u16::from_be_bytes([b[2], b[3]]) | code;
        b[2..4].copy_from_slice(&flags.to_be_bytes());
        b
    }

    #[test]
    fn stores_and_returns_an_answer() {
        let mut c = DnsCache::new();
        let now = Instant::now();
        c.put(&response("example.com", 1, 60), now);
        let got = c.get(&query("example.com", 0xBEEF), "example.com", TYPE_A, now);
        assert!(got.is_some(), "a fresh entry must be served");
    }

    #[test]
    fn rewrites_the_transaction_id_to_match_the_asker() {
        let mut c = DnsCache::new();
        let now = Instant::now();
        c.put(&response("example.com", 0x1111, 60), now);
        let got = c
            .get(&query("example.com", 0xBEEF), "example.com", TYPE_A, now)
            .expect("hit");
        assert_eq!(
            u16::from_be_bytes([got[0], got[1]]),
            0xBEEF,
            "serving the stored id would look like an unsolicited reply"
        );
    }

    #[test]
    fn echoes_the_asker_s_own_question_casing() {
        // 0x20 encoding: the resolver randomises case and verifies the echo.
        let mut c = DnsCache::new();
        let now = Instant::now();
        c.put(&response("example.com", 1, 60), now);
        let q = query("ExAmPlE.CoM", 7);
        let got = c.get(&q, "example.com", TYPE_A, now).expect("hit");
        assert_eq!(
            &got[12..q.len()],
            &q[12..],
            "the question must come back exactly as it was sent"
        );
    }

    #[test]
    fn expires_after_the_ttl() {
        let mut c = DnsCache::new();
        let now = Instant::now();
        c.put(&response("example.com", 1, 5), now);
        let later = now + Duration::from_secs(6);
        assert!(c
            .get(&query("example.com", 2), "example.com", TYPE_A, later)
            .is_none());
    }

    #[test]
    fn caps_a_absurdly_long_ttl() {
        let mut c = DnsCache::new();
        let now = Instant::now();
        c.put(&response("example.com", 1, 86_400), now);
        let past_cap = now + MAX_TTL + Duration::from_secs(1);
        assert!(
            c.get(&query("example.com", 2), "example.com", TYPE_A, past_cap)
                .is_none(),
            "MAX_TTL must bound a long upstream TTL"
        );
    }

    #[test]
    fn does_not_cache_zero_ttl() {
        let mut c = DnsCache::new();
        let now = Instant::now();
        c.put(&response("example.com", 1, 0), now);
        assert_eq!(c.len(), 0);
    }

    #[test]
    fn does_not_cache_failures() {
        let mut c = DnsCache::new();
        let now = Instant::now();
        c.put(&rcode(&response("example.com", 1, 60), 3), now); // NXDOMAIN
        c.put(&rcode(&response("example.com", 1, 60), 2), now); // SERVFAIL
        assert_eq!(c.len(), 0, "a failure must never pin a domain");
    }

    #[test]
    fn does_not_cache_a_query() {
        let mut c = DnsCache::new();
        c.put(&query("example.com", 1), Instant::now());
        assert_eq!(c.len(), 0);
    }

    #[test]
    fn qtype_is_part_of_the_key() {
        let mut c = DnsCache::new();
        let now = Instant::now();
        c.put(&response("example.com", 1, 60), now);
        assert!(
            c.get(
                &query("example.com", 2),
                "example.com",
                crate::dns::TYPE_AAAA,
                now
            )
            .is_none(),
            "an A answer must not satisfy an AAAA question"
        );
    }

    #[test]
    fn flush_drops_everything() {
        let mut c = DnsCache::new();
        let now = Instant::now();
        c.put(&response("a.com", 1, 60), now);
        c.put(&response("b.com", 1, 60), now);
        assert_eq!(c.len(), 2);
        c.flush();
        assert_eq!(c.len(), 0, "a verdict change must not leave stale allows");
    }

    #[test]
    fn is_bounded_by_capacity() {
        let mut c = DnsCache::new();
        let now = Instant::now();
        for i in 0..(CAPACITY + 500) {
            c.put(&response(&format!("host{i}.example.com"), 1, 60), now);
        }
        assert_eq!(c.len(), CAPACITY, "must not grow without bound");
    }

    #[test]
    fn lookup_is_case_insensitive() {
        let mut c = DnsCache::new();
        let now = Instant::now();
        c.put(&response("Example.COM", 1, 60), now);
        assert!(c
            .get(&query("example.com", 2), "example.com", TYPE_A, now)
            .is_some());
    }
}
