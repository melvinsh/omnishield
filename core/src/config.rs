//! Runtime configuration handed across the JNI boundary as JSON.
//!
//! Kotlin owns user-facing settings; the core just receives a snapshot at start and whenever
//! rules change. Keeping this as one JSON blob rather than a dozen JNI setters keeps the
//! bridge narrow.

use std::net::{IpAddr, Ipv4Addr};

use serde::Deserialize;

fn default_mtu() -> usize {
    1500
}
fn default_dns_sentinel() -> String {
    "10.0.0.53".to_string()
}
fn default_upstream() -> Vec<String> {
    vec!["1.1.1.1".to_string(), "1.0.0.1".to_string()]
}
fn default_true() -> bool {
    true
}
fn default_upstream_mode() -> String {
    "doh".to_string()
}
fn default_doh_url() -> String {
    "https://1.1.1.1/dns-query".to_string()
}

#[derive(Debug, Clone, Deserialize)]
pub struct Config {
    #[serde(default = "default_mtu")]
    pub mtu: usize,

    /// The address the tunnel advertises as the system DNS server. Queries to it are
    /// intercepted; it is never a real host.
    #[serde(default = "default_dns_sentinel")]
    pub dns_sentinel: String,

    /// Parsed forms of the two address fields, filled in by [`Config::derive`].
    ///
    /// Not deserialized — they are derived, and a JSON blob has no business setting them.
    #[serde(skip)]
    sentinel: Option<IpAddr>,
    #[serde(skip)]
    upstream: Option<IpAddr>,

    #[serde(default = "default_upstream")]
    pub upstream_dns: Vec<String>,

    /// `doh` or `udp`. Anything else is treated as `udp`.
    #[serde(default = "default_upstream_mode")]
    pub upstream_mode: String,

    /// DoH endpoint. Must be an IP literal — see `doh::parse` for why.
    #[serde(default = "default_doh_url")]
    pub doh_url: String,

    /// Drop UDP/443. Chrome prefers HTTP/3, which cannot be intercepted; without this the
    /// HTTPS filter silently sees almost no browser traffic.
    #[serde(default = "default_true")]
    pub block_quic: bool,

    /// Sink DNS-over-TLS (TCP/853) so the DNS layer cannot be trivially bypassed.
    #[serde(default = "default_true")]
    pub block_dot: bool,

    #[serde(default)]
    pub filtering_enabled: bool,

    /// Intercept TLS for these UIDs only. Everything else is bypassed to DNS-level
    /// filtering — the safe default, since most apps never trusted a user CA anyway.
    #[serde(default)]
    pub mitm_uids: Vec<i32>,

    #[serde(default)]
    pub mitm_enabled: bool,

    /// UIDs blocked outright on the current transport (Phase 6 firewall).
    #[serde(default)]
    pub blocked_uids: Vec<i32>,

    /// App-private directory where the root CA key and certificate are persisted.
    #[serde(default)]
    pub data_dir: String,
    /// Where prebuilt filter caches live.
    ///
    /// Separate from [`data_dir`], which is the CA's directory: moving the CA would invalidate
    /// a root certificate the user has already installed, so the cache gets its own location
    /// rather than being tucked inside it. Empty disables caching entirely.
    #[serde(default)]
    pub cache_dir: String,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            mtu: default_mtu(),
            dns_sentinel: default_dns_sentinel(),
            upstream_dns: default_upstream(),
            block_quic: true,
            block_dot: true,
            filtering_enabled: true,
            mitm_uids: Vec::new(),
            mitm_enabled: false,
            blocked_uids: Vec::new(),
            data_dir: String::new(),
            cache_dir: String::new(),
            upstream_mode: default_upstream_mode(),
            doh_url: default_doh_url(),
            sentinel: None,
            upstream: None,
        }
        .derive()
    }
}

impl Config {
    pub fn uses_doh(&self) -> bool {
        self.upstream_mode.eq_ignore_ascii_case("doh")
    }

    /// The sentinel address, already parsed.
    ///
    /// `dns_sentinel` arrives as a string and the triage path compared against it by calling
    /// `.parse()` on *every UDP datagram*. Parsing once, when the config is installed, moves
    /// that off the packet path entirely.
    pub fn sentinel_addr(&self) -> Option<IpAddr> {
        self.sentinel
    }

    /// First upstream resolver, already parsed. Re-parsed per DNS query before this existed.
    pub fn upstream_addr(&self) -> IpAddr {
        self.upstream
            .unwrap_or(IpAddr::V4(Ipv4Addr::new(1, 1, 1, 1)))
    }

    /// Fills the derived fields. Must be called on any config that reaches the packet path.
    fn derive(mut self) -> Self {
        self.sentinel = self.dns_sentinel.parse().ok();
        self.upstream = self.upstream_dns.first().and_then(|s| s.parse().ok());
        self
    }
}

impl Config {
    pub fn from_json(s: &str) -> Self {
        match serde_json::from_str::<Config>(s) {
            Ok(c) => c.derive(),
            Err(e) => {
                log::warn!("bad config json ({e}); falling back to defaults");
                Config::default()
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fills_defaults_for_missing_fields() {
        let c = Config::from_json(r#"{"filtering_enabled":true}"#);
        assert_eq!(c.mtu, 1500);
        assert_eq!(c.dns_sentinel, "10.0.0.53");
        assert!(c.block_quic);
        assert!(c.filtering_enabled);
    }

    #[test]
    fn malformed_json_falls_back_rather_than_panicking() {
        let c = Config::from_json("{not json");
        assert_eq!(c.mtu, 1500);
    }

    #[test]
    fn defaults_to_encrypted_dns() {
        // A privacy app that silently defaults to plaintext DNS undercuts its own premise.
        let c = Config::default();
        assert!(c.uses_doh());
        assert_eq!(Config::from_json("{}").upstream_mode, "doh");
    }

    #[test]
    fn honours_an_explicit_udp_mode() {
        let c = Config::from_json(r#"{"upstream_mode":"udp"}"#);
        assert!(!c.uses_doh());
    }

    #[test]
    fn parses_full_config() {
        let c = Config::from_json(
            r#"{"mtu":1400,"upstream_dns":["9.9.9.9"],"block_quic":false,"mitm_uids":[10192]}"#,
        );
        assert_eq!(c.mtu, 1400);
        assert_eq!(c.upstream_dns, vec!["9.9.9.9"]);
        assert!(!c.block_quic);
        assert_eq!(c.mitm_uids, vec![10192]);
    }
}
