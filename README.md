# OmniShield

An ad and tracker blocker for Android that covers every app on the phone, not just the browser.
No root. No account. No server: the filtering happens on your device, and nothing about your
traffic is sent anywhere.

Requires Android 10 or newer. Current version 0.1.0.

## How it works

OmniShield runs a VPN tunnel that both ends on your phone, and answers DNS itself. When an app
looks up a known ad or tracking domain, the lookup is refused and the connection is never made.
About 430,000 domains are covered, from StevenBlack, AdGuard DNS and OISD plus a small bundled
list. They refresh once a day, on Wi-Fi only.

Two things sit on top of that. A per-app firewall cuts any installed app off from the network, on
Wi-Fi, on mobile data, or both. And in browsers you opt in one at a time, OmniShield can strip ads
out of the page itself; that part is off by default, and [What it cannot do](#what-it-cannot-do)
explains why it reaches so few apps.

## Installing

Google Play does not allow apps that block ads in other apps, so OmniShield is installed from an
APK file rather than a store.

1. Copy `app-release.apk` to the phone and open it.
2. Android will ask whether to allow installs from whichever app you opened it with. Say yes.
3. Open OmniShield and tap Connect.

Android shows a VPN consent dialog the first time. That prompt comes from Android, not from
OmniShield, and it is the only way an app is allowed to see network traffic. There is no remote
server on the other end of it.

## Using it

Five tabs along the bottom:

| Tab | What it is for |
|---|---|
| **Shield** | Connect and disconnect, how much has been blocked, and pause for 5 minutes, 30 minutes or an hour |
| **Log** | Every lookup and connection the tunnel saw. Tap a row to always allow or always block that domain |
| **Firewall** | Cut an app off from the network. A switch turned **on** means blocked, and each row says its rule in words |
| **Web** | In-page ad removal for browsers you opt in. Off by default |
| **Settings** | Filter lists, DNS resolver, start on boot, battery exemption, your saved domain overrides |

If a site breaks, open the Log, find the domain, and tap **Always allow**. Your choice beats any
downloaded rule for that domain, and it survives list updates. Everything you have overridden is
listed in Settings, where you can undo it.

Pause is usually better than disconnecting. It turns filtering off but keeps the tunnel up, so
there is no reconnect and no second consent prompt, and it switches itself back on when the timer
runs out.

## What it cannot do

Some of this is Android and some of it is the nature of DNS blocking. Read it before deciding the
app is broken.

Ads inside apps stay. Filtering an app's HTTPS traffic means decrypting it, and since Android 7
an app ignores certificates you installed unless it deliberately opts in. Chrome-family browsers
do; almost nothing else does. The Web tab says plainly which apps it can reach.

Ads served from the content's own domain stay. YouTube pre-rolls and the ads in the Instagram and
Facebook feeds arrive from the same hostnames as the posts and videos around them. Blocking the
domain would block the app.

Your local network is not filtered. Printers, media servers, file transfers and anything else on
your own Wi-Fi are left alone, which is what keeps them working while the tunnel is up. Ad and
tracker domains do not live there.

`ping` does not work while the tunnel is on. Ordinary browsing and apps are unaffected.

## Privacy

Nothing about your traffic leaves the phone. There is no account, no analytics, no crash
reporting and no server anywhere in the app.

The blocklists are downloaded from their publishers over HTTPS, and that is the only network
request OmniShield makes for itself. DNS queries go to Cloudflare over HTTPS by default, so
whoever runs your network cannot read them; you can point that at a different resolver in
Settings, or turn it off.

The request log is stored on the phone and pruned automatically. You can wipe it at any time from
Settings.

## Battery

Sitting idle with the screen off, OmniShield costs about 0.4 seconds of CPU per hour. It sleeps
when nothing is happening rather than waking on a timer.

If filtering stops on its own after a while, Android has suspended the service. Settings has a
one-tap battery exemption that prevents it.

## Building from source

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:/opt/homebrew/opt/rustup/bin:$PATH"

./gradlew installDebug
```

The Rust core builds automatically as part of that. Full toolchain setup is in
[docs/development.md](docs/development.md).

## Documentation

| Document | Contents |
|---|---|
| [Architecture](docs/architecture.md) | The Kotlin/Rust split, the packet path, the three filtering layers, routing |
| [Development](docs/development.md) | Toolchain setup, emulators, tests, offline probes, version constraints |
| [Verification](docs/verification.md) | What was measured on device, the benchmark result, bugs testing found |
| [Efficiency](docs/efficiency.md) | Idle CPU and memory: what changed, what did not, and why |
| [Interface](docs/interface.md) | Material 3 Expressive, and the pass that made every screen explain itself |
