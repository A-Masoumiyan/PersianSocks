# Project B / PathFinder v0.4

**Iranian-IP public proxy verifier**.

## Goal
Show proxies *hosted on Iranian IPv4 addresses* that can reach Telegram API or YouTube **through the proxy**. No successful foreign-IP proxy belongs in results.

## Implementation
- Bundled offline country-range index (`ip-country.bin`) filters candidates to exact Iranian source IPv4 before making network probes.
- Bundled public-source candidates: SOCKS5, HTTP CONNECT and a small additionally curated list with source comments. No runtime download of proxy or DNS lists.
- Each candidate is tested through a genuine CONNECT tunnel followed by hostname-verified TLS and HTTP response from **api.telegram.org** and **www.youtube.com** separately. For YouTube positives, an additional image CDN check is attempted.
- Results list **all verified candidates in the bundled list**, deduplicated and sorted, with protocol, IP, port, measured time and separate flags for each service.
- The results can be copied or shared. If a qualifying SOCKS5 server exists, the UI optionally permits a real TUN-based VPN attempt after consent.
- No random probing of every Iranian IP address and no unauthorized open-relay or government server exploitation.
- HTTP CONNECT candidates are tested and shown but the current TUN connector only accepts SOCKS5.
- The test indicates HTTPS reachability of a service endpoint, *not* guarantee that Telegram MTProto works or a YouTube video actually plays.
- This is a debug APK; neither physical-device success nor network-through-filtering behavior is verified.

## Snapshot limits
An offline proxy list will go stale after installation. It cannot capture **all** Iranian IPs or all unpublished / non-public proxies. The GeoIP dataset is an approximate location assignment and may not reflect actual physical hosting; a proxy can forward to other relays.

## Build
`gradle -p project-b :app:assembleDebug`, JDK 17, Android SDK 35, Gradle 8.10.2.

Source lists from the existing repository's bootstrap assets are copied into APK at build time; no downloads at runtime. Country data from the same existing app is included.
