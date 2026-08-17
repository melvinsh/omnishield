//! Domain blocklist matching.
//!
//! Deliberately not a trie or Aho-Corasick. A hostname has at most a handful of labels, so
//! matching "is this domain or any of its parents blocked" is a short walk of suffixes.
//! Aho-Corasick is the right tool for ABP *substring* rules, which live in the Phase 5 content
//! filter instead.
//!
//! The list-derived sets are held as one contiguous UTF-8 blob plus a sorted offset index
//! rather than as `HashSet<String>`. With ~430k domains loaded, a `HashSet<String>` costs a
//! separate heap allocation and 24 bytes of `String` header *per entry* before the text
//! itself; the blob pays the text plus four bytes of offset. Lookup becomes a binary search
//! instead of a hash probe, which is a few extra comparisons on a path that runs once per DNS
//! query — nowhere near the packet hot path.

use std::cmp::Ordering;
use std::collections::HashSet;

/// A sorted, deduplicated set of domains stored as one blob.
///
/// `offsets` has `len() + 1` entries; entry `i` occupies `blob[offsets[i]..offsets[i + 1]]`.
#[derive(Debug, Default)]
struct DomainSet {
    blob: Vec<u8>,
    offsets: Vec<u32>,
}

impl DomainSet {
    fn len(&self) -> usize {
        self.offsets.len().saturating_sub(1)
    }

    fn is_empty(&self) -> bool {
        self.len() == 0
    }

    fn get(&self, index: usize) -> &[u8] {
        let start = self.offsets[index] as usize;
        let end = self.offsets[index + 1] as usize;
        &self.blob[start..end]
    }

    fn contains(&self, needle: &str) -> bool {
        let needle = needle.as_bytes();
        let mut lo = 0usize;
        let mut hi = self.len();
        while lo < hi {
            let mid = lo + (hi - lo) / 2;
            match self.get(mid).cmp(needle) {
                Ordering::Less => lo = mid + 1,
                Ordering::Greater => hi = mid,
                Ordering::Equal => return true,
            }
        }
        false
    }

    /// Merges `additions` into the set and rebuilds the index.
    ///
    /// Existing entries are read straight out of the old blob rather than kept alongside it as
    /// `String`s — holding a staging copy would reintroduce exactly the per-entry overhead
    /// this structure exists to avoid.
    fn extend(&mut self, additions: Vec<String>) {
        if additions.is_empty() {
            return;
        }
        let mut all: Vec<&[u8]> = Vec::with_capacity(self.len() + additions.len());
        for i in 0..self.len() {
            all.push(self.get(i));
        }
        for s in &additions {
            all.push(s.as_bytes());
        }
        all.sort_unstable();
        all.dedup();

        let total: usize = all.iter().map(|s| s.len()).sum();
        let mut blob = Vec::with_capacity(total);
        let mut offsets = Vec::with_capacity(all.len() + 1);
        offsets.push(0u32);
        for s in all {
            blob.extend_from_slice(s);
            offsets.push(blob.len() as u32);
        }

        self.blob = blob;
        self.offsets = offsets;
    }

    fn clear(&mut self) {
        self.blob.clear();
        self.blob.shrink_to_fit();
        self.offsets.clear();
        self.offsets.shrink_to_fit();
    }

    /// Bytes of heap held by this set, for the memory reporting the UI shows.
    fn heap_bytes(&self) -> usize {
        self.blob.capacity() + self.offsets.capacity() * std::mem::size_of::<u32>()
    }

    /// Appends this set to `out` as `blob_len | offsets_len | blob | offsets`.
    fn write_to(&self, out: &mut Vec<u8>) {
        out.extend_from_slice(&(self.blob.len() as u64).to_le_bytes());
        out.extend_from_slice(&(self.offsets.len() as u64).to_le_bytes());
        out.extend_from_slice(&self.blob);
        for off in &self.offsets {
            out.extend_from_slice(&off.to_le_bytes());
        }
    }

