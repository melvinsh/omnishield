# Notices and attribution

OmniShield itself is MIT licensed; see [LICENSE](LICENSE). This file covers everything in the
repo or the running app that someone else wrote.

## Filter lists

The distinction that matters here is between bundling a list and fetching one.

Only one list is bundled: `app/src/main/assets/filters/default.txt`, 174 lines of original
curation, kept small on purpose so filtering works offline on first launch before anything has
been downloaded. It is covered by this project's MIT licence.

Everything else is downloaded from its publisher on the user's device and never redistributed by
this project, so each list stays under its own terms. The sources are declared in
`app/src/main/kotlin/io/omnishield/data/FilterRepository.kt`:

| List | Publisher | Terms |
|---|---|---|
| [StevenBlack/hosts](https://github.com/StevenBlack/hosts) | Steven Black | MIT |
| [AdGuard DNS filter](https://github.com/AdguardTeam/AdGuardSDNSFilter) | AdGuard | GPL-3.0 |
| [OISD Big](https://oisd.nl/) | oisd.nl | See oisd.nl |
| [EasyList](https://easylist.to/) | The EasyList authors | GPL-3.0 and CC BY-SA 3.0 |
| [EasyPrivacy](https://easylist.to/) | The EasyList authors | GPL-3.0 and CC BY-SA 3.0 |

Shipping EasyList inside an MIT-licensed APK would be a licence conflict. Downloading it on the
device is not, which is why the app works the way it does.

## Rust dependencies

Checked across all 180 entries in `core/Cargo.lock`. **No GPL-licensed crate is in the tree.**
The overwhelming majority are MIT or Apache-2.0. The ones with terms worth naming individually:

| Crate | Licence | Why it is here |
|---|---|---|
| [adblock](https://github.com/brave/adblock-rust) | MPL-2.0 | Brave's ABP engine, for Layer 3 network and cosmetic rules |
| `cssparser`, `cssparser-macros`, `selectors`, `dtoa-short` | MPL-2.0 | Mozilla CSS crates, pulled in by `adblock` |
| [lol_html](https://github.com/cloudflare/lol-html) | BSD-3-Clause | Cloudflare's streaming HTML rewriter |
| [smoltcp](https://github.com/smoltcp-rs/smoltcp) | 0BSD | The userspace TCP/IP stack behind the tunnel |
| [rustls](https://github.com/rustls/rustls) | Apache-2.0 or ISC or MIT | TLS for DoH and for the interception layer |
| [ring](https://github.com/briansmith/ring) | Apache-2.0 and ISC | rustls' crypto backend |
| [webpki-roots](https://github.com/rustls/webpki-roots) | CDLA-Permissive-2.0 | Mozilla's root CA set, for verifying upstream servers |

MPL-2.0 is file-level copyleft: it obliges anyone who modifies those crates' own files to
publish the changes, and places no obligation on a larger work that merely links them. OmniShield
does not modify them.

## Android dependencies

AndroidX, Jetpack Compose, Material Components, Room, WorkManager and DataStore are Apache-2.0,
from the Android Open Source Project and Google. Test dependencies (JUnit, Robolectric, Espresso,
Turbine) are EPL-1.0, MIT or Apache-2.0 and are not part of the shipped APK.

## Regenerating this list

```bash
cd core && cargo tree --format '{p} {l}'
```

Anyone changing `core/Cargo.toml` should re-check for a GPL crate entering the tree, since that
would conflict with the MIT licence on the app as a whole.
