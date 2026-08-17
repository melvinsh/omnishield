# Changelog

Follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

The release workflow reads the section matching the tag and uses it as the release notes, so the
`## [x.y.z]` headings have to keep their shape.

## [Unreleased]

## [0.3.1] - 2026-08-17

### Fixed

- The HTTPS-interception opt-in list only showed the default browser. It now lists every
  installed browser, so a second browser can be opted in without first making it the default.
- When a browser is bypassed for not trusting the certificate, the row now says so clearly —
  that DNS filtering still applies, and that some browsers (Firefox) keep their own certificate
  store and can't be intercepted at all.

## [0.3.0] - 2026-08-17

### Added

- **Tablet and foldable layout.** On a wide screen the navigation moves to a rail down the side
  instead of a bar along the bottom, and the screens reflow into the space. Phones are unchanged.

### Changed

- A pass over the interface for Material 3 Expressive: rounder, softer container shapes; a wavy
  progress indicator while lists refresh; connected pause-duration buttons; a light press response
  on tappable rows; and animated transitions between tabs. Nothing moved — the same five tabs and
  controls, dressed in the newer design language.

### Fixed

- **HTTPS interception certificate could not be installed.** Since Android 11 an app can no
  longer hand the system a CA certificate to install, so the old "Install certificate" button
  silently did nothing. It now saves the certificate to a file you choose and walks you through
  importing it by hand in Settings, with the Samsung path called out.
- **Refreshing the filter lists could silently skip one.** A list served by a host that was
  slow or rate-limiting was reported as a success alongside the others while it quietly failed
  to update. Lists now download in parallel, a slow or unreachable host is bounded to a short
  reachability timeout instead of hanging the refresh, and each list reports its own state —
  downloading, updated, or couldn't reach — with any failure named.

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

[Unreleased]: https://github.com/melvinsh/omnishield/compare/v0.3.1...HEAD
[0.3.1]: https://github.com/melvinsh/omnishield/compare/v0.3.0...v0.3.1
[0.3.0]: https://github.com/melvinsh/omnishield/compare/v0.2.2...v0.3.0
[0.2.2]: https://github.com/melvinsh/omnishield/compare/v0.2.1...v0.2.2
[0.2.1]: https://github.com/melvinsh/omnishield/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/melvinsh/omnishield/releases/tag/v0.2.0
