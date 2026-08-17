# Verification

Everything here was observed on an API 34 emulator, not inferred.

## Phase gates

| Phase | Gate | Result |
|---|---|---|
| 0 | Rust `.so` builds, is packaged, loads | `omnishield-core 0.1.0 initialised` in logcat |
| 1 | Foreground service survives Doze | `types=40000000`; alive through `deviceidle force-idle` |
| 2 | Transparent TCP through smoltcp | `HTTP/1.1 200 OK` from example.com over the tunnel |
| 3 | DNS allow/block | `doubleclick.net` returns NXDOMAIN; `github.com` resolves |
| 4 | TLS interception | Chrome: "Chrome verified that OmniShield Root CA issued this website's certificate" |
| 5 | Content filtering | theguardian.com rewritten, `rewritten=true`, 1,256,400 bytes emitted |
| 6 | Per-app firewall | Blocking `com.android.shell` dropped its TCP while DNS still resolved; the counter incremented; unblocking restored it |
| 7 | UI | Dashboard, live log (181 entries), per-app toggles, HTTPS controls |

Filter volume in a live run: **431,819 DNS rules** (bundled, StevenBlack, AdGuard DNS, OISD) and
**134,875 ABP content rules** (EasyList, EasyPrivacy).

## Productionisation

| What | Result |
|---|---|
| DoH active | `upstream DNS over HTTPS: https://1.1.1.1/dns-query`, zero fallbacks |
| Blocking over DoH | `doubleclick.net`, `criteo.com` and `adnxs.com` NXDOMAIN; `github.com` resolves |
| Persistence | Cold restart with the tunnel disconnected still shows 5 blocked / 10 queries, read from Room |
| Allowlist | `criteo.com` blocked, then allowed from the log, then resolves at 23.185.0.4, while `taboola.com` stays blocked |
| Pause | Filtering off, `tun0` still up, auto-resume restores blocking; the override survived |
| Onboarding | Shows once, persists across a cold restart |
| Launcher icon | Adaptive icon with a monochrome layer renders in the drawer |

`TunnelStatus` gained `Failed(reason)` here. A tunnel that could not be established previously
rendered as plain "Not protected", which is indistinguishable from the user simply not
connecting, and a privacy tool that is silently off is worse than one that is loudly broken.

`START_STICKY` divergence was fixed at the same time: a service recreated with a null intent now
consults a persisted `tunnelDesired` flag instead of blindly re-establishing.

## Memory with the full rule set loaded

| | Native heap | TOTAL PSS |
|---|---|---|
| `HashSet<String>` (original) | 61.6 MB | 194 MB |
| Compact blob + offset index | **43.7 MB** | **175 MB** |

Roughly 18 MB saved, which is less than the structure alone would suggest. The residual native
heap is now dominated by the ABP content engine (135k rules inside the `adblock` crate), which
this change does not touch. That engine is the next place to look if memory matters more.

## Independent benchmark: adblock.turtlecute.org

**128 of 132 blocked, 97%**, up from 122 and 92% before two fixes:

| Fix | Effect |
|---|---|
| Generic cosmetic rules via `hidden_class_id_selectors` | The static-ad cosmetic test now passes |
| Added OISD to the DNS sources | Host coverage went from 120/128 to 127/128 |

The remaining four failures are understood and deliberately not chased.

1. **Dynamic Ad (cosmetic)** is architectural. The bait element is created by JavaScript with
   classes (`ad-unit`, `ad-space`) that appear only inside the script and never in the HTML being
   rewritten. Generic cosmetic rules are looked up against the classes present in the served
   document, so a class that does not exist at rewrite time cannot be covered. Browser extensions
   solve this with an in-page MutationObserver; a MITM proxy has no DOM to observe.
2. **`ads.js` and `pagead.js`** are absent from EasyList, EasyPrivacy, uBlock Origin's
   `filters.txt` and AdGuard Base. A generic `/ads.js` rule would break too many legitimate
   sites, so the lists deliberately do not ship one. Adding a custom rule would be gaming the
   benchmark rather than improving the product.
3. **`browser.sentry-cdn.com`** is a JS SDK CDN that mainstream lists intentionally leave alone.

