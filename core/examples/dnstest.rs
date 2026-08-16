//! Offline probe for the Layer 1 DNS filter.
//!
//! Loads the real blocklists plus a newline-separated list of hostnames and reports which
//! would be sinkholed, so coverage gaps can be identified without a device.
//!
//! Usage: `cargo run --example dnstest -- <hosts.txt> <list.txt> [more.txt ...]`

use omnishield_core::filter::{DomainFilter, Verdict};

fn main() {
    let mut args = std::env::args().skip(1);
    let hosts_path = args.next().expect("usage: dnstest <hosts.txt> <list.txt> ...");
    let list_paths: Vec<String> = args.collect();
    assert!(!list_paths.is_empty(), "at least one blocklist is required");

    let mut filter = DomainFilter::new();
    for path in &list_paths {
        let text = std::fs::read_to_string(path).unwrap_or_else(|e| panic!("{path}: {e}"));
        let added = filter.load_list(&text);
        println!("{path}: +{added} rules");
    }
    println!("total rules: {}\n", filter.len());

    let hosts = std::fs::read_to_string(&hosts_path).expect("hosts file");
    let mut blocked = 0usize;
    let mut missed: Vec<&str> = Vec::new();

    for host in hosts.lines().map(str::trim).filter(|h| !h.is_empty()) {
        match filter.lookup(host) {
            Verdict::Block(_) => blocked += 1,
            Verdict::Allow => missed.push(host),
        }
    }

    let total = blocked + missed.len();
    println!("blocked {blocked}/{total}");
    if missed.is_empty() {
        println!("no gaps");
    } else {
        println!("\nnot covered by these lists:");
        for host in &missed {
            println!("  {host}");
        }
    }
}
