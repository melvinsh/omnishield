# Security policy

## Reporting a vulnerability

Use GitHub's private vulnerability reporting: **Security → Report a vulnerability** on
[the repository](https://github.com/melvinsh/omnishield). That opens a channel only the
maintainers can see. Please do not open a public issue for anything exploitable.

Include what you did, what happened, the Android version, and whether the tunnel was running.
A logcat excerpt helps; scrub the hostnames in it if they are yours.

Expect an acknowledgement within a week. There is no bounty, and no fixed disclosure deadline
beyond a preference for coordinating one that gives users time to update.

## Supported versions

The latest release. This is a single-maintainer project and there are no backport branches.

## What is in scope

- The packet path: `core/src/packet.rs`, `core/src/runtime.rs`, `core/src/tun.rs`. A crafted
  packet that escapes filtering, corrupts state, or reaches memory it should not.
- DNS: `core/src/dns.rs`, `core/src/doh.rs`, `core/src/dns_cache.rs`. Response spoofing,
  cache poisoning, or a query leaking in cleartext when DoH is on.
- The certificate authority and interception: `core/src/ca.rs`, `core/src/mitm.rs`.
- The JNI boundary: `core/src/android.rs`, `core/src/jvm.rs`, and the keep rules in
  `app/proguard-rules.pro`.
- The firewall: a way for a blocked app to reach the network anyway.

## What is known, and by design

None of these are vulnerabilities. They are properties of the design, stated here so a report
about one gets a fast answer rather than silence.

The CA private key is stored unencrypted in app-private storage, generated on the device on first
use and written to the app's files directory (`core/src/ca.rs:47-49`). Three things bound the
exposure. It is generated per install, so it is useless against any other user and no shared key
ships in the APK. It has no power at all until the user manually installs the certificate, and
even then Android 7 and later confines user-installed CAs to apps that opt in. And
`android:allowBackup="false"`, with matching `dataExtractionRules`, keeps it out of cloud backups
and device transfers. On a rooted or physically compromised device it is readable, and nothing
available to a non-root app changes that.

HTTPS interception is off by default and opt-in per app. Nothing is decrypted until the user
enables the master switch and then picks an app. An app that rejects the certificate is recorded
and permanently bypassed rather than left broken.

The local network is deliberately not routed into the tunnel, so LAN traffic is unfiltered. That
is a trade made to keep inbound connections working, and the reasoning is in
[docs/architecture.md](docs/architecture.md#the-tunnel-does-not-claim-the-local-network).

`QUERY_ALL_PACKAGES` is held so the per-app firewall can map UIDs to package names and icons. It
is used for nothing else, and the app is sideload-only.

`panic = "abort"` takes the whole process down. A panic in the core is a crash, not a degraded
tunnel, and `START_STICKY` restarts it. The alternative leaves `VpnService` holding a TUN that
nothing services, which costs every app on the device its connectivity with no error anywhere. A
reproducible panic is still a bug, so please report it.

## What the app does not do

No account, no server, no analytics, no crash reporting. Nothing about a user's traffic leaves
their device. The only network requests OmniShield makes on its own behalf are the blocklist
downloads and DNS. Release builds trust only system CAs for those
(`app/src/main/res/xml/network_security_config.xml`), so OmniShield's own connections cannot be
intercepted with a certificate a user was talked into installing.
