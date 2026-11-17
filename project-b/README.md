# Project B — PathFinder v0.3

This is an experimental Android app for **offline-input route diagnostics and cautious tunnel testing**.

## What changes in v0.3
- Main UI now offers an automatic connect entry, not an empty SOCKS5 field.
- APK bundles a snapshot of proxy candidates from this repository as a **build-time static asset**. It makes no requests to fetch lists at runtime.
- Bounded concurrency (20) and at most 240 shuffled candidates per scan, validating an actual SOCKS5 CONNECT and HTTPS TLS to a different domain, not merely port-open checks.
- If a legitimate compatible SOCKS5 relay responds, the client asks for Android VPN permission, then starts the native `hev-socks5-tunnel` TUN.
- Optional diagnostic "direct VPN plumbing test" uses an authenticated loopback SOCKS bridge whose physical sockets are protected with VpnService.protect(). This **does not bypass filtering**; it only proves Android VPN packet handling on currently working Internet, and may not cover UDP apps.
- Manual authorized SOCKS5 configuration is optional and collapsed by default.
- Stops or fails without displaying a fake connected success.

## Security and accuracy
- Public proxies are untrusted: they may log traffic and can observe unencrypted payloads and metadata. User explicit consent is required before scanning those candidates.
- The offline proxy snapshot can go stale quickly; no success is guaranteed now or during a blackout.
- This is NOT an implementation of SlipNet, DNSTT, whitelisted-network discovery, arbitrary HTTPS proxying, or access through government servers. It does not circumvent a zero-egress shutdown.
- A browser returning data through the tunnel must be checked on a device before considering the VPN functional.
- Native engine upstream socks traffic may support TCP while UDP-dependent apps fail.
- No real-device acceptance test is yet completed.
- This is a debug signed build, for Android 10+.

## Build
```sh
gradle -p project-b --no-daemon :app:assembleDebug
```

The build copies the offline list from the repository's existing Android bootstrap assets.
