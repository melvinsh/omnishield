# Performance

A blocker sits in the path of every packet on the device and runs for as long as the phone is on,
so its cost has to be close to nothing when nothing is happening.

All figures measured on an API 34 emulator (`omnishield-34`) with the full rule set loaded, using
`/proc/<pid>/stat` deltas over a 300 s window and `dumpsys meminfo`. Battery is not measurable on
QEMU, so CPU time stands in for it, and it is also the quantity that predicts battery.

| | |
|---|---|
| Idle CPU, screen off | 0.4 s/hour |
| Idle CPU, screen on | ~3 s/hour |
| Native heap, startup | 32.0 MB |
| Native heap, steady | 31.8 MB |
| TOTAL PSS, startup | 132.5 MB |
| TOTAL PSS, steady | 131.2 MB |
| Tunnel start to filters ready | 45 ms warm, 359 ms cold |
| Release APK | 8.7 MB per ABI, 14.9 MB universal |

Screen-on idle is the least stable of these: repeat runs land between 2.6 and 3.1 s/hour, so treat
it as roughly 3 rather than a precise figure. The screen-off row reproduces at 0.4 exactly.

## Where the cost goes, and does not

The tunnel loop sleeps. `runtime.rs` trusts smoltcp's `poll_delay` rather than polling on a
timer, with a 30 s backstop. That makes every off-thread event responsible for announcing itself,
so `core/src/wake.rs` provides a self-pipe written by `Runtime::stop`, by the JNI config setters,
and by the DoH worker, whose answers arrive on a channel the loop only drains while awake. A
producer that changes state without waking the loop looks like a hang, not like slowness, and the
30 s ceiling is the only reason it self-heals.

The event drain adapts. `PollSchedule` starts at 500 ms, doubles while the core reports
nothing, and caps at 2 s with the UI in front or 30 s without. Anything arriving snaps it back to
the floor. A drain returning 75% or more of the core's 2000-entry ring polls *below* the floor,
because an overflow would silently discard log rows.

Filters are built once and cached. `nativeLoadFilters` stages text; `nativeCommitFilters`
builds the index and writes `filters.bin` plus a serialized `adblock` engine under `cache_dir`,
keyed by a string derived from the name, size and mtime of every list file. Validating that key
costs a few `stat` calls rather than re-reading 13 MB, so a warm start never opens the lists at
all. That cache is where the 45 ms figure above comes from; a cold start pays 359 ms to parse.

DNS answers are cached with their own TTL (`dns_cache.rs`), flushed on any rule or config
change, so a page touching 30 hosts costs 30 upstream round trips rather than 90, and 30 radio
wakeups rather than 90.

Smaller things on hot paths. Packet buffers come from a pool rather than being allocated and
zeroed per packet. `POLLIN` is not requested for a connection whose `from_remote` is already
backed up. smoltcp ring buffers are 16 KiB rather than 64 KiB, since they only span the
app-to-tunnel hop where the round trip is microseconds and the bandwidth-delay product is tiny;
that bounds the worst case at `MAX_CONNECTIONS` to 16 MiB. Non-HTML responses through the
interception layer, meaning every image, script and font, are relayed without being copied.

The domain set is a blob, not a `HashSet`. `filter.rs` holds roughly 430k list domains as one
UTF-8 buffer plus a sorted offset index, binary-searched. A `HashSet<String>` of the same data
costs 61.6 MB of native heap against 43.7 MB for the blob, and the residual is dominated by the
ABP content engine rather than by domains.

## Deliberate choices that look like waste

Each of these reads as an obvious optimisation left undone. None of them are, and the reasons are
here so nobody spends a weekend rediscovering them.

The domain blob is read, not `mmap`ed. It is dirty anonymous heap at steady state, about
10 MB, where mapping would make it evictable file-backed memory. Doing that needs `DomainSet::blob`
to stop being a `Vec<u8>`, which reaches further into the filter than the saving justifies so far.

HTML is parsed twice per rewritten page. `inject_css` writes into `<head>`, which a streaming
rewriter emits long before it has seen the class attributes further down the document. One pass
cannot both know the classes and still have `<head>` available to write into.

adblock's `single-thread` feature cannot be enabled. It swaps `Arc` for `Rc` inside the
engine, which makes `Engine` `!Send`, and `Shared` is held in an `Arc` shared with the tunnel
thread.

Sockets are allocated at SYN, before the firewall check. Allocating afterwards would mean
dropping the SYN for a blocked app, and a dropped SYN is a connect timeout where the current
behaviour is an immediate reset. That trades 16 KiB for a user-visible hang.

DNS attribution costs one JNI call per query. It is cached per UDP session, but every app on
the device shares one source address across the TUN, so the only discriminator is the ephemeral
port, and a resolver that opens a fresh socket per lookup gets no reuse.

`SettingsViewModel.settingsOrNull` is the one eager collector. It exists so the first value is
ready before the first composition. Making it lazy trades a visible flash on launch for no
background saving, since the activity owns it either way.

## Constraints for anything touching this

1. `MorphShape` in `DashboardScreen.kt` stays a `data class`. As a plain class, every
   recomposition defeats `Modifier.clip`'s outline cache.
2. No eager collectors beyond the one named above.
3. No binder calls in composable bodies. `isIgnoringBatteryOptimizations` is read on lifecycle
   resume rather than per recomposition.
4. `TunnelRepository.setUiVisible` drives the drain cadence, so the activity's `onStart`/`onStop`
   plumbing has to stay wired.
5. The log screen reads Room. A convenience field on `TunnelRepository` mirroring recent log rows
   would be rewritten many times a second and read by nothing.

## Filtering, measured

With every list loaded: **431,819 DNS rules** from the bundled set plus StevenBlack, AdGuard DNS
and OISD, and **134,875 ABP content rules** from EasyList and EasyPrivacy.

Against [adblock.turtlecute.org](https://adblock.turtlecute.org), a third-party test page:
**128 of 132 blocked**. The four it does not catch are understood and deliberately left alone.

1. **The dynamic cosmetic test:** its bait element is created by JavaScript with classes that
   appear only inside the script, never in the HTML being rewritten. Generic cosmetic rules are
   looked up against the classes present in the served document, so a class that does not exist at
   rewrite time cannot be covered. Browser extensions solve this with an in-page MutationObserver;
   an interception proxy has no DOM to observe.
2. **`ads.js` and `pagead.js`** are absent from EasyList, EasyPrivacy, uBlock Origin's
   `filters.txt` and AdGuard Base. A generic `/ads.js` rule breaks too many legitimate sites, so
   the lists deliberately do not ship one, and adding a custom one would be gaming the benchmark.
3. **`browser.sentry-cdn.com`** is a JS SDK CDN that mainstream lists intentionally leave alone.

The offline probes reproduce this without a device: 60 of 60 known tracker hostnames blocked, 0 of
14 legitimate ones. See [Development](development.md#offline-probes).

Throughput has no reliable number yet. A direct 1 MB download on the emulator truncates at
exactly 141,904 bytes on every attempt, while the same download through the tunnel returns the
full 1,048,994 bytes in 1.27 to 1.49 s. That is a QEMU NAT artifact rather than a tunnel result,
so there is no baseline to compare against. It needs measuring on physical hardware.