    /// Reads a set written by [`write_to`], returning the number of bytes consumed.
    ///
    /// Every length is validated against the remaining input before it is used. A truncated or
    /// corrupt cache file must fail cleanly here so the caller can fall back to parsing the
    /// lists — never panic, and never produce a set whose offsets index outside the blob.
    fn read_from(buf: &[u8]) -> Option<(Self, usize)> {
        if buf.len() < 16 {
            return None;
        }
        let blob_len = u64::from_le_bytes(buf[0..8].try_into().ok()?) as usize;
        let off_len = u64::from_le_bytes(buf[8..16].try_into().ok()?) as usize;
        let need = 16usize
            .checked_add(blob_len)?
            .checked_add(off_len.checked_mul(4)?)?;
        if buf.len() < need {
            return None;
        }
        let blob = buf[16..16 + blob_len].to_vec();
        let mut offsets = Vec::with_capacity(off_len);
        let base = 16 + blob_len;
        for i in 0..off_len {
            let at = base + i * 4;
            offsets.push(u32::from_le_bytes(buf[at..at + 4].try_into().ok()?));
        }
        // Structural invariants the lookup path relies on: offsets ascend and the last one is
        // the blob length. Without this a malformed file would panic in `get`. An empty set is
        // legitimate — a filter with no exception rules has an empty `allowed` — and is
        // represented by no offsets at all rather than by a single zero.
        if offsets.is_empty() {
            if blob_len != 0 {
                return None;
            }
        } else {
            if *offsets.last()? as usize != blob_len {
                return None;
            }
            if offsets.windows(2).any(|w| w[0] > w[1]) {
                return None;
            }
        }
        Some((Self { blob, offsets }, need))
    }
}

#[derive(Debug, Default)]
pub struct DomainFilter {
    blocked: DomainSet,
    /// `@@` exceptions from the loaded lists.
    allowed: DomainSet,
    /// User overrides. Tiny compared with the lists, and mutated one entry at a time, so a
    /// plain `HashSet` is the right shape here.
    user_allow: HashSet<String>,
    user_block: HashSet<String>,
    /// Staging for the current `load_list` call; drained into [`DomainSet`] on completion.
    pending_blocked: Vec<String>,
    pending_allowed: Vec<String>,
}

/// Why a lookup returned the verdict it did — surfaced in the UI log.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Verdict {
    Allow,
    /// Carries the list entry that matched, which is not always the queried name — a query
    /// for `a.b.tracker.com` can be blocked by the rule `tracker.com`.
    Block(String),
}

impl DomainFilter {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn len(&self) -> usize {
        self.blocked.len()
    }

    pub fn is_empty(&self) -> bool {
        self.blocked.is_empty()
    }

    pub fn allow_len(&self) -> usize {
        self.allowed.len() + self.user_allow.len()
    }

    /// Approximate heap held by the domain sets, for reporting.
    pub fn heap_bytes(&self) -> usize {
        self.blocked.heap_bytes() + self.allowed.heap_bytes()
    }

    /// Ingests a blocklist in any of the formats we care about, returning how many rules were
    /// added. Recognised per line:
    ///
    /// - hosts files:            `0.0.0.0 ads.example.com` / `127.0.0.1 ads.example.com`
    /// - AdGuard/ABP DNS rules:  `||ads.example.com^`
    /// - exceptions:             `@@||good.example.com^`
    /// - bare domains:           `ads.example.com`
    ///
    /// Comments (`#`, `!`) and anything carrying ABP modifiers (`$`) or element-hiding syntax
    /// (`##`) are skipped — those are Phase 5 content rules, not DNS rules, and treating them
    /// as domains would produce nonsense entries.
    pub fn stage_list(&mut self, text: &str) -> usize {
        // Deliberately does *not* clear the staging buffers: successive calls accumulate, and
        // [`commit`] drains them. Clearing here (which is what this did when every call also
        // committed) silently discarded every list but the last.
        let before = self.pending_blocked.len();

        for raw in text.lines() {
            let line = raw.trim();
            if line.is_empty() || line.starts_with('#') || line.starts_with('!') {
                continue;
            }

            let (is_exception, rest) = match line.strip_prefix("@@") {
                Some(r) => (true, r),
                None => (false, line),
            };

            // Element-hiding and option-carrying rules are not DNS-blockable.
            if rest.contains("##") || rest.contains("#@#") || rest.contains('$') {
                continue;
            }

            let domain = if let Some(r) = rest.strip_prefix("||") {
                r.split(['^', '/']).next().unwrap_or("")
            } else if rest.starts_with("0.0.0.0") || rest.starts_with("127.0.0.1") {
                let mut parts = rest.split_whitespace();
                parts.next();
                match parts.next() {
                    Some(d) => d,
                    None => continue,
                }
            } else if rest.contains(' ') || rest.contains('\t') {
                // An unrecognised multi-column line; guessing would create junk rules.
                continue;
            } else {
                rest
            };

            let domain = normalize(domain);
            if !is_plausible_domain(&domain) {
                continue;
            }

            // `localhost` and friends appear in every hosts file and must never be sunk.
            if domain == "localhost" || domain == "localhost.localdomain" || domain == "local" {
                continue;
            }

            if is_exception {
                self.pending_allowed.push(domain);
            } else {
                self.pending_blocked.push(domain);
            }
        }

        self.pending_blocked.len() - before
    }

