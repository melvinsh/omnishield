# Changelog

Follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

The release workflow reads the section matching the tag and uses it as the release notes, so
the headings here have to keep their shape.

## [Unreleased]

## [0.2.0] - 2026-08-17

First public release.

### If you installed a development build

Uninstall it first. Everything before this was signed with a per-machine debug key, and Android
refuses to upgrade an app whose signature has changed. Installing over it fails with a signature
mismatch, which is a confusing error for what is really just a one-time reset. Your settings and
request history do not survive the uninstall.

From 0.2.0 onward, every published APK is signed with the same release key and upgrades in place.

### Added

- Per-ABI APKs. `arm64-v8a` for phones, `x86_64` for emulators and x86 Chromebooks, plus a
  universal build for anyone who does not want to think about it. The Rust core is 5.5 MB per
  ABI, so splitting saves every user a copy they cannot run.
- Continuous integration: 101 Rust tests, 58 Kotlin unit tests, 36 instrumented tests, Android
  lint, `cargo fmt` and `cargo clippy`, on every push and pull request.
- `LICENSE` (MIT), `NOTICE.md`, `SECURITY.md`, `CONTRIBUTING.md`.
- `android:dataExtractionRules`, so the generated CA private key stays out of cloud backups and
  device-to-device transfers on Android 12 and later as well as on earlier versions.

### Fixed

- The tunnel no longer routes the local network. It used to take `0.0.0.0/0`, which broke every
  inbound LAN connection: file transfers, media servers and `adb connect` to the phone all timed
  out while OmniShield was running, and nothing in the app explained why.
- The Quick Settings tile no longer crashes on Android 14. It called an overload of
  `startActivityAndCollapse` that throws against a `targetSdk` of 34.
- Domain overrides are no longer offered on log rows that name an address rather than a domain.
  Allowing a `tcp` row used to store a rule that could never match while listing it in Settings
  as though it were in force.
- Six clippy findings in the core, including a `usize` cast to `usize` in DNS name decompression
  and four address conversions that converted a type to itself.

### Changed

- Every screen has a title and a line saying what it is for. Every action is acknowledged, every
  destructive one is confirmed, and firewall toggles can be undone.
- The firewall states each app's rule in words, so a switch position is no longer the only thing
  carrying the meaning. The switches block, which is the opposite of the usual reading.
- Filter lists, the DNS resolver and un-pinning an app that rejected the certificate all have
  user interfaces. They existed only as repository methods before.
- Idle CPU with the screen off dropped from 13.2 to 0.4 seconds per hour, and tunnel start with a
  warm filter cache from 3.8 seconds to 45 ms. See
  [docs/efficiency.md](docs/efficiency.md).
- The README is written for someone installing the app. The engineering record moved to `docs/`.

[Unreleased]: https://github.com/melvinsh/omnishield/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/melvinsh/omnishield/releases/tag/v0.2.0
