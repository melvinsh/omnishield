# OmniShield Android

A non-root, system-wide ad and tracker blocker: a local `VpnService` loopback tunnel with DNS
sinkholing, a userspace TCP/IP stack, HTTPS interception, ABP content filtering, and a per-app
firewall.

Kotlin/Compose UI over a Rust core (`core/`) that owns the entire packet path. No packet ever
crosses the JNI boundary.

**Status:** All seven phases implemented and verified on an API 34 emulator. See
[Verification](#verification) for what was actually observed, and
[Known limitations](#known-limitations) for what does not work.

---

## Architecture

```
 apps ── tun0 ──► [ Rust core, one thread ]
                    │
                    ├─ packet.rs   IP/TCP/UDP triage
                    ├─ tun.rs      smoltcp Device over the TUN fd
                    ├─ runtime.rs  poll() loop, connection tables, firewall
                    │
                    ├─ L1  dns.rs + filter.rs      DNS sinkholing        (all traffic)
                    ├─ L2  ca.rs + mitm.rs         TLS termination       (opt-in per UID)
                    └─ L3  content.rs              ABP rules + cosmetic  (decrypted only)
```

The hard problem in a transparent tunnel is that smoltcp sockets bind to a *specific* endpoint
while we must accept connections to arbitrary destinations. Two mechanisms solve it:

1. `Interface::set_any_ip(true)` — the interface accepts packets not addressed to it.
2. Every packet is peeked before smoltcp sees it. On a SYN to a new 4-tuple, a socket is
   created already listening on that exact destination, *then* the frame is handed over.

---

## Prerequisites

Installed from scratch on macOS/Apple Silicon. See [Version constraints](#version-constraints)
before changing any of these.

```bash
brew install openjdk@17      # formula, not the temurin cask — the cask needs sudo
brew install --cask android-commandlinetools
brew install rustup gradle

rustup target add aarch64-linux-android
cargo install cargo-ndk

sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-37.0" "platforms;android-34" \
           "build-tools;37.0.0" "ndk;27.3.13750724" "emulator" \
           "system-images;android-34;google_apis;arm64-v8a" \
           "system-images;android-33;google_apis;arm64-v8a"
```

`openjdk@17` is keg-only and rustup's shims are not on the default PATH:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:/opt/homebrew/opt/rustup/bin:$HOME/.cargo/bin:$PATH"
```

Gradle resolves `cargo` itself via `rustToolDirs` in `app/build.gradle.kts`, so builds work
even without those PATH entries.

---

## Build and run

```bash
./gradlew installDebug        # runs cargoNdkBuild automatically
cd core && cargo test         # 46 host-native tests, no emulator needed
```

The Rust core must be built **from `core/`** — cargo-ndk runs `cargo metadata` in the working
directory, so `--manifest-path` alone is not enough:

```bash
cd core && cargo ndk -t arm64-v8a -P 29 -o ../app/src/main/jniLibs build --release
```

---

## Emulators

```bash
emulator -avd omnishield-34 -no-snapshot -no-audio -no-boot-anim   # default target
emulator -avd omnishield-33 -no-snapshot -writable-system          # Phase 4 CA testing
```

**Why two.** Android 14 moved the system trust store to `/apex/com.android.conscrypt/cacerts`,
which is immutable and cannot be remounted even as root. On API 33, `-writable-system` plus a
push to `/system/etc/security/cacerts` still works, which is the only practical way to test
HTTPS interception against *all* apps rather than only Chrome.

Both images are `google_apis`, deliberately **not** `google_apis_playstore` — Play Store
images block `adb root` and `-writable-system`.

### Testing recipes

```bash
# Skip the VPN consent dialog
adb shell appops set io.omnishield ACTIVATE_VPN allow

# Install the generated CA into the user trust store (Chrome trusts these)
adb root
adb shell cat /data/data/io.omnishield/files/ca/omnishield-ca.pem > ca.pem
HASH=$(openssl x509 -inform PEM -subject_hash_old -noout -in ca.pem)
adb push ca.pem /data/misc/user/0/cacerts-added/$HASH.0
adb shell chmod 644 /data/misc/user/0/cacerts-added/$HASH.0

# Foreground service type check
adb shell dumpsys activity services io.omnishield | grep -E "isForeground|types"
# expect: isForeground=true ... types=40000000   (FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
```

There is no `nslookup` or `curl` on these images. Use `ping` for DNS resolution and `nc` for
TCP:

```bash
adb shell "printf 'GET / HTTP/1.0\r\nHost: example.com\r\nConnection: close\r\n\r\n' \
  | nc -w 6 example.com 80 | head -1"
```

---

## Verification

Everything below was observed on the API 34 emulator, not inferred.

| Phase | Gate | Result |
|---|---|---|
| 0 | Rust `.so` builds, packaged, loads | `omnishield-core 0.1.0 initialised` in logcat |
| 1 | Foreground service survives Doze | `types=40000000`; alive through `deviceidle force-idle` |
| 2 | Transparent TCP through smoltcp | `HTTP/1.1 200 OK` from example.com over the tunnel |
| 3 | DNS allow/block | `doubleclick.net` → NXDOMAIN; `github.com` resolves |
| 4 | TLS interception | Chrome: *"Chrome verified that OmniShield Root CA issued this website's certificate"* |
| 5 | Content filtering | theguardian.com rewritten, `rewritten=true`, 1,256,400 bytes emitted |
| 6 | Per-app firewall | Blocking `com.android.shell` dropped its TCP while DNS still resolved; counter incremented; unblocking restored it |
| 7 | UI | Dashboard, live log (181 entries), per-app toggles, HTTPS controls |

Filter volume in a live run: **431,819 DNS rules** (bundled + StevenBlack + AdGuard DNS +
OISD) and **134,875 ABP content rules** (EasyList + EasyPrivacy).

**Memory, measured on device with the full rule set loaded:**

| | Native heap | TOTAL PSS |
|---|---|---|
| `HashSet<String>` (original) | 61.6 MB | 194 MB |
| Compact blob + offset index | **43.7 MB** | **175 MB** |

Roughly 18 MB saved. Less than the structure alone would suggest, because the residual native
heap is now dominated by the ABP content engine (135k rules inside the `adblock` crate), which
this change does not touch. That engine is the next thing to look at if memory matters more.

### Independent benchmark — adblock.turtlecute.org

**97%, 128 of 132 blocked** (from 92% / 122 before the two fixes below).

| Fix | Effect |
|---|---|
| Generic cosmetic rules via `hidden_class_id_selectors` | Static-ad cosmetic test now passes |
| Added OISD to the DNS sources | Host coverage 120/128 → 127/128 |

The remaining 4 failures are understood and **deliberately not chased**:

1. **Dynamic Ad (cosmetic)** — architectural. The bait element is created by JavaScript with
   classes (`ad-unit`, `ad-space`) that appear *only inside the script*, never in the HTML we
   rewrite. Generic cosmetic rules are looked up against the classes present in the served
   document, so a class that does not exist at rewrite time cannot be covered. Browser
   extensions solve this with an in-page MutationObserver; a MITM proxy has no DOM to observe.
2. **`ads.js` / `pagead.js`** — verified absent from EasyList, EasyPrivacy, uBlock Origin's
   `filters.txt` *and* AdGuard Base. A generic `/ads.js` rule would break too many legitimate
   sites, so the lists deliberately do not ship one. Adding a custom rule would be gaming the
   benchmark, not improving the product.
3. **`browser.sentry-cdn.com`** — a JS SDK CDN that mainstream lists intentionally leave alone.

Both offline probes used to reach these conclusions are checked in and need no device:

```bash
cd core
cargo run --example dnstest  -- hosts.txt stevenblack.txt adguard-dns.txt oisd-big.txt
cargo run --example ruletest -- easylist.txt easyprivacy.txt
```

**Throughput could not be measured as a percentage.** The Phase 2 gate called for "within ~20%
of tunnel-off", but the non-tunnelled baseline is broken on this emulator: a direct 1 MB HTTP
download truncates at exactly 141,904 bytes on every attempt, while the same download through
the tunnel returns the full 1,048,994 bytes in 1.27–1.49 s each time. That is a QEMU NAT
artifact, not a tunnel result. Re-measure on physical hardware before trusting any number.

### Bugs found by testing

Three defects that unit tests did not catch and only appeared against real traffic:

1. **rustls' 64 KiB write buffer silently truncated large pages.** `writer().write_all()`
   returns `WouldBlock` past the default cap and the result was being discarded, so a ~1 MB
   rewritten page arrived truncated while `Content-Length` advertised the full size — every
   large site rendered blank. Fixed with `set_buffer_limit(None)`; short writes are now logged
   as errors rather than ignored.
2. **Duplicate `LazyColumn` keys crashed the process.** The log key was
   `ts-name-kind-uid`, which collides when one hostname gets simultaneous A and AAAA lookups
   in the same millisecond. Compose throws, the process dies, and `START_STICKY` restarts it
   into a state where the UI reads "Not protected" while the service is still foreground.
   Fixed with a monotonic `seq` assigned in the core.
3. **Chunked responses were parsed as HTML.** `Connection: close` bounds a body at EOF but
   does not stop chunked encoding; chunk-size lines were being fed to the rewriter. Fixed with
   a `dechunk()` step that returns `None` on malformed framing so the original bytes are
   relayed instead.

A fourth was caught before it could mislead: with `any_ip` enabled, smoltcp answers ICMP echo
for *every* destination, so `ping 192.0.2.1` "succeeded" against a nonexistent host. ICMP is
now dropped outright — proxying it properly needs a raw socket, which is root-only.

---

## Design: Material 3 Expressive

The UI is Material 3 Expressive with Material You dynamic colour, on `material3 1.5.0-alpha26`.

**Dynamic colour is the default on Android 12+.** `dynamicLightColorScheme` /
`dynamicDarkColorScheme` pull the palette the platform derived from the user's wallpaper, so
the app inherits the system's tonal palettes rather than imposing a brand colour. Verified by
seeding the system palette directly and watching every surface, container, switch and
navigation indicator re-tint with contrast intact:

```bash
adb shell "settings put secure theme_customization_overlay_packages \
  '{\"android.theme.customization.system_palette\":\"B33B15\",\
    \"android.theme.customization.theme_style\":\"VIBRANT\"}'"
adb shell cmd uimode night yes    # dark variant
```

Below API 31 the fallbacks are Google-authored rather than hand-picked —
`expressiveLightColorScheme()` and the baseline `darkColorScheme()` — because eyeballing 48
role colours reliably produces contrast failures. Material ships no
`expressiveDarkColorScheme()` yet, hence the asymmetry; devices on API 31+ see neither.

**Expressive, not just tinted rectangles.** The first pass at this was dynamic colour plus a
few Expressive components, and it still looked like Material 2 with rounded corners. What
actually carries the language:

| Element | Treatment |
|---|---|
| Hero badge | A `Morph` between `MaterialShapes.Cookie9Sided` and `MaterialShapes.Sunny`, driven by connection state on a low-stiffness spring so it overshoots and settles. State is carried by *geometry*, not a colour swap. |
| Connect control | `TonalToggleButton` — shape morphs on toggle. Tonal specifically because the *unchecked* state is the primary call to action; the plain variant renders unchecked on a bare surface and reads as disabled. |
| Stat tiles | Deliberate colour variety across `secondaryContainer` / `errorContainer` / `tertiaryContainer` / `surfaceContainerHighest`, at `shapes.extraLarge`. A grid of identical neutral cards is what Expressive moves away from. |
| Navigation | `ShortNavigationBar` — the Expressive bar, label beside the icon. |
| Loading | `ContainedLoadingIndicator` — morphing shape sequence, not a spinning arc. |
| Filter toggle | `ToggleButton` in place of `FilterChip`. |
| Motion | `MotionScheme.expressive()` across the whole theme. |

`MorphShape` in `DashboardScreen.kt` adapts a `Morph` to a Compose `Shape`. It measures the
morph path's real bounds and scales/centres from those rather than assuming a normalised 0..1
box, which keeps every shape in the catalogue correct including the asymmetric ones.

**Why an alpha.** `MaterialExpressiveTheme` and `MotionScheme` exist in stable 1.4.0, but
`MaterialShapes`, `ButtonGroup`, `FloatingToolbar`, `LoadingIndicator`, `SplitButton` and
`ToggleButton` only appear from 1.5.0-alpha. The alpha declares compose-foundation/ui
1.12.0-beta01, which the BOM's stable 1.12.0 already satisfies, so **only material3 is
pre-release** — the rest of Compose stays stable. Drop to 1.4.0 and the theme still works;
the shape morph and toggle buttons are what would need replacing.

## Productionisation

The app was a working engine with a thin UI. This pass made it something that could be relied
on. Everything below was verified on the emulator, not inferred.

### Architecture

- **Room + KSP** for durable history: `log_entries`, `daily_stats`, `app_rules`, `user_rules`
  (`data/db/`). Schemas are exported to `app/schemas/` so migrations have a previous version
  to migrate *from*. No destructive fallback — silently wiping a user's history on a schema
  mistake is worse than failing loudly.
- **Repositories and ViewModels** replace screens reading a singleton and constructing stores
  inside composables. `SettingsRepository` (DataStore) · `LogRepository` · `RulesRepository` ·
  `TunnelRepository`; one ViewModel per screen.
- **`TunnelStatus` is a sealed type with `Failed(reason)`.** A tunnel that could not be
  established previously rendered as plain "Not protected", indistinguishable from the user
  simply not connecting. A privacy tool that is silently off is worse than one that is loudly
  broken.
- **START_STICKY divergence fixed.** A service recreated with a null intent now consults a
  persisted `tunnelDesired` flag instead of blindly re-establishing.

### Features

| Feature | Notes |
|---|---|
| **Allowlist / overrides** | Tap any log row → allow or block that domain. The most important gap: previously a bad rule meant switching off *all* filtering. |
| **Pause / snooze** | 5 m / 30 m / 1 h. Expressed as filtering-off, so the tunnel stays up — no reconnect flicker, no consent re-prompt. Backed by an `AlarmManager` so it expires even if the process dies. |
| **DNS-over-HTTPS** | RFC 8484 over rustls, on its own worker thread. |
| **Quick Settings tile** | Observes tunnel state; an unconsented tap opens the app rather than failing silently. |
| **Start on boot** | Gives the previously-declared, unused `RECEIVE_BOOT_COMPLETED` a purpose. |
| **Scheduled list refresh** | WorkManager, daily, unmetered only — 13 MB of lists is not something to pull over a metered connection unasked. |
| **Settings screen** | Resolver, block-DoT, start-on-boot, battery exemption, overrides, clear history. |

### DNS-over-HTTPS

Closes the largest remaining privacy hole: the app previously sinkholed trackers and then
announced every surviving query in cleartext to the local network.

- `core/src/doh.rs`, reusing `mitm::client_config()`. Runs on a dedicated thread with blocking
  I/O rather than in the packet `poll()` loop — DNS is low-rate and off the hot path, and
  keeping a second TLS state machine out of the loop avoids entangling two very different
  lifetimes.
- **Endpoints must be IP literals.** Resolving a DoH server's own hostname needs DNS, which is
  the thing being established. `doh::parse` rejects hostname endpoints outright.
- **Failure is visible.** A DoH failure falls back to plaintext *and* raises a degraded flag
  the dashboard shows. Silently downgrading would leave the user believing queries are
  encrypted when they are not.

### Verified

| What | Result |
|---|---|
| DoH active | `upstream DNS over HTTPS: https://1.1.1.1/dns-query`, zero fallbacks |
| Blocking over DoH | `doubleclick.net`/`criteo.com`/`adnxs.com` NXDOMAIN; `github.com` resolves |
| Persistence | Cold restart with tunnel **disconnected** still shows 5 blocked / 10 queries, read from Room |
| Allowlist | `criteo.com` blocked → allowed from the log → resolves at 23.185.0.4, while `taboola.com` stays blocked |
| Pause | Filtering off, `tun0` still up, auto-resume restores blocking; override survived |
| Onboarding | Shows once, persists across cold restart |
| Launcher icon | Adaptive icon with monochrome layer renders in the drawer |
| Tests | 64 Rust · 18 Kotlin unit · 14 instrumented, all green |

### Bugs the tests caught

**`OmniShieldApp.onCreate` could crash the app on launch.** `WorkManager.getInstance` throws
if its startup initializer has not run. Unguarded in `Application.onCreate`, that takes down
the whole app — for the sake of a background list refresh. Found because it failed 9
Robolectric tests at once; the fix (guarding the call) is correct on device too, not just
under test.

Also worth recording: an early "onboarding persists" check passed for the wrong reason — it
dumped the UI while DataStore was still loading and saw the blank surface rather than the
absence of onboarding. Re-verified with a proper wait. A test that passes because it measured
nothing is worse than one that fails.

## Efficiency

The app was built for correctness first, and it showed: with **zero traffic and the screen
off**, the process still woke roughly seven times a second, forever, purely to discover that
nothing had changed. Five of those were the Rust tunnel loop (`poll_delay` was clamped to
200 ms even when smoltcp reported nothing pending) and two were a fixed-rate Kotlin drain that
ran regardless of screen state, UI binding, or whether filtering was even paused.

All numbers below are measured on `omnishield-34`, same device, same procedure, via
`/proc/<pid>/stat` deltas over a 300 s window and `dumpsys meminfo`. Battery is not measurable
on QEMU, so **CPU time is the proxy** — it is also the quantity that predicts battery.

| | Before | After | |
|---|---|---|---|
| Idle CPU, screen on | 12.4 s/hour | **3.1 s/hour** | 4× less |
| Idle CPU, screen off | 13.2 s/hour | **0.4 s/hour** | 33× less |
| Native heap, startup | 57.6 MB | **32.0 MB** | −44% |
| TOTAL PSS, startup | 158.2 MB | **132.5 MB** | −16% |
| Native heap, steady | 36.4 MB | **31.8 MB** | −13% |
| TOTAL PSS, steady | 138.5 MB | **131.2 MB** | −5% |
| Tunnel start → filters ready | 3,839 ms | **45 ms** warm / 359 ms cold | 85× warm |
| Release APK | n/a (R8 was off) | **8.5 MB** | — |

The after-column is one run of the final build. A second run of an intermediate build agreed
within noise on everything except screen-on idle, which came out at 2.6 s/hour — so treat that
row as ≈3 s/hour rather than a precise figure. The screen-off row was 0.4 s/hour in both.

The single most telling row is the second. Before, idle cost was *higher* with the screen off
than on — the tunnel's overhead was entirely independent of whether anyone was looking at it,
which is exactly the shape that defeats Doze. It is now essentially free when nothing is
happening.

### What actually changed

**The loop sleeps.** `runtime.rs` now trusts smoltcp's `poll_delay` instead of capping the
sleep at 200 ms, with a 30 s backstop. That makes every off-thread event responsible for
announcing itself, so `core/src/wake.rs` adds a self-pipe written by `Runtime::stop`, by the
JNI config setters, and by the DoH worker — whose answers arrive on a channel the loop only
drains when awake, and would otherwise sit unread. This is now the fourth silent coupling in
`CLAUDE.md`; a producer that forgets to wake looks like a hang, not like slowness.

**The drain adapts.** `PollSchedule` replaces the fixed 500 ms tick: it doubles while the core
reports nothing, capped at 2 s with a screen up and 30 s without, and snaps back the moment
anything arrives. A drain returning ≥75% of the core's 2000-entry ring polls *below* the floor,
because an overflow would silently discard log rows — the one outcome that would make this a
functional regression rather than a cheaper one.

**Filters are built once and cached.** `nativeLoadFilters` now only stages; `nativeCommitFilters`
builds the index and writes `filters.bin` plus a serialized `adblock` engine, keyed by a string
Kotlin derives from the name, size and mtime of each list file. Validating that key costs a few
`stat` calls instead of re-reading 13 MB. A warm start never opens the lists at all.

The start path also used to **re-download all three DNS lists on every single connect** and feed
them straight back in — 13 MB over the network per tunnel start, on top of four full index
rebuilds. The scheduled `FilterRefreshWorker` owns refreshing; a start now only loads.

**R8 now actually runs.** `isMinifyEnabled` was `false`, which made the `proguardFiles` entries
inert — the keep rules for the JNI seam existed but had never been exercised. Release builds are
minified and resource-shrunk, and signed with the debug key so the output is *installable* and
therefore testable: a broken keep rule produces no build error, only a tunnel that silently does
nothing. Verified on device — DNS resolves, `doubleclick.net` is sinkholed, TCP completes,
`nativeStop` returns, and the log still attributes traffic to `com.android.shell` and
`com.google.android.gsf`, which is positive proof that both `lookupUid` and `packageForUid`
survived shrinking.

**Smaller, but on hot paths:** DNS answers are cached with their own TTL (`dns_cache.rs`),
flushed on any rule or config change, so a page touching 30 hosts no longer costs 90 upstream
round trips and 90 radio wakeups. Packet buffers are recycled through a pool rather than
allocated and zeroed per packet. `POLLIN` is no longer requested for a connection whose
`from_remote` is already backed up, which removed a genuine busy-wait. smoltcp ring buffers went
from 64 KiB to 16 KiB each — they only span the app↔tunnel hop, where the round trip is
microseconds and the bandwidth-delay product is tiny — dropping the worst case at
`MAX_CONNECTIONS` from 64 MiB to 16 MiB. Non-HTML MITM responses — every image, script and font
— stopped being cloned in full just to be written out.

### Things this did not fix, and why

- **The blob is read, not `mmap`ed.** The cache removes the parse and the ~37 MB transient
  startup spike, but the ~10 MB domain blob is still dirty anonymous heap at steady state.
  Mapping it would make it evictable, file-backed memory; it needs `DomainSet::blob` to stop
  being a `Vec<u8>`, which is a larger change than this pass warranted.
- **HTML is still parsed twice per rewritten page.** This looks like obvious waste and is not:
  `inject_css` writes into `<head>`, which a streaming rewriter emits long before it has seen
  the class attributes further down the document. The real defect there was a misplaced size
  guard — a 4 MB page was fully parsed and had its selectors computed before being rejected —
  which is fixed.
- **adblock's `single-thread` feature was tried and reverted.** It swaps `Arc` for `Rc` inside
  the engine, making it `!Send`, and `Shared` is held in an `Arc` shared with the tunnel thread.
- **Sockets are still allocated at SYN, before the firewall check.** Allocating after would
  mean dropping the SYN for a blocked app, and a dropped SYN is a connect *timeout* where the
  current behaviour is an immediate reset — trading 32 KiB for a user-visible hang is a bad
  deal. Shrinking the ring got most of the benefit without touching the semantics.
- **DNS attribution is still one JNI call per query.** Attribution is now cached per UDP
  session, but every app on the device shares one source address across the TUN, so the only
  discriminator is the ephemeral port — and a resolver that opens a fresh socket per lookup
  gets no reuse. The saving is real where sockets are reused and zero where they are not.
- **`SettingsViewModel.settingsOrNull` is deliberately still `SharingStarted.Eagerly`.** It is
  the only eager collector left and stands out in an audit, but it exists so the first value is
  ready before the first composition — making it lazy trades a visible onboarding flash for no
  background saving at all, since the activity owns it.

### No functional regression

Verified rather than assumed, because every change here makes the app do *less*:

- **101 Rust, 24 Kotlin unit and 16 instrumented tests pass.**
- **431,819 DNS rules and 134,880 ABP rules** load — identical to before, on device and in the
  offline probes.
- The offline probe blocks **60/60** known tracker hostnames and **0/14** legitimate ones.
  `ruletest` still blocks `adsbygoogle.js` and `analytics.js` and still shows exactly the two
  documented `ads.js` / `pagead.js` gaps.
- On device: `github.com` resolves, `doubleclick.net` returns NXDOMAIN, TCP fetches complete.
- **`nativeStop` returns in ~3 ms** with the loop blocked in a 30 s `poll`, which is the proof
  the wake pipe works — without it the join would have waited out the ceiling.

One bug was caught by a test written for this work and would otherwise have been severe:
splitting `load_list` into stage-and-commit left the old `pending.clear()` at the top of
`stage_list`, so **every list but the last was silently discarded** — the filter would have
dropped from 431k rules to a few hundred with no error anywhere.

---

## User interface

The app was built outward from the packet path, and it showed: every screen was a thin
rendering of whatever state the core exposed, and none of them explained themselves. The
firewall was the clearest case — a `App | Wi-Fi | Data` header over a list of bare switches,
with nothing saying what the screen did or which way the switches ran. They *block*, which is
the opposite of the usual "on means enabled" reading, and there was no safe way to guess.

### What changed

| | Before | After |
|---|---|---|
| Screens with a title | 0 of 5 | 5 of 5, each with a line saying what it is for |
| Feedback on an action | none — no `Snackbar` anywhere | every mutation acknowledged; firewall toggles undoable |
| Destructive actions | fired on one tap, silently | confirmation naming the row count and what survives |
| Firewall rule state | switch position only | stated in words per row, in the row's own colour |
| Log verdict | a 10 dp coloured dot | icon + colour + text in the row description |
| Hardcoded UI strings | 15 | 0 |

Three features existed in the repositories and had no way in at all. They do now: **filter
lists** (sizes, last-updated, a manual refresh — `settings_lists` had been defined in
`strings.xml` and referenced nowhere), an **editable resolver** (`setDohUrl`/`setUpstreamUdp`
were wired but the screen rendered static text), and **un-pinning an app** that once rejected
our certificate (`clearPinnedUids` was never called from anywhere, so the bypass was
permanent). The log's kind filter was the fourth — `setKind` had no caller.

### Two bugs this surfaced

- **Overrides on rows that have no domain.** The sheet offered "Always allow" on every log row
  and stored the row's label. A `tcp` row is labelled `address:port` and a content-filter row
  with a full URL, and user rules match domains — so allowing a TCP row wrote a rule that
  could never match, listed it in Settings as though it were in force, and left the user
  believing they had unblocked something. `overrideTarget` now extracts a host, or declines.
- **A list that reordered under the finger.** Grouping blocked apps at the top of the firewall
  looked right in a screenshot and was wrong in the hand: a `LazyColumn` holds its scroll
  offset while items are inserted above it, so toggling a switch slid the row just touched out
  of the viewport. Caught on the emulator, not in review. The order is now always alphabetical
  and the grouping is a filter chip.

### Verified

- **101 Rust, 49 Kotlin unit and 36 instrumented tests pass**, the latter two up from 24 and 16.
  The new ones cover the firewall wording and undo, both confirmation dialogs, resolver
  validation, day bucketing across local midnight, override targeting, and the per-app
  interception switches being inert while the master switch is off.
- Every screen was captured on the emulator in light and dark, plus the paused dashboard, the
  override sheet, both dialogs, search, and an empty log. The reordering bug above was found
  that way and nowhere else.
- Idle CPU with the screen off is unchanged from the efficiency pass, which is the check that
  matters here: a UI pass is exactly where a stray eager collector or a per-frame binder call
  gets reintroduced.

---

## Known limitations

- **HTTPS filtering only reaches Chrome-family browsers.** Since Android 7, apps ignore
  user-installed CAs unless they opt in via their own network security config. This is a
  platform constraint, not an implementation gap. Interception is opt-in per UID and bypassed
  by default; an app that rejects our certificate is remembered and permanently bypassed.
- **One request per TLS connection.** ALPN is pinned to `http/1.1` and `Connection: close` is
  forced, so response bodies are delimited by EOF. This removes the keep-alive framing problem
  entirely at the cost of more connections per page.
- **ICMP is not proxied**, so `ping` does not work through the tunnel.
- **IPv6 extension headers are not walked** — such packets are dropped rather than guessed at.
- **Mobile-data firewall rules are unverifiable on an emulator** (no cellular radio).
- **Response bodies are buffered whole** for rewriting, capped at 4 MB.
- **The prebuilt filter cache costs ~16 MB of disk** (`filters.bin` 10.2 MB, `content.bin`
  6.0 MB) to save the parse on every start. It is rebuilt automatically whenever a list file
  changes, and any doubt about it — wrong key, wrong version, truncation — falls back to
  parsing rather than serving a filter that might be wrong.
- Google Play is off the table permanently: Play policy forbids apps that block ads in other
  apps. Distribution is sideloaded APK, which is why `QUERY_ALL_PACKAGES` is unproblematic.

---

## Version constraints

Changing any of these tends to break the build in a non-obvious way.

| Constraint | Reason |
|---|---|
| **AGP 9.3.1**, not 8.x | `androidx.core:1.19.0` and `lifecycle:2.11.0` declare `requires Android Gradle plugin 9.1.0 or higher` in their AAR metadata. |
| **No `kotlin-android` plugin** | AGP 9.0+ has built-in Kotlin support and hard-fails if the standalone plugin is also applied. |
| **`compileSdk = 37`, `targetSdk = 34`** | Current AndroidX refuses to compile against anything older. The two are independent knobs. |
| Platform is **`platforms;android-37.0`** | API 37 ships under the new minor-version scheme; `platforms;android-37` does not exist. |
| **`minSdk = 29`** | `ConnectivityManager.getConnectionOwnerUid` does not exist below Android 10, and `/proc/net` scraping was blocked in the same release. No firewall is possible below 29. |
| **cargo-ndk `-P`**, capital | 4.x repurposed lowercase `-p` to `--package`; it fails with `unknown package: 29`. |
| Gradle **9.7** | Required by AGP 9.x. Gradle 9.6 also turned `tasks.registering` and `sourceSets[...].java.srcDirs` into hard errors. |
| `android.ndkDirectory` unavailable | Removed in AGP 9; the NDK path is derived from `local.properties`/`ANDROID_HOME`. |
| rustls/rcgen use the **`ring`** backend | `aws-lc-rs` needs a full CMake/Go toolchain to cross-compile. |

> **Caution:** cargo-ndk's panic handler dumps the entire process environment to stdout,
> including any secrets exported in your shell. Do not run it from a shell holding live
> credentials.

---

## Deviations from the original plan

- `getConnectionOwnerUid` lives on **`ConnectivityManager`**, not `VpnService` as the plan
  stated.
- The JNI package is `io.omnishield.bridge`, not `native` — a Java reserved keyword.
- **Room was replaced by `SharedPreferences`** holding a JSON blob. Room needs KSP, whose
  Kotlin-version coupling is exactly the dependency risk that already forced one rewrite here.
  The dataset is a few hundred `uid → flags` rows read once at tunnel start. Revisit if
  per-connection history ever needs on-device querying.
- DNS matching uses a reversed-label suffix walk over a `HashSet`, not a trie — a hostname has
  ~6 labels, so lookup is a handful of hash probes.
- EasyList is ABP browser syntax and is **not** usable for DNS blocking; DNS uses hosts-format
  and AdGuard-DNS-format lists, and EasyList is confined to Layer 3.