    /// Folds everything staged by [`stage_list`] into the searchable sets.
    ///
    /// Splitting this out of `stage_list` is what makes a multi-list load cheap. `extend`
    /// re-sorts and rebuilds the whole blob, and the old blob stays live while the new one is
    /// written — so calling it once per list meant four full rebuilds per tunnel start, each
    /// with a transient footprint several times the steady-state size. Now it happens once.
    pub fn commit(&mut self) -> usize {
        let before = self.blocked.len();
        let additions = std::mem::take(&mut self.pending_blocked);
        self.blocked.extend(additions);
        let exceptions = std::mem::take(&mut self.pending_allowed);
        self.allowed.extend(exceptions);
        self.blocked.len().saturating_sub(before)
    }

    /// Parses and commits in one step. Kept for tests and any single-list caller.
    pub fn load_list(&mut self, text: &str) -> usize {
        self.stage_list(text);
        self.commit()
    }

    /// Serialises the list-derived sets so a later start can skip parsing entirely.
    ///
    /// User overrides are deliberately *not* included: they live in Room, are pushed in
    /// separately on every start, and are tiny. Caching them here would create a second source
    /// of truth for the one thing the user directly controls.
    pub fn to_cache_bytes(&self) -> Vec<u8> {
        let mut out =
            Vec::with_capacity(self.blocked.heap_bytes() + self.allowed.heap_bytes() + 32);
        out.extend_from_slice(&(self.blocked.len() as u64).to_le_bytes());
        self.blocked.write_to(&mut out);
        self.allowed.write_to(&mut out);
        out
    }

    /// Restores sets written by [`to_cache_bytes`]. Returns false if the data is unusable, in
    /// which case the caller must fall back to parsing the lists.
    pub fn load_cache_bytes(&mut self, buf: &[u8]) -> bool {
        if buf.len() < 8 {
            return false;
        }
        let Some((blocked, used)) = DomainSet::read_from(&buf[8..]) else {
            return false;
        };
        let Some((allowed, _)) = DomainSet::read_from(&buf[8 + used..]) else {
            return false;
        };
        self.blocked = blocked;
        self.allowed = allowed;
        self.pending_blocked = Vec::new();
        self.pending_allowed = Vec::new();
        true
    }

    /// Replaces the user's per-domain overrides wholesale.
    ///
    /// Replace rather than merge so removing an override in the UI actually removes it —
    /// an additive API would make deletion impossible without a separate call.
    pub fn set_user_rules<I: IntoIterator<Item = (String, bool)>>(&mut self, rules: I) {
        self.user_allow.clear();
        self.user_block.clear();
        for (domain, allow) in rules {
            let domain = normalize(&domain);
            if domain.is_empty() {
                continue;
            }
            if allow {
                self.user_allow.insert(domain);
            } else {
                self.user_block.insert(domain);
            }
        }
    }

    pub fn user_rule_count(&self) -> usize {
        self.user_allow.len() + self.user_block.len()
    }

    pub fn allow(&mut self, domain: &str) {
        self.user_allow.insert(normalize(domain));
    }

    pub fn block(&mut self, domain: &str) {
        self.user_block.insert(normalize(domain));
    }

