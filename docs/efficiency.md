# Efficiency

The app was built for correctness first, and it showed. With zero traffic and the screen off, the
process still woke roughly seven times a second, forever, to discover that nothing had changed.
Five of those wakes were the Rust tunnel loop, whose `poll_delay` was clamped to 200 ms even when
smoltcp reported nothing pending. Two were a fixed-rate Kotlin drain that ran regardless of screen
state, UI binding, or whether filtering was even paused.

All numbers below were measured on `omnishield-34`: same device, same procedure, `/proc/<pid>/stat`
deltas over a 300 s window and `dumpsys meminfo`. Battery is not measurable on QEMU, so CPU time
is the proxy, and it is also the quantity that predicts battery.

| | Before | After | |
|---|---|---|---|
| Idle CPU, screen on | 12.4 s/hour | **3.1 s/hour** | 4× less |
| Idle CPU, screen off | 13.2 s/hour | **0.4 s/hour** | 33× less |
| Native heap, startup | 57.6 MB | **32.0 MB** | −44% |
| TOTAL PSS, startup | 158.2 MB | **132.5 MB** | −16% |
| Native heap, steady | 36.4 MB | **31.8 MB** | −13% |
| TOTAL PSS, steady | 138.5 MB | **131.2 MB** | −5% |
| Tunnel start to filters ready | 3,839 ms | **45 ms** warm / 359 ms cold | 85× warm |
| Release APK | n/a (R8 was off) | **8.5 MB** | |

The after column is one run of the final build. A second run of an intermediate build agreed
within noise on everything except screen-on idle, which came out at 2.6 s/hour, so treat that row
as roughly 3 s/hour rather than a precise figure. The screen-off row was 0.4 s/hour both times.

Before the pass, idle cost was *higher* with the screen off than on. The tunnel's overhead was
entirely independent of whether anyone was looking at it, which is the shape that defeats Doze.
It is now close to free when nothing is happening.

## What changed

### The loop sleeps

`runtime.rs` trusts smoltcp's `poll_delay` instead of capping the sleep at 200 ms, with a 30 s
backstop. That makes every off-thread event responsible for announcing itself, so
`core/src/wake.rs` adds a self-pipe written by `Runtime::stop`, by the JNI config setters, and by
the DoH worker, whose answers arrive on a channel the loop only drains when awake and would
otherwise leave unread. A producer that forgets to wake looks like a hang, not like slowness.

### The drain adapts

`PollSchedule` replaces the fixed 500 ms tick. It doubles while the core reports nothing, caps at
2 s with a screen up and 30 s without, and snaps back the moment anything arrives. A drain
returning 75% or more of the core's 2000-entry ring polls *below* the floor, because an overflow
would silently discard log rows, which would make this a functional regression rather than a
cheaper build.

### Filters are built once and cached

`nativeLoadFilters` only stages; `nativeCommitFilters` builds the index and writes `filters.bin`
plus a serialized `adblock` engine, keyed by a string Kotlin derives from the name, size and mtime
of each list file. Validating that key costs a few `stat` calls instead of re-reading 13 MB, and a
warm start never opens the lists at all.

The start path also used to re-download all three DNS lists on every single connect and feed them
straight back in: 13 MB over the network per tunnel start, on top of four full index rebuilds. The
scheduled `FilterRefreshWorker` owns refreshing now, and a start only loads.

### R8 actually runs

`isMinifyEnabled` was `false`, which made the `proguardFiles` entries inert. The keep rules for
the JNI seam existed but had never been exercised. Release builds are minified and
resource-shrunk, and signed with the debug key so the output is installable and therefore
testable. [Verification](verification.md#no-functional-regression-after-the-efficiency-pass) has
the on-device evidence that the keep rules hold.

### Smaller changes, on hot paths

DNS answers are cached with their own TTL (`dns_cache.rs`) and flushed on any rule or config
change, so a page touching 30 hosts no longer costs 90 upstream round trips and 90 radio wakeups.
Packet buffers are recycled through a pool rather than allocated and zeroed per packet. `POLLIN`
is no longer requested for a connection whose `from_remote` is already backed up, which removed a
busy-wait. smoltcp ring buffers went from 64 KiB to 16 KiB each, since they only span the
app-to-tunnel hop where the round trip is microseconds and the bandwidth-delay product is tiny;
that drops the worst case at `MAX_CONNECTIONS` from 64 MiB to 16 MiB. Non-HTML MITM responses,
meaning every image, script and font, stopped being cloned in full just to be written out.

## What this did not fix, and why

Every one of these looks like obvious waste and is not. Check here before "fixing" one of them.

The blob is read, not `mmap`ed. The cache removes the parse and the 37 MB transient startup
spike, but the 10 MB domain blob is still dirty anonymous heap at steady state. Mapping it would
make it evictable, file-backed memory, and it needs `DomainSet::blob` to stop being a `Vec<u8>`,
which is a larger change than this pass warranted.

HTML is parsed twice per rewritten page. `inject_css` writes into `<head>`, which a streaming
rewriter emits long before it has seen the class attributes further down the document. The real
defect there was a misplaced size guard, where a 4 MB page was fully parsed and had its selectors
computed before being rejected, and that is fixed.

adblock's `single-thread` feature was tried and reverted. It swaps `Arc` for `Rc` inside the
engine, making it `!Send`, and `Shared` is held in an `Arc` shared with the tunnel thread.

Sockets are still allocated at SYN, before the firewall check. Allocating afterwards would
mean dropping the SYN for a blocked app, and a dropped SYN is a connect timeout where the current
behaviour is an immediate reset. Trading 32 KiB for a user-visible hang is a bad deal. Shrinking
the ring got most of the benefit without touching the semantics.

DNS attribution is still one JNI call per query. Attribution is cached per UDP session, but
every app on the device shares one source address across the TUN, so the only discriminator is the
ephemeral port, and a resolver that opens a fresh socket per lookup gets no reuse. The saving is
real where sockets are reused and zero where they are not.

`SettingsViewModel.settingsOrNull` is deliberately still `SharingStarted.Eagerly`. It is the
only eager collector left and stands out in an audit, but it exists so the first value is ready
before the first composition. Making it lazy trades a visible onboarding flash for no background
saving at all, since the activity owns it.

## Constraints for later work

Anything touching the UI or the packet path should leave these intact:

1. `MorphShape` in `DashboardScreen.kt` stays a `data class`. As a plain class, every
   recomposition defeats `Modifier.clip`'s outline cache.
2. No new eager collectors beyond the documented one above.
3. No binder calls in composable bodies. `isIgnoringBatteryOptimizations` is read on lifecycle
   resume, not per recomposition.
4. `TunnelRepository.setUiVisible` drives the core's drain cadence, so the activity's
   `onStart`/`onStop` plumbing has to stay wired.
5. No `liveLog`-shaped convenience field on `TunnelRepository`. The log screen reads Room.
