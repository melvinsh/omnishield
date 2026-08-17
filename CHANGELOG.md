# Changelog

Follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

The release workflow reads the section matching the tag and uses it as the release notes, so the
`## [x.y.z]` headings have to keep their shape.

## [Unreleased]

## [0.2.1] - 2026-08-17

### Fixed

- A connection whose upstream socket died — a server reset, a carrier NAT reclaim, or the
  socket teardown Android performs on every network change — could pin a full CPU core until
  the tunnel restarted. Upstream read and write errors now tear the connection down, and dead
  connections whose data can no longer be delivered are reaped instead of held forever. This
  was the single largest battery cost on a real device.
- Connections refused by the firewall or that failed to dial leaked 32 KiB each for the life
  of the tunnel, and made every wakeup a little more expensive than the last.
- Long-lived UDP flows were torn down and rebuilt every 30 seconds; the far end saw a new
  source port each time. Sessions now expire on idleness, not on age.
- The event drain no longer resets to its 500 ms floor on every allowed DNS query while the
  screen is off; cadence now follows drain volume, so background traffic no longer keeps the
  service polling at 2 Hz all night.

### Changed

- Blocking QUIC now only applies while HTTPS interception is enabled for at least one app,
  which is the only layer it serves. Everything else keeps HTTP/3.
- The persistent notification is not republished while the screen is off, and is refreshed
  the moment it turns on.
- Release builds log at info level; the debug tier logged per intercepted request and per
  dialled connection.

## [0.2.0] - 2026-08-17

First release.

DNS-level ad and tracker blocking for every app on the device, from a local `VpnService` tunnel
with no root, no account and no server. Roughly 430,000 domains, from StevenBlack, AdGuard DNS and
OISD plus a bundled starter list, refreshed daily over Wi-Fi.

- **Request log:** every lookup and connection the tunnel saw, searchable and filterable, with
  per-domain always-allow and always-block overrides that beat any downloaded rule.
- **Per-app firewall:** cut any installed app off from Wi-Fi, mobile data, or both. Rules apply to
  a tunnel that is already running.
- **Pause:** 5 minutes, 30 minutes or an hour, backed by an alarm so it expires even if the
  process dies. Filtering stops; the tunnel stays up, so there is no reconnect and no second
  consent prompt.
- **DNS over HTTPS**, on by default via Cloudflare, so queries are not readable on the local
  network. The resolver is editable, and a DoH failure falls back to plaintext and says so rather
  than silently downgrading.
- **Optional HTTPS interception** for in-page ad and cosmetic filtering, off by default and opt-in
  per app. Since Android 7 this reaches Chrome-family browsers and little else, which the app says
  plainly.
- **Quick Settings tile**, start on boot, and a battery-optimisation exemption.

Per-ABI APKs for `arm64-v8a` and `x86_64` plus a universal build. Android 10 and later.
Sideloaded, because Play policy forbids apps that block ads in other apps.

[Unreleased]: https://github.com/melvinsh/omnishield/compare/v0.2.1...HEAD
[0.2.1]: https://github.com/melvinsh/omnishield/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/melvinsh/omnishield/releases/tag/v0.2.0