    pub fn clear(&mut self) {
        self.blocked.clear();
        self.allowed.clear();
        self.user_allow.clear();
        self.user_block.clear();
    }

    /// Walks `domain` and each of its parent suffixes. `ads.tracker.co.uk` tests
    /// `ads.tracker.co.uk`, `tracker.co.uk`, `co.uk`, `uk`.
    ///
    /// At each level the user's own overrides are consulted before the lists, so an explicit
    /// choice always beats a downloaded rule at the same specificity — and a more specific
    /// rule still beats a broader one, because the walk stops at the first match.
    pub fn lookup(&self, domain: &str) -> Verdict {
        let domain = normalize(domain);
        let bytes = domain.as_bytes();

        let mut start = 0usize;
        loop {
            let suffix = &domain[start..];
            if suffix.is_empty() {
                break;
            }
            if self.user_allow.contains(suffix) {
                return Verdict::Allow;
            }
            if self.user_block.contains(suffix) {
                return Verdict::Block(format!("user rule: {suffix}"));
            }
            if self.allowed.contains(suffix) {
                return Verdict::Allow;
            }
            if self.blocked.contains(suffix) {
                return Verdict::Block(suffix.to_string());
            }
            match bytes[start..].iter().position(|&b| b == b'.') {
                Some(idx) => start += idx + 1,
                None => break,
            }
        }
        Verdict::Allow
    }
}

/// Lowercases and drops the root label's trailing dot.
///
/// Deliberately does *not* strip a leading `www.`: the suffix walk in [`DomainFilter::lookup`]
/// already matches `www.example.com` against a rule for `example.com`, and stripping it here
/// would silently widen a rule written for `www.evil.com` into one covering all of
/// `evil.com`.
fn normalize(domain: &str) -> String {
    domain.trim().trim_end_matches('.').to_ascii_lowercase()
}

