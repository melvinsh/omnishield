//! Offline probe for the Layer 3 content filter.
//!
//! Loads real ABP lists from disk and reports what the engine decides for a set of URLs, so a
//! filtering miss can be attributed to either the rules or our invocation of them without
//! going near a device.
//!
//! Usage: `cargo run --example ruletest -- <list.txt> [more.txt ...]`

use omnishield_core::content::ContentFilter;

fn main() {
    let paths: Vec<String> = std::env::args().skip(1).collect();
    if paths.is_empty() {
        eprintln!("usage: ruletest <list.txt> [more.txt ...]");
        std::process::exit(2);
    }

    let lists: Vec<String> = paths
        .iter()
        .map(|p| std::fs::read_to_string(p).unwrap_or_else(|e| panic!("{p}: {e}")))
        .collect();

    let list_count = lists.len();
    let mut filter = ContentFilter::new();
    filter.load(lists);
    println!("loaded {} rule lines from {} list(s)\n", filter.rules(), list_count);

    // (url, source_url, request_type)
    let probes = [
        // The two the adblock.turtlecute.org test reported as "not blocked".
        (
            "https://adblock.turtlecute.org/js/widget/ads.js",
            "https://adblock.turtlecute.org/",
            "script",
        ),
        (
            "https://adblock.turtlecute.org/js/pagead.js",
            "https://adblock.turtlecute.org/",
            "script",
        ),
        // Same URLs presented as third-party, to isolate first-party classification.
        (
            "https://adblock.turtlecute.org/js/widget/ads.js",
            "https://example.org/",
            "script",
        ),
        // Controls that must block, proving the engine really is loaded.
        (
            "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js",
            "https://news.example.org/",
            "script",
        ),
        (
            "https://www.google-analytics.com/analytics.js",
            "https://news.example.org/",
            "script",
        ),
        // A control that must NOT block.
        (
            "https://cdn.example.org/app.js",
            "https://news.example.org/",
            "script",
        ),
    ];

    for (url, source, kind) in probes {
        let blocked = filter.blocks(url, source, kind);
        println!("{:<7} {url}", if blocked { "BLOCK" } else { "allow" });
    }

    println!("\ncosmetic selectors offered per domain:");
    for domain in [
        "https://adblock.turtlecute.org/",
        "https://www.theguardian.com/",
        "https://news.ycombinator.com/",
    ] {
        match filter.cosmetic_css(domain) {
            Some(css) => println!("  {domain} -> {} bytes", css.len()),
            None => println!("  {domain} -> none"),
        }
    }

    // The bait classes/ids used by adblock.turtlecute.org's cosmetic test. These are
    // *generic* cosmetic rules (`##.adsbox`), which adblock-rust deliberately withholds from
    // url_cosmetic_resources — they are far too numerous to ship per page, so the caller is
    // expected to look them up against the class/id attributes actually present in the DOM.
    // The exact classes adblock.turtlecute.org's cosmetic test applies to its bait element.
    let bait = br#"<html><head></head><body>
        <div id="ad_ctd" class="textads banner-ads banner_ads ad-unit afs_ads ad-zone ad-space adsbox"></div>
        </body></html>"#;
    let css = filter
        .cosmetic_css_for_document("https://adblock.turtlecute.org/", bait)
        .unwrap_or_default();
    println!("\ndocument-aware CSS: {} bytes", css.len());
    println!("can we hide the cosmetic test's bait element?");
    let mut any = false;
    for sel in [
        ".textads", ".banner-ads", ".banner_ads", ".ad-unit", ".afs_ads", ".ad-zone",
        ".ad-space", ".adsbox", "#ad_ctd",
    ] {
        let hit = css.contains(sel);
        any |= hit;
        println!("  {:<13} {hit}", sel);
    }
    println!("  => element would be hidden: {any}");
}
