# Contributing

Bug reports, filter-list problems and patches are all welcome. This is a single-maintainer
project, so reviews are not instant.

## Before you write code

For anything beyond a small fix, open an issue first. The parts of this codebase that look
arbitrary usually are not, and a short conversation saves a rewrite. `docs/` and `CLAUDE.md`
record why a surprising number of things are the way they are, including several that were tried
the obvious way first and reverted.

## Setting up

Full toolchain instructions are in [docs/development.md](docs/development.md). The short version:

```bash
brew install openjdk@17 rustup gradle
brew install --cask android-commandlinetools
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk
sdkmanager "platform-tools" "platforms;android-37.0" "build-tools;37.0.0" "ndk;27.3.13750724"

./gradlew installDebug     # the Rust core builds automatically
```

## Running the tests

All three suites have to pass, and CI runs all three:

```bash
cd core && cargo test                  # 101 tests, no emulator
./gradlew testDebugUnitTest            # 58 tests (Robolectric)
./gradlew connectedDebugAndroidTest    # 36 tests, needs an emulator
./gradlew lint                         # not optional, see below
cd core && cargo fmt --check && cargo clippy --all-targets -- -D warnings
```

Lint fails the build, and it earns that. It caught `BigInteger.TWO` being an API 33 field against a
`minSdk` of 29, which would have killed the tunnel on Android 10 through 12. Every unit test
passed, because they run on a desktop JVM where the field exists. If lint and the tests disagree
about whether your change is safe, lint is probably right.

## Things that will waste your afternoon

Four couplings in this codebase fail at runtime with no compile error, usually presenting as
"nothing happens". They are the reason to read
[docs/architecture.md](docs/architecture.md#four-couplings-that-fail-silently) before touching
the JNI seam: the exported Rust symbol names, the reverse callbacks named by JNI signature
string, the serde field names in the config JSON, and the requirement that anything changing
state off-thread wakes the tunnel loop. That last one presents as a hang rather than as silence.

Two smaller traps:

- **cargo-ndk's `-P` is capital.** Lowercase `-p` means `--package` in 4.x and fails with
  `unknown package: 29`.
- **`dnstest`'s first argument is the hostnames to test**, not a blocklist. Pass a blocklist
  there and it reports `blocked 0/N`, which looks exactly like a catastrophic filtering
  regression.

## Filter list problems

If a site is broken, that is usually a rule problem rather than a code problem, and the app can
fix it without a release: open the Log, find the domain, and tap **Always allow**. Please still
report it. A rule that breaks a mainstream site is something to chase upstream. Use the "site or app
is broken" issue template.

The blocklists themselves are third-party and downloaded at runtime; a wrong entry in one is best
reported upstream to whoever publishes it. See [NOTICE.md](NOTICE.md) for who that is.

## Pull requests

- One concern per pull request.
- Say which suites you ran. If you could not run the instrumented ones, say that instead of
  leaving it ambiguous; CI will run them.
- Match the surrounding style. Comments here explain *why*, not what, and there are a lot of
  them on purpose.
- Rust is formatted with `cargo fmt` and has to be clippy-clean under `-D warnings`.
- Do not add a dependency without saying what it replaces. The core has a deliberately small
  tree, and a GPL crate entering it would conflict with the licence on the app.

## Licence

Contributions are under the MIT licence, the same as the project. There is no CLA to sign.
Opening a pull request is taken as agreement that your contribution can be released under it.

## Reporting security issues

Not here. See [SECURITY.md](SECURITY.md) for private reporting.

## Code of conduct

[Contributor Covenant](CODE_OF_CONDUCT.md).