fn is_plausible_domain(d: &str) -> bool {
    !d.is_empty()
        && d.len() <= 253
        && d.contains('.')
        && d.bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'.' || b == b'-' || b == b'_')
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn blocks_exact_and_subdomains() {
        let mut f = DomainFilter::new();
        f.load_list("||doubleclick.net^");
        assert_eq!(
            f.lookup("doubleclick.net"),
            Verdict::Block("doubleclick.net".into())
        );
        assert_eq!(
            f.lookup("ad.g.doubleclick.net"),
            Verdict::Block("doubleclick.net".into())
        );
        assert_eq!(f.lookup("github.com"), Verdict::Allow);
    }

    #[test]
    fn does_not_block_sibling_suffix() {
        // A rule for `evil.com` must not catch `notevil.com`.
        let mut f = DomainFilter::new();
        f.load_list("||evil.com^");
        assert_eq!(f.lookup("notevil.com"), Verdict::Allow);
    }

    #[test]
    fn parses_hosts_format() {
        let mut f = DomainFilter::new();
        let n = f.load_list(
            "# comment\n0.0.0.0 ads.example.com\n127.0.0.1 tracker.example.org\n0.0.0.0 localhost\n",
        );
        assert_eq!(n, 2);
        assert!(matches!(f.lookup("ads.example.com"), Verdict::Block(_)));
        assert_eq!(
            f.lookup("localhost"),
            Verdict::Allow,
            "localhost must never be sunk"
        );
    }

    #[test]
    fn exceptions_win() {
        let mut f = DomainFilter::new();
        f.load_list("||example.com^\n@@||good.example.com^");
        assert!(matches!(f.lookup("bad.example.com"), Verdict::Block(_)));
        assert_eq!(f.lookup("good.example.com"), Verdict::Allow);
    }

    #[test]
    fn skips_cosmetic_and_option_rules() {
        let mut f = DomainFilter::new();
        let n = f.load_list("example.com##.ad-banner\n||x.com^$script\nplain.example.net");
        assert_eq!(n, 1, "only the bare domain is a DNS rule");
        assert!(matches!(f.lookup("plain.example.net"), Verdict::Block(_)));
        assert_eq!(f.lookup("x.com"), Verdict::Allow);
    }

    #[test]
    fn normalizes_case_and_trailing_dot() {
        let mut f = DomainFilter::new();
        f.load_list("||Ads.Example.COM^");
        assert!(matches!(f.lookup("ads.example.com."), Verdict::Block(_)));
        // Reached by the suffix walk, not by stripping the prefix.
        assert!(matches!(f.lookup("www.ads.example.com"), Verdict::Block(_)));
    }

    #[test]
    fn www_rule_does_not_widen_to_apex() {
        // A rule written for `www.evil.com` must not take down `evil.com` or its other hosts.
        let mut f = DomainFilter::new();
        f.load_list("||www.evil.com^");
        assert!(matches!(f.lookup("www.evil.com"), Verdict::Block(_)));
        assert_eq!(f.lookup("evil.com"), Verdict::Allow);
        assert_eq!(f.lookup("mail.evil.com"), Verdict::Allow);
    }

    // -- merging across successive loads ------------------------------------

    #[test]
    fn merges_successive_lists_without_losing_earlier_entries() {
        let mut f = DomainFilter::new();
        f.load_list("||a.com^\n||b.com^");
        f.load_list("||c.com^");
        for d in ["a.com", "b.com", "c.com"] {
            assert!(
                matches!(f.lookup(d), Verdict::Block(_)),
                "{d} should be blocked"
            );
        }
        assert_eq!(f.len(), 3);
    }

    #[test]
    fn deduplicates_across_lists() {
        let mut f = DomainFilter::new();
        f.load_list("||dup.com^");
        f.load_list("||dup.com^");
        assert_eq!(f.len(), 1);
    }

    // -- user overrides -----------------------------------------------------

    #[test]
    fn user_allow_beats_the_lists() {
        let mut f = DomainFilter::new();
        f.load_list("||tracker.com^");
        assert!(matches!(f.lookup("tracker.com"), Verdict::Block(_)));

        f.set_user_rules([("tracker.com".to_string(), true)]);
        assert_eq!(
            f.lookup("tracker.com"),
            Verdict::Allow,
            "an explicit user allow must override a downloaded rule"
        );
        assert_eq!(
            f.lookup("cdn.tracker.com"),
            Verdict::Allow,
            "and its subdomains"
        );
    }

    #[test]
    fn user_block_catches_what_the_lists_miss() {
        let mut f = DomainFilter::new();
        f.set_user_rules([("annoying.example^".replace('^', ""), false)]);
        assert!(matches!(f.lookup("annoying.example"), Verdict::Block(_)));
    }

    #[test]
    fn more_specific_user_rule_wins_over_broader_one() {
        let mut f = DomainFilter::new();
        f.set_user_rules([
            ("example.com".to_string(), false),
            ("safe.example.com".to_string(), true),
        ]);
        assert_eq!(f.lookup("safe.example.com"), Verdict::Allow);
        assert!(matches!(f.lookup("other.example.com"), Verdict::Block(_)));
    }

    #[test]
    fn setting_user_rules_replaces_rather_than_merges() {
        let mut f = DomainFilter::new();
        f.set_user_rules([("a.com".to_string(), true)]);
        f.set_user_rules([("b.com".to_string(), true)]);
        assert_eq!(
            f.user_rule_count(),
            1,
            "removing an override must actually remove it"
        );
    }

    // -- compact storage ----------------------------------------------------

    #[test]
    fn binary_search_agrees_with_a_reference_hashset() {
        // The whole point of the compact structure is that it is indistinguishable from the
        // HashSet it replaced. Generate a wide corpus and assert they agree everywhere.
        let mut reference = std::collections::HashSet::new();
        let mut list = String::new();
        for i in 0..5_000 {
            let d = format!("host{i}.block{}.example", i % 37);
            list.push_str("||");
            list.push_str(&d);
            list.push_str("^\n");
            reference.insert(d);
        }

        let mut f = DomainFilter::new();
        f.load_list(&list);
        assert_eq!(f.len(), reference.len());

        for d in &reference {
            assert!(
                matches!(f.lookup(d), Verdict::Block(_)),
                "{d} should be blocked"
            );
        }
        for i in 0..2_000 {
            let miss = format!("absent{i}.example.org");
            assert!(!reference.contains(&miss));
            assert_eq!(f.lookup(&miss), Verdict::Allow, "{miss} should not match");
        }
    }

    #[test]
    fn heap_is_proportional_to_text_not_entry_count() {
        let mut list = String::new();
        for i in 0..10_000 {
            list.push_str(&format!("||d{i}.example.com^\n"));
        }
        let mut f = DomainFilter::new();
        f.load_list(&list);

        // Text plus four bytes of offset per entry. A HashSet<String> would spend at least
        // ~48 bytes of overhead per entry on top of this.
        let text: usize = 10_000 * "d0000.example.com".len();
        assert!(
            f.heap_bytes() < text * 2 + 10_000 * 8,
            "compact set used {} bytes for ~{text} bytes of text",
            f.heap_bytes()
        );
    }

    #[test]
    fn clear_releases_everything() {
        let mut f = DomainFilter::new();
        f.load_list("||a.com^\n||b.com^");
        f.set_user_rules([("c.com".to_string(), true)]);
        f.clear();
        assert!(f.is_empty());
        assert_eq!(f.user_rule_count(), 0);
        assert_eq!(f.lookup("a.com"), Verdict::Allow);
    }
}

