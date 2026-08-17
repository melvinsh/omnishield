# Development

Set up on macOS/Apple Silicon. Read [Version constraints](#version-constraints) before changing
any of the versions below.

## Toolchain

```bash
brew install openjdk@17      # the formula, not the temurin cask, which needs sudo
brew install --cask android-commandlinetools
brew install rustup gradle

rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk

sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-37.0" "platforms;android-34" \
           "build-tools;37.0.0" "ndk;27.3.13750724" "emulator" \
           "system-images;android-34;google_apis;arm64-v8a" \
           "system-images;android-33;google_apis;arm64-v8a"
```

Nothing is on the default PATH: `openjdk@17` is keg-only, and rustup's shims live in
`/opt/homebrew/opt/rustup/bin`, not in `~/.cargo/bin`, which holds only cargo-installed binaries
like `cargo-ndk`.

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:/opt/homebrew/opt/rustup/bin:$HOME/.cargo/bin:$PATH"
```

Gradle resolves `cargo` itself through `rustToolDirs` in `app/build.gradle.kts`, so builds work
without those exports. The Rust commands below do not.

> cargo-ndk's panic handler dumps the entire process environment to stdout, including anything
> secret you have exported. Do not run it from a shell holding live credentials.

## Build

```bash
./gradlew assembleDebug        # cargoNdkBuild runs automatically via preBuild
./gradlew installDebug
```

Building the Rust core alone has to run from `core/`, because cargo-ndk invokes `cargo metadata`
in the working directory and `--manifest-path` alone is not enough. Note the capital `-P` for the
API level; lowercase `-p` means `--package` in cargo-ndk 4.x and fails with `unknown package: 29`:

```bash
cd core && cargo ndk -t arm64-v8a -t x86_64 -P 29 -o ../app/src/main/jniLibs build --release
```

## Tests

All three suites have to pass.

```bash
cd core && cargo test                      # 101 host-native tests, no emulator
./gradlew testDebugUnitTest                # 58 Kotlin unit tests (Robolectric)
./gradlew connectedDebugAndroidTest        # 36 Room + Compose UI tests, needs an emulator
```

Single tests:

```bash
cd core && cargo test filter::tests::www_rule_does_not_widen_to_apex
./gradlew testDebugUnitTest --tests "io.omnishield.data.CoreJsonTest"
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.omnishield.data.db.DatabaseTest
```

The desktop JVM is not the device. `TunnelRoutesTest` passed against `BigInteger.TWO`, an API 33
field on a `minSdk` of 29, because the field exists on the host. Lint is what catches that class
of bug, so lint gates the build.

## Emulators

```bash
$ANDROID_HOME/emulator/emulator -avd omnishield-34 -no-snapshot -no-boot-anim   # default target
$ANDROID_HOME/emulator/emulator -avd omnishield-33 -no-snapshot -writable-system
```

Two images, because Android 14 moved the system trust store to
`/apex/com.android.conscrypt/cacerts`, which is immutable and cannot be remounted even as root.
On API 33, `-writable-system` plus a push to `/system/etc/security/cacerts` still works, and that
is the only practical way to test HTTPS interception against all apps rather than only Chrome.

Both images are `google_apis`, deliberately not `google_apis_playstore`: Play Store images block
`adb root` and `-writable-system`.

```bash
# Skip the VPN consent dialog when scripting
adb shell appops set io.omnishield ACTIVATE_VPN allow

# Install the generated CA into the user trust store, which Chrome honours
adb root
adb shell cat /data/data/io.omnishield/files/ca/omnishield-ca.pem > ca.pem
HASH=$(openssl x509 -inform PEM -subject_hash_old -noout -in ca.pem)
adb push ca.pem /data/misc/user/0/cacerts-added/$HASH.0
adb shell chmod 644 /data/misc/user/0/cacerts-added/$HASH.0

# Foreground service type check
adb shell dumpsys activity services io.omnishield | grep -E "isForeground|types"
# expect: isForeground=true ... types=40000000   (FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
```

There is no `nslookup` or `curl` on these images. Use `ping` for DNS resolution and `nc` for TCP:

```bash
adb shell "printf 'GET / HTTP/1.0\r\nHost: example.com\r\nConnection: close\r\n\r\n' \
  | nc -w 6 example.com 80 | head -1"
```

## Offline probes

Two probes reason about filter behaviour without a device. Use them instead of guessing whether a
miss is a rule gap or a code bug.

```bash
cd core
cargo run --release --example dnstest  -- probe-hosts.txt <list.txt> [more.txt ...]
cargo run --release --example ruletest -- easylist.txt easyprivacy.txt
```

`dnstest`'s first argument is the list of hostnames to **test**, not a blocklist: one bare
hostname per line. Passing a blocklist there reports `blocked 0/N` and looks exactly like a
catastrophic filtering regression. Everything after the first argument is a blocklist.

Run it twice, once with hostnames that must be blocked and once with legitimate ones that must
not. Over-blocking is the failure mode a single probe will not show you.

Use `--release`. A debug build parses 13 MB of lists slowly enough to look hung.

The lists themselves are gitignored. Pull the real ones off a device that has run once, or unzip
the bundled one:

```bash
adb shell "run-as io.omnishield cat files/filters/stevenblack-hosts.txt" > stevenblack-hosts.txt
unzip -p app/build/outputs/apk/debug/app-debug.apk assets/filters/default.txt > bundled.txt
```

`core/probe-*.txt` are checked-in fixtures and should stay that way.

## Measuring cost

CPU time is the battery proxy, since battery itself is not measurable on QEMU. Compare
`/proc/<pid>/stat` utime+stime deltas over a fixed window with the screen both on and off, plus
`dumpsys meminfo io.omnishield` at startup and again 60 s later. The startup peak and the steady
state are different numbers, and the peak is the one that caching the filters addresses.

[Efficiency](efficiency.md) has the measured before and after figures, and the things that look
like obvious waste but are not.

## Continuous integration

`.github/workflows/ci.yml` runs on every push to `master` and every pull request, in three jobs
on `ubuntu-latest`:

| Job | What it runs |
|---|---|
| `rust` | `cargo fmt --check`, `cargo clippy --all-targets -- -D warnings`, `cargo test` |
| `android` | Gradle wrapper validation, then `testDebugUnitTest lint assembleDebug`; uploads the lint SARIF to code scanning and the debug APKs as artifacts |
| `instrumented` | `connectedDebugAndroidTest` on an API 34 x86_64 emulator |

The `x86_64` ABI exists largely so that last job can run at all: GitHub's Linux runners are
x86_64 and cannot host an arm64 emulator. Running it on macOS arm64 runners instead was the
alternative, and shipping a second ABI is more useful than a faster CI job.

Two things CI cannot check, so neither has a gate and both need a device:

- **The LAN routing behaviour:** QEMU's NAT has no real LAN peer to connect inward from, which
  is exactly the direction the bug in [architecture.md](architecture.md#the-tunnel-does-not-claim-the-local-network)
  broke.
- **Idle CPU:** measure it by hand, per [Measuring cost](#measuring-cost), when changing anything
  on the poll or drain path.

The compiler is pinned in `core/rust-toolchain.toml`, so `rustup show` in that directory installs
the right version and both Android targets in one step. That is what CI does.

## Releases

Pushing a `v*` tag runs `.github/workflows/release.yml`: it re-runs the tests, builds
`assembleRelease` with a keystore decoded from repository secrets, renames the split outputs to
`omnishield-<version>-<abi>.apk`, writes `SHA256SUMS`, and publishes a GitHub Release using the
matching `CHANGELOG.md` section as the body. `versionName` comes from the tag and `versionCode`
from the tag count, so it increases monotonically without any state to keep.

Four secrets are needed: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
The workflow fails rather than falling back if the first is missing, because a debug-signed
release can never be upgraded.

Locally, with none of those in the environment, `./gradlew assembleRelease` still signs with the
debug key. That fallback is deliberate: an unsigned release APK cannot be installed, which would
leave R8's output untestable on a device, and a broken keep rule produces no build error. Do not
publish anything built that way.

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
| `android.ndkDirectory` unavailable | Removed in AGP 9; the NDK path comes from `local.properties`/`ANDROID_HOME`. |
| rustls/rcgen use the **`ring`** backend | `aws-lc-rs` needs a full CMake/Go toolchain to cross-compile. |
| material3 pinned to **1.5.0-alpha** | Ahead of the BOM, for the Expressive component set. The rest of Compose stays stable. See [Interface](interface.md). |
| `core/Cargo.toml` sets **`panic = "abort"`** | A panic cannot usefully unwind across the JNI boundary, and unwinding is worse than a crash: it kills only the tunnel thread, leaving `VpnService` holding a TUN that nothing services, so every app on the device loses connectivity with no error anywhere. Aborting takes the process down and `START_STICKY` brings it back. No `catch_unwind` in the core will work. |

## Repository

`master` on `github.com/melvinsh/omnishield`. Build outputs, `core/target/`,
`app/src/main/jniLibs/` and the 13 MB of downloaded blocklists are gitignored.
