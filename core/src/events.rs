//! Event log and counters surfaced to the UI.
//!
//! Events are pushed here by the tunnel thread and *pulled* by Kotlin on a timer via
//! `nativeDrainEvents`. That direction matters: native-to-JVM callbacks would require
//! attaching the tunnel thread to the JVM on every event, which is exactly the per-packet
//! JNI cost the Rust core exists to avoid.

use std::collections::VecDeque;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{SystemTime, UNIX_EPOCH};

use serde::Serialize;

/// Monotonic event counter.
///
/// The UI keys its list on this. A timestamp-plus-name key is *not* unique — a single
/// hostname routinely produces simultaneous A and AAAA lookups in the same millisecond from
/// the same app, and a duplicate key makes Compose's LazyColumn throw and take the process
/// down with it.
static SEQ: AtomicU64 = AtomicU64::new(0);

#[derive(Debug, Clone, Serialize)]
pub struct Event {
    /// Process-unique, monotonically increasing. Used as the UI list key.
    pub seq: u64,
    /// Milliseconds since the Unix epoch.
    pub ts: u64,
    /// `dns`, `tcp`, `http`, or `tls`. Always one of four literals, so it is borrowed rather
    /// than allocated — `kind.to_string()` was a heap allocation per event for a constant.
    pub kind: &'static str,
    /// Domain for DNS/TLS/HTTP, or `ip:port` for a bare TCP connection.
    pub name: String,
    pub uid: i32,
    /// Package name. Shared rather than copied: the same handful of apps generate every event,
    /// and this fires on every allowed DNS query, not just on blocks.
    pub app: Arc<str>,
    pub blocked: bool,
    /// The rule or reason behind the verdict; empty when allowed.
    pub rule: String,
}

impl Event {
    /// Builds an event, stamping it with the next sequence number and the current time.
    /// Always prefer this over a struct literal so `seq` cannot be forgotten.
    pub fn new(
        kind: &'static str,
        name: impl Into<String>,
        uid: i32,
        app: Arc<str>,
        blocked: bool,
        rule: impl Into<String>,
    ) -> Self {
        Self {
            seq: SEQ.fetch_add(1, Ordering::Relaxed),
            ts: now_millis(),
            kind,
            name: name.into(),
            uid,
            app,
            blocked,
            rule: rule.into(),
        }
    }
}

#[derive(Debug, Default, Serialize)]
pub struct StatsSnapshot {
    pub dns_total: u64,
    pub dns_blocked: u64,
    pub conns_total: u64,
    pub conns_blocked: u64,
    pub bytes_saved: u64,
    pub filter_rules: u64,
    /// Heap held by the compact domain sets. Reported so memory claims can be measured
    /// rather than asserted.
    pub filter_bytes: u64,
    /// Compiled ABP rules currently loaded. Reported here rather than returned from the load
    /// call, because a cache hit never sees the rule text at all.
    pub content_rules: u64,
    /// DNS answers served from cache. Reported for the same reason: a cache whose hit rate is
    /// never measured is an assumption, not an optimisation.
    pub dns_cached: u64,
    /// True when DoH was requested but the core had to fall back to plaintext UDP.
    pub doh_degraded: bool,
}

#[derive(Debug, Default)]
pub struct Stats {
    pub dns_total: AtomicU64,
    pub dns_blocked: AtomicU64,
    pub conns_total: AtomicU64,
    pub conns_blocked: AtomicU64,
    pub bytes_saved: AtomicU64,
    pub filter_rules: AtomicU64,
}

impl Stats {
    pub fn snapshot(&self) -> StatsSnapshot {
        StatsSnapshot {
            dns_total: self.dns_total.load(Ordering::Relaxed),
            dns_blocked: self.dns_blocked.load(Ordering::Relaxed),
            conns_total: self.conns_total.load(Ordering::Relaxed),
            conns_blocked: self.conns_blocked.load(Ordering::Relaxed),
            bytes_saved: self.bytes_saved.load(Ordering::Relaxed),
            filter_rules: self.filter_rules.load(Ordering::Relaxed),
            // Filled in by the JNI layer, which can reach the filter, the cache and the DoH
            // flag — none of which live behind the plain atomics this struct owns.
            filter_bytes: 0,
            content_rules: 0,
            dns_cached: 0,
            doh_degraded: false,
        }
    }
}

/// Bounded ring of recent events. The log is a firehose on a busy device, so it drops the
/// oldest entries rather than growing without limit; the UI only ever shows a recent window.
pub struct EventLog {
    inner: Mutex<VecDeque<Event>>,
    capacity: usize,
}

impl EventLog {
    pub fn new(capacity: usize) -> Self {
        Self {
            inner: Mutex::new(VecDeque::with_capacity(capacity)),
            capacity,
        }
    }

    pub fn push(&self, event: Event) {
        let mut q = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        if q.len() == self.capacity {
            q.pop_front();
        }
        q.push_back(event);
    }

    /// Removes and returns everything buffered. Called from the JVM polling thread.
    pub fn drain(&self) -> Vec<Event> {
        let mut q = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        q.drain(..).collect()
    }
}

pub fn now_millis() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ev(name: &str) -> Event {
        Event::new("dns", name, -1, Arc::from(""), false, "")
    }

    #[test]
    fn sequence_numbers_are_unique_for_identical_events() {
        // Two A/AAAA lookups for the same host in the same millisecond must not collide —
        // a duplicate key crashes the UI list.
        let app: Arc<str> = Arc::from("chrome");
        let a = Event::new("dns", "example.com", 10146, Arc::clone(&app), false, "");
        let b = Event::new("dns", "example.com", 10146, app, false, "");
        assert_ne!(a.seq, b.seq);
    }

    #[test]
    fn drops_oldest_when_full() {
        let log = EventLog::new(2);
        log.push(ev("a"));
        log.push(ev("b"));
        log.push(ev("c"));
        let drained = log.drain();
        assert_eq!(drained.len(), 2);
        assert_eq!(drained[0].name, "b", "oldest entry evicted");
        assert_eq!(drained[1].name, "c");
    }

    #[test]
    fn drain_empties() {
        let log = EventLog::new(4);
        log.push(ev("a"));
        assert_eq!(log.drain().len(), 1);
        assert!(log.drain().is_empty());
    }
}