#[cfg(test)]
mod cache_tests {
    use super::*;

    fn sample() -> DomainFilter {
        let mut f = DomainFilter::new();
        f.load_list(
            "0.0.0.0 ads.example.com\n\
             0.0.0.0 tracker.test\n\
             ||doubleclick.net^\n\
             @@||allowed.example.com^\n",
        );
        f
    }

    #[test]
    fn cache_round_trip_preserves_every_verdict() {
        let original = sample();
        let bytes = original.to_cache_bytes();

        let mut restored = DomainFilter::new();
        assert!(restored.load_cache_bytes(&bytes), "round trip must succeed");

        for name in [
            "ads.example.com",
            "sub.ads.example.com",
            "tracker.test",
            "doubleclick.net",
            "www.doubleclick.net",
            "allowed.example.com",
            "example.com",
            "notads.example.com",
            "example.com.evil.test",
        ] {
            assert_eq!(
                original.lookup(name),
                restored.lookup(name),
                "verdict diverged for {name}"
            );
        }
        assert_eq!(original.len(), restored.len());
    }

    #[test]
    fn staging_many_lists_matches_loading_them_one_at_a_time() {
        let lists = [
            "0.0.0.0 a.example.com\n0.0.0.0 b.example.com\n",
            "||c.example.com^\n@@||b.example.com^\n",
            "0.0.0.0 d.example.com\n",
        ];

        let mut one_at_a_time = DomainFilter::new();
        for l in lists {
            one_at_a_time.load_list(l);
        }

        let mut staged = DomainFilter::new();
        for l in lists {
            staged.stage_list(l);
        }
        staged.commit();

        assert_eq!(one_at_a_time.len(), staged.len());
        for name in [
            "a.example.com",
            "b.example.com",
            "c.example.com",
            "d.example.com",
            "e.example.com",
        ] {
            assert_eq!(
                one_at_a_time.lookup(name),
                staged.lookup(name),
                "diverged for {name}"
            );
        }
    }

    #[test]
    fn rejects_truncated_cache() {
        let bytes = sample().to_cache_bytes();
        for cut in [0, 1, 8, 16, bytes.len() / 2, bytes.len() - 1] {
            let mut f = DomainFilter::new();
            assert!(
                !f.load_cache_bytes(&bytes[..cut]),
                "a {cut}-byte cache must be rejected, not trusted"
            );
        }
    }

    #[test]
    fn rejects_corrupt_offsets() {
        let mut bytes = sample().to_cache_bytes();
        // Smash the tail, where the offset table lives, into something non-monotonic.
        let n = bytes.len();
        bytes[n - 4..].copy_from_slice(&0u32.to_le_bytes());
        let mut f = DomainFilter::new();
        assert!(
            !f.load_cache_bytes(&bytes),
            "offsets that do not end at blob_len are corrupt"
        );
    }

    #[test]
    fn a_cache_from_an_empty_filter_is_still_valid() {
        let empty = DomainFilter::new();
        let bytes = empty.to_cache_bytes();
        let mut restored = DomainFilter::new();
        assert!(restored.load_cache_bytes(&bytes));
        assert_eq!(restored.len(), 0);
    }
}
