# Architecture

OmniShield is a Kotlin/Compose app over a Rust core. The core owns the entire packet path, and no
packet ever crosses the JNI boundary.

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

## The language split

Kotlin owns lifecycle, persistence and UI. Rust owns everything on the packet path. Once
`OmniShieldVpnService` calls `ParcelFileDescriptor.detachFd()` and hands the descriptor to
`nativeStart`, the packet path is entirely native. That boundary is the reason the core is in
Rust at all. Keep it intact.

Events flow back by polling rather than by native-to-JVM callbacks, which would mean attaching
the tunnel thread to the JVM once per event. The poll interval adapts: it starts at 500 ms,
doubles while the core reports nothing, and caps at 2 s with the UI in front or 30 s without. A
drain that returns 75% or more of the core's 2000-entry ring snaps the interval back to the
floor, so a busy device cannot overflow the ring and lose log rows.

## Transparent TCP

The hard problem in a transparent tunnel is that smoltcp sockets bind to a *specific* endpoint,
while the tunnel has to accept connections to arbitrary destinations. Two mechanisms solve it:

1. `Interface::set_any_ip(true)`, so the interface accepts packets not addressed to it.
2. Every packet is peeked before smoltcp sees it. On a SYN to a new 4-tuple, a socket is created
   already listening on that exact destination, and only then is the frame handed over.

`packet::parse` returning `None` means drop, never "forward unfiltered". Passing along a packet
that failed to parse would be a filtering bypass.

## Filtering layers

| Layer | Module | Sees |
|---|---|---|
| 1, DNS sinkholing | `dns.rs` + `filter.rs` + `dns_cache.rs` | All traffic |
| 2, TLS termination | `ca.rs` + `mitm.rs` | Opt-in per UID only |
| 3, ABP rules and cosmetic filtering | `content.rs` | Decrypted traffic only |

Layer 2 is opt-in and bypassed by default. Since Android 7, apps ignore user-installed CAs unless
they opt in, so in practice it reaches Chrome-family browsers and little else. An app that
rejects our certificate is recorded and permanently bypassed rather than left broken.

`filter.rs` stores the roughly 430k list domains as one UTF-8 blob plus a sorted offset index,
binary-searched, rather than a `HashSet<String>`. User overrides are checked before the lists at
each suffix level, so an explicit choice beats a downloaded rule at the same specificity.

Filters are built once and cached. `nativeLoadFilters` only stages text; `nativeCommitFilters`
builds the index and writes `filters.bin` plus `content.bin` under `cache_dir`, keyed by a string
Kotlin derives from the name, size and mtime of every list file. A warm start never opens the
13 MB of lists at all. Any doubt about the cache, whether missing, truncated, wrongly keyed or
the wrong version, falls back to parsing.

DNS list formats and ABP browser syntax are not interchangeable. `FilterRepository` keeps
`DNS_SOURCES` and `CONTENT_SOURCES` separate; feeding EasyList to the DNS filter produces junk.

## Four couplings that fail silently

None of these produce a compile error when broken. They fail at runtime, usually as "nothing
happens".

1. **JNI symbol mangling:** `core/src/android.rs` exports 14
   `Java_io_omnishield_bridge_NativeBridge_*` functions matching 14 `external fun`s in
   `bridge/NativeBridge.kt`. Renaming that object or its package means renaming every Rust export
   in lockstep. The package is `bridge`, not `native`, because `native` is a Java reserved word.
2. **Reverse callbacks:** `core/src/jvm.rs` calls Kotlin by name *and* JNI signature string:
   `protect(I)Z`, `lookupUid(ILjava/lang/String;ILjava/lang/String;I)I` and
   `packageForUid(I)Ljava/lang/String;` on `OmniShieldVpnService`. These are `@Keep`-annotated and
   listed in `proguard-rules.pro`.
3. **The config JSON contract:** `CoreJson.buildConfig` must use the exact serde field names in
   `core/src/config.rs`. A renamed field decodes silently to that field's default, which is why
   `CoreJsonTest` asserts the field names.
4. **Every off-thread event must wake the tunnel loop.** This one fails as a hang rather than as
   nothing happening. The loop sleeps until there is something to do, with a 30 s backstop,
   instead of waking on a timer, so anything changing state from another thread has to call
   `Runtime::wake`: the JNI config and rule setters, `Runtime::stop`, and the DoH worker, whose
   answers arrive on an mpsc channel the loop drains only while awake. `core/src/wake.rs`
   documents all three. A new producer that forgets to wake looks like a hung tunnel, and the
   30 s ceiling is the only reason it self-heals instead of staying wedged.

## The tunnel does not claim the local network

`TunnelRoutes` computes the complement of the private ranges (RFC 1918, link-local, 100.64/10,
multicast) and claims the rest: 47 IPv4 routes and 5 IPv6. A default route of `0.0.0.0/0` would be
simpler and is what a VPN normally takes, and it breaks every inbound connection from the local
network.

