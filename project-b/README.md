# Project B — PathFinder v0.5: authorized CIDR audit

Fixed scope: **37.32.0.0/19**, all 8,192 IPv4 addresses (37.32.0.0–37.32.31.255). Not a generic Internet-wide scanner.

## How it works

1. On-device, rate-limited TCP connect to user-selected ports across the **entire authorized CIDR**. Default ports: 1080, 10808, 10809, 8080, 3128, 8000, 8888, 4145, 9050, 8118, 80, 443. Maximum 24 unique ports, with 24 worker threads and approximately 33 attempts per second. This may entail many tens of thousands of checks.
2. For open ports, try **SOCKS5**, **SOCKS4a** (remote hostname), **SOCKS4** (IPv4 destination), and **HTTP CONNECT**, independently.
3. Test real HTTPS over the candidate protocol to Telegram API and YouTube. Validate the remote TLS hostname and fetch a response; on YouTube success, also test a thumbnail host. Only endpoints with a successful verified response are listed.
4. Keep a persisted batch-level checkpoint of scanning phase, next index, open ports, and successful proxies. Pause and resume on the same port configuration. Reset clears progress. Duplicate endpoint results are suppressed.
5. Export all verified endpoints and per-service results. An optional experimental Android TUN connector supports verified SOCKS5 endpoints only. SOCKS4(a) and HTTP CONNECT are **scanner/verifier** protocols in this release, not VPN connectors.

No proxy/DNS lists are downloaded at run time. The scanner does **not** use the old public proxy list to define scan scope.

This is an opt-in diagnostic utility for an explicitly authorized address range. It is not evidence that an untested proxy is open, and TLS/HTTP reachability does not imply a Telegram MTProto session or YouTube video playback. Avoid sending sensitive data over unknown proxies.

Development status: APK build can be checked in GitHub Actions; no real phone or Iranian network access has been tested from this environment.

Build with JDK17, Android SDK 35, Gradle 8.10.2:
`gradle -p project-b --no-daemon :app:assembleDebug`.
