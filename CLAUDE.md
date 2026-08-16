# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

OmniShield is a non-root Android ad/tracker blocker: a Kotlin/Compose app over a Rust core that
owns the entire packet path behind `VpnService`.

`README.md` holds the measured verification results, the version-constraint table, and known
platform limitations. Read it before changing build config or claiming a behaviour works. Its
**Efficiency** section records the before/after cost numbers and, more usefully, the three
things that look like obvious waste but are not — check there before "fixing" one of them.

## Environment

Nothing is on the default PATH. Every command below assumes:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17          # keg-only
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:/opt/homebrew/opt/rustup/bin:$HOME/.cargo/bin:$PATH"
```

rustup's shims live in `/opt/homebrew/opt/rustup/bin`, **not** `~/.cargo/bin` (which holds only
cargo-installed binaries like `cargo-ndk`). Gradle resolves `cargo` itself via `rustToolDirs` in
`app/build.gradle.kts`, so builds work without these exports; the Rust commands do not.

This is **not a git repository**.

## Commands

```bash
./gradlew assembleDebug        # cargoNdkBuild runs automatically via preBuild
./gradlew installDebug
```

Measuring cost (the harness the Efficiency numbers came from lives in the session scratchpad;
recreate it if needed). CPU time is the battery proxy — battery itself is not measurable on
QEMU. Compare `/proc/<pid>/stat` utime+stime deltas over a fixed window, with the screen both
on and off, plus `dumpsys meminfo io.omnishield` at startup *and* 60 s later; the startup peak
and the steady state are different numbers and the peak is the one caching addresses.

Tests — all three suites must pass:

```bash
cd core && cargo test                      # 101 host-native tests, no emulator
./gradlew testDebugUnitTest                # 24 Kotlin unit tests (Robolectric)
./gradlew connectedDebugAndroidTest        # 16 Room tests, needs a running emulator
```

Single tests:

```bash
cd core && cargo test filter::tests::www_rule_does_not_widen_to_apex
./gradlew testDebugUnitTest --tests "io.omnishield.data.CoreJsonTest"
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.omnishield.data.db.DatabaseTest
```

Building the Rust core alone **must run from `core/`** — cargo-ndk invokes `cargo metadata` in
the working directory, so `--manifest-path` alone is not enough. Note `-P` (capital) is the API
level; lowercase `-p` means `--package` in cargo-ndk 4.x and fails with `unknown package: 29`:

```bash
cd core && cargo ndk -t arm64-v8a -P 29 -o ../app/src/main/jniLibs build --release
```

Two offline probes exist for reasoning about filter behaviour without a device. Use these
instead of guessing whether a miss is a rule gap or a code bug:

```bash
cd core
cargo run --release --example dnstest  -- probe-hosts.txt <list.txt> [more.txt ...]
cargo run --release --example ruletest -- easylist.txt easyprivacy.txt
```

**`dnstest`'s first argument is the list of hostnames to *test*, not a blocklist** — one bare
hostname per line. Passing a blocklist there reports `blocked 0/N` and looks exactly like a
catastrophic filtering regression. Everything after it is a blocklist. Run it twice: once with
hostnames that must be blocked and once with legitimate ones that must not, because
over-blocking is the failure mode a single probe will not show you.

The lists themselves are not in the repo. Pull the real ones off a device that has run once:

```bash
adb shell "run-as io.omnishield cat files/filters/stevenblack-hosts.txt" > stevenblack-hosts.txt
unzip -p app/build/outputs/apk/debug/app-debug.apk assets/filters/default.txt > bundled.txt
```

Use `--release`: a debug build parses 13 MB of lists slowly enough to look hung.

Emulators (`omnishield-34` is the default target; `omnishield-33` exists because Android 14
made the system trust store immutable, so CA-injection testing only works on 33):

```bash
$ANDROID_HOME/emulator/emulator -avd omnishield-34 -no-snapshot -no-boot-anim
adb shell appops set io.omnishield ACTIVATE_VPN allow   # skip the consent dialog when scripting
```

## Architecture

### The language split

Kotlin owns lifecycle, persistence and UI. Rust owns everything on the packet path. Once
`OmniShieldVpnService` calls `ParcelFileDescriptor.detachFd()` and hands the descriptor to
`nativeStart`, **no packet crosses the JNI boundary**. Preserve this: it is the reason the core
is in Rust at all.

Events flow back by *polling* (`nativeDrainEvents` in `pollCore()`), not by native-to-JVM
callbacks, which would require attaching the tunnel thread to the JVM per event. The poll
interval is **adaptive**, not fixed: it starts at 500 ms, doubles while the core reports
nothing, and is capped at 2 s with a UI bound or 30 s without (`TunnelRepository.uiActive`). A
drain returning ≥75% of the core's 2000-entry ring snaps it back to the floor, so a busy device
cannot overflow the ring and lose log rows.

### Four couplings that fail silently

None of these produce a compile error when broken. They fail at runtime, usually as "nothing
happens".

1. **JNI symbol mangling.** `core/src/android.rs` exports 14 `Java_io_omnishield_bridge_NativeBridge_*`
   functions matching 14 `external fun`s in `bridge/NativeBridge.kt`. Renaming that object or
   its package requires renaming every Rust export in lockstep. (The package is `bridge`, not
   `native` — `native` is a Java reserved word.)
2. **Reverse callbacks.** `core/src/jvm.rs` calls Kotlin by name *and JNI signature string*:
   `protect(I)Z`, `lookupUid(ILjava/lang/String;ILjava/lang/String;I)I`, `packageForUid(I)Ljava/lang/String;`
   on `OmniShieldVpnService`. These are `@Keep`-annotated and listed in `proguard-rules.pro`.
3. **The config JSON contract.** `CoreJson.buildConfig` in Kotlin must use the exact serde field
   names in `core/src/config.rs`. A renamed field silently decodes to that field's default.
   `CoreJsonTest` asserts the field names for this reason.
4. **Every off-thread event must wake the tunnel loop.** This one fails as a *hang* rather
   than as nothing happening. The loop sleeps until there is something to do (backstop
   `IDLE_CEILING_MS`, 30 s) instead of waking on a timer, so anything that changes state from
   another thread must call `Runtime::wake`: the JNI config and rule setters, `Runtime::stop`,
   and the DoH worker — whose answers arrive on an mpsc channel the loop only drains while
   awake. `core/src/wake.rs` documents all three. A new producer that forgets to wake looks
   like a hung tunnel, and the 30 s ceiling is the only reason it self-heals instead of
   staying wedged.

### The tunnel loop (`core/src/runtime.rs`, ~1450 lines)

One thread drives a single `poll()` over the TUN descriptor plus every upstream socket. The hard
problem it solves: smoltcp sockets bind to a *specific* endpoint, but a transparent tunnel must
accept arbitrary destinations. Two mechanisms combine:

- `Interface::set_any_ip(true)` — accept packets not addressed to us.
- Every packet is peeked before smoltcp sees it; on a SYN to a new 4-tuple a socket is created
  already listening on that exact destination, *then* the frame is handed over.

`packet::parse` returning `None` means **drop**, never "forward unfiltered" — passing a packet we
failed to parse would be a filtering bypass.

### Filtering layers

| Layer | Module | Sees |
|---|---|---|
| 1 — DNS sinkholing | `dns.rs` + `filter.rs` + `dns_cache.rs` | All traffic |
| 2 — TLS termination | `ca.rs` + `mitm.rs` | Opt-in per UID only |
| 3 — ABP rules + cosmetic | `content.rs` | Decrypted traffic only |

Layer 2 is opt-in and bypassed by default, because since Android 7 apps ignore user-installed
CAs unless they opt in — it reaches Chrome-family browsers and little else. An app that rejects
our certificate is recorded and permanently bypassed rather than left broken.

Filters are **built once and cached**. `nativeLoadFilters` only *stages* text;
`nativeCommitFilters` builds the index and writes `filters.bin` + `content.bin` (the serialized
`adblock` engine) under `cache_dir`, keyed by a string Kotlin derives from the name/size/mtime
of every list file. `nativeLoadCachedFilters` restores both and returns -1 on a miss. A warm
start therefore never opens the ~13 MB of lists at all. Any doubt — missing, truncated, wrong
key, wrong version — falls back to parsing; see `core/src/cache.rs`.

`filter.rs` stores the ~430k list domains as one UTF-8 blob plus a sorted offset index (binary
search), not `HashSet<String>`. Its existing tests are the contract for any change here —
particularly `does_not_block_sibling_suffix` and `www_rule_does_not_widen_to_apex`. User
overrides are checked *before* the lists at each suffix level, so an explicit choice beats a
downloaded rule at the same specificity.

DNS list formats and ABP browser syntax are **not interchangeable**. `FilterRepository` keeps
`DNS_SOURCES` and `CONTENT_SOURCES` separate; feeding EasyList to the DNS filter produces junk.

### Kotlin data flow

`OmniShieldVpnService` → `TunnelRepository` (process-level observable state) and
`LogRepository` (durable Room history + daily rollups). Screens read ViewModels only; they never
touch `NativeBridge` or construct repositories inline. The log screen reads Room, not a
repository field — `TunnelRepository` used to also hold a `liveLog` that the service rewrote
twice a second and nothing ever read.

`LogRepository` batches on purpose: inserts go in per drain inside one transaction, retention
pruning runs on a five-minute timer, and daily counter deltas accumulate in memory and flush
every 30 s (and on tunnel stop, via `flushPending` — skip that call and the last few minutes of
a session are lost).

`TunnelStatus` is a sealed type including `Failed(reason)` — a tunnel that could not start must
render its reason, not fall back to looking like "not connected".

Room has **no destructive migration fallback**. Schema changes need a real migration; schemas
are exported to `app/schemas/`.

## Build constraints worth knowing before touching Gradle

`README.md` has the full table. The ones that bite hardest:

- **AGP 9.x is mandatory** (current AndroidX declares `requires Android Gradle plugin 9.1.0 or
  higher`), and AGP 9 has built-in Kotlin — applying the `kotlin-android` plugin is a hard
  failure.
- **`compileSdk` 37 / `targetSdk` 34** are deliberately different; the platform package is
  `platforms;android-37.0` (the `.0` is required).
- **`minSdk` 29 is load-bearing**: `ConnectivityManager.getConnectionOwnerUid` does not exist
  below it and `/proc/net` scraping was blocked in the same release, so the per-app firewall is
  impossible on anything older.
- material3 is pinned to a **1.5.0-alpha** ahead of the BOM for the Expressive component set;
  the rest of Compose stays stable.
- **`core/Cargo.toml` sets `panic = "abort"`.** A panic cannot usefully unwind across the JNI
  boundary, and the alternative is worse than a crash: unwinding kills only the tunnel thread,
  leaving `VpnService` holding a TUN that nothing services, so every app on the device loses
  connectivity with no error anywhere. Aborting takes the process down and `START_STICKY`
  brings it back. Consequence: no `catch_unwind` anywhere in the core will work.

> cargo-ndk's panic handler dumps the entire process environment to stdout. Do not run it from a
> shell holding live credentials.
