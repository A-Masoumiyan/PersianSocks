# Project B / PathFinder v0.1

Independent Android diagnostic prototype. Package: ir.pathfinder.b

## What it DOES
- Runs entirely with bundled target list (no remote configuration, account, or telemetry).
- Reads active connection's assigned DNS resolvers and available transport.
- Sends small DNS A/TXT/nonce queries directly to the configured resolvers, distinguishes response/no-response and RCODE. A response is **not** proof of international reachability.
- Tests direct IPv4 and IPv6 TCP 443 connectivity to fixed, public IPs.
- Validates HTTPS connections to known public endpoints using the system TLS verifier, checks HTTP status, and reads at most 128 bytes.
- Stores scan history locally and offers report export through Android share intent.
- Uses bounded socket timeouts and only well-known public endpoints.

## What it does NOT
- No VPN tunnel, no traffic forwarding, no circumvention claim, no probing government/private endpoints, no mass-IP scanning.
- Does not guarantee an endpoint is a relay or can be used as a proxy.
- Cannot discover every possible whitelist destination without information or a probing range.

## Compile
Gradle 8.10.2, Android Gradle Plugin 8.7.3, JDK 17, SDK 35.

gradle -p project-b :app:assembleDebug

Build result: project-b/app/build/outputs/apk/debug/app-debug.apk

Debug APK has a developer/debug signing key and will not upgrade any existing app. 