Both offline probes used to reach these conclusions are checked in and need no device. See
[Development](development.md#offline-probes).

## Throughput could not be measured as a percentage

The Phase 2 gate called for "within about 20% of tunnel-off", but the non-tunnelled baseline is
broken on this emulator. A direct 1 MB HTTP download truncates at exactly 141,904 bytes on every
attempt, while the same download through the tunnel returns the full 1,048,994 bytes in 1.27 to
1.49 s each time. That is a QEMU NAT artifact rather than a tunnel result. Re-measure on physical
hardware before trusting any number.

## Bugs that only real traffic found

Three defects that unit tests did not catch:

1. **rustls' 64 KiB write buffer silently truncated large pages.** `writer().write_all()` returns
   `WouldBlock` past the default cap, and the result was being discarded, so a 1 MB rewritten page
   arrived truncated while `Content-Length` advertised the full size. Every large site rendered
   blank. Fixed with `set_buffer_limit(None)`; short writes are now logged as errors rather than
   ignored.
2. **Duplicate `LazyColumn` keys crashed the process.** The log key was `ts-name-kind-uid`, which
   collides when one hostname gets simultaneous A and AAAA lookups in the same millisecond.
   Compose throws, the process dies, and `START_STICKY` restarts it into a state where the UI
   reads "Not protected" while the service is still foreground. Fixed with a monotonic `seq`
   assigned in the core.
3. **Chunked responses were parsed as HTML.** `Connection: close` bounds a body at EOF but does
   not stop chunked encoding, so chunk-size lines were being fed to the rewriter. Fixed with a
   `dechunk()` step that returns `None` on malformed framing, so the original bytes are relayed
   instead.

A fourth was caught before it could mislead. With `any_ip` enabled, smoltcp answers ICMP echo for
every destination, so `ping 192.0.2.1` "succeeded" against a host that does not exist. ICMP is now
dropped outright.

## Bugs the test suites caught

`OmniShieldApp.onCreate` could crash the app on launch. `WorkManager.getInstance` throws if
its startup initializer has not run. Unguarded in `Application.onCreate`, that takes down the
whole app for the sake of a background list refresh. It failed nine Robolectric tests at once,
and guarding the call is correct on device too, not only under test.

Splitting `load_list` into stage-and-commit silently discarded lists. The old
`pending.clear()` was left at the top of `stage_list`, so every list but the last was dropped. The
filter would have gone from 431k rules to a few hundred with no error anywhere.

`BigInteger.TWO` would have thrown on Android 10 through 12. It is an API 33 field against a
`minSdk` of 29, so the tunnel would have died the moment it started. The unit tests run on a
desktop JVM, where the field exists, and passed. Lint caught it.

One check also passed for the wrong reason and had to be redone: an early "onboarding persists"
test dumped the UI while DataStore was still loading and saw the blank surface rather than the
absence of onboarding. A test that passes because it measured nothing is worse than one that
fails.

## No functional regression after the efficiency pass

Verified rather than assumed, because every change in that pass makes the app do less.

- **101 Rust, 58 Kotlin unit and 36 instrumented tests pass.**
- **431,819 DNS rules and 134,880 ABP rules** load, identical to before, both on device and in the
  offline probes.
- The offline probe blocks **60/60** known tracker hostnames and **0/14** legitimate ones.
  `ruletest` still blocks `adsbygoogle.js` and `analytics.js`, and still shows exactly the two
  documented `ads.js` / `pagead.js` gaps.
- On device, `github.com` resolves, `doubleclick.net` returns NXDOMAIN, and TCP fetches complete.
- **`nativeStop` returns in about 3 ms** with the loop blocked in a 30 s `poll`, which is the
  proof that the wake pipe works. Without it, the join would have waited out the ceiling.

R8 was verified separately, because a broken keep rule produces no build error, only a tunnel that
silently does nothing. On a minified release build, DNS resolves, `doubleclick.net` is sinkholed,
TCP completes, `nativeStop` returns, and the log still attributes traffic to `com.android.shell`
and `com.google.android.gsf`, which is positive proof that both `lookupUid` and `packageForUid`
survived shrinking.

## Local network routing

Port 53317 on the phone was closed to a LAN peer with the tunnel up and open with it down, which
is what identified the default-route bug described in
[Architecture](architecture.md#the-tunnel-does-not-claim-the-local-network). After the fix,
`ip route show table all` shows no private prefix on `tun0`, 77 routes total, and a LocalSend
transfer to the phone completes with the tunnel running. DNS filtering is unchanged.

This cannot be reproduced on the emulator: QEMU's NAT has no real LAN peer to connect inward
from.