The inbound SYN is not the problem, since a VPN cannot intercept that and it arrives on the
physical interface as usual. The reply is. It is routed by destination, matches the default route,
and is handed to the TUN, where the core has no socket for it, because sockets are created only
when a SYN is peeked. A SYN-ACK for an unseen 4-tuple is dropped and the peer waits until it gives
up. Everything that listens on the device fails that way: file transfer apps, media servers,
`adb connect`, a desktop reaching the phone.

Two consequences follow. LAN traffic is unfiltered, which is the intended trade and what every
VPN-based blocker does; ad and tracker domains do not live on `192.168.0.0/16`. And `10.0.0.0/24`
is claimed back explicitly, because the DNS sentinel every app is handed lives inside the excluded
`10/8`, and without that route name resolution stops device-wide.

`Builder.excludeRoute` expresses this directly but is API 33 against a `minSdk` of 29, which is why
the complement is computed instead. The same `minSdk` rules out `BigInteger.TWO`, an API 33 field
that throws `NoSuchFieldError` on Android 10 through 12. Unit tests cannot catch that class of
mistake here, because they run on a desktop JVM where the field exists; lint can, which is why lint
gates the build.

## Kotlin data flow

`OmniShieldVpnService` feeds `TunnelRepository` (process-level observable state) and
`LogRepository` (durable Room history plus daily rollups). Screens read ViewModels only; they
never touch `NativeBridge` or construct repositories inline. The log screen reads Room directly
rather than a mirrored field on a repository, which would be rewritten several times a second and
read only while that one screen is open.

`LogRepository` batches on purpose. Inserts go in per drain inside one transaction, retention
pruning runs on a five-minute timer, and daily counter deltas accumulate in memory and flush
every 30 s and on tunnel stop, via `flushPending`. Skip that call and the last few minutes of a
session are lost.

`TunnelStatus` is a sealed type including `Failed(reason)`. A tunnel that could not start has to
render its reason; falling back to something indistinguishable from "not connected" is how a
privacy tool ends up silently off.

Room has no destructive migration fallback. Schema changes need a real migration, and schemas are
exported to `app/schemas/`.

## Screens

Five tabs, one file each in `ui/`, all wrapped in `ui/components/ScreenScaffold`, which supplies
the title bar (title plus a one-line statement of what the screen is for) and the `SnackbarHost`
reachable through `LocalSnackbar`. A screen without it renders with no title and throws on the
first snackbar. Both are deliberate: every screen must say what it is, and every action must be
acknowledged.

`MainActivity`'s `Scaffold` owns the navigation bar and zeroes its own `contentWindowInsets`,
because each `TopAppBar` applies the status-bar inset itself. Padding both double-pads.

Three things here are not free choices:

- `ruleSummary` (firewall) and `overrideTarget` (log) carry the real UI logic, and both have unit
  tests. The firewall's switches *block*, which is the opposite of the usual reading, so the row
  states its rule in words and the switch is a second representation of it. `overrideTarget`
  decides whether a log row even has a domain to override: a `tcp` row is labelled `address:port`
  and an `http` row with a full URL, and a user rule keyed on either literal string is stored,
  listed in Settings, and matches nothing.
- The firewall list is always alphabetical and never reorders itself. A `LazyColumn` holds its
  scroll offset while items are inserted above it, so hoisting blocked apps to the top slides the
  row the user just touched out of the viewport. Finding blocked apps is a filter chip instead.
- New filter lists are not pushed into a running core. `FilterRefreshWorker` explains why, so the
  manual "Refresh now" in Settings says the lists load on the next connect rather than implying
  an effect it does not have.

## Limitations

- **HTTPS filtering only reaches Chrome-family browsers.** Since Android 7, apps ignore
  user-installed CAs unless they opt in via their own network security config. Platform
  constraint, not an implementation gap.
- **One request per TLS connection:** ALPN is pinned to `http/1.1` and `Connection: close` is
  forced, so response bodies are delimited by EOF. That removes the keep-alive framing problem
  entirely, at the cost of more connections per page.
- **ICMP is not proxied**, so `ping` does not work through the tunnel. Proxying it properly needs
  a raw socket, which is root-only.
- **IPv6 extension headers are not walked.** Such packets are dropped rather than guessed at.
- **Response bodies are buffered whole** for rewriting, capped at 4 MB.
- **The prebuilt filter cache costs about 16 MB of disk** (`filters.bin` 10.2 MB, `content.bin`
  6.0 MB) to save the parse on every start. It is rebuilt whenever a list file changes.
- **Mobile-data firewall rules cannot be verified on an emulator**, which has no cellular radio.
- **Google Play is off the table permanently.** Play policy forbids apps that block ads in other
  apps, so distribution is a sideloaded APK, which is also why `QUERY_ALL_PACKAGES` is
  unproblematic here.
