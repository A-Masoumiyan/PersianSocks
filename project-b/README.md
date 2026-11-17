# Project B — PathFinder v0.2

Experimental Android diagnostic + verified SOCKS5 VPN.

- Retains all v0.1 offline diagnostics (network DNS resolvers, TCP IPv4/IPv6, HTTPS response checks).
- New real connection flow: up to 8 user-supplied authorized SOCKS5 relay endpoints, manual entry; verify SOCKS5 CONNECT to www.example.com:443, verify TLS hostname and HTTP response, request Android VPN permission, start TUN via upstream pinned hev-socks5-tunnel AAR.
- Native tunnel excludes the upstream IPv4 address from VPN routes to avoid traffic loops.
- The VPN tunnel starts only for a cooperating, verified proxy. A reachable public DNS or HTTP server is not enough and cannot be used as arbitrary proxy.
- Explicitly not a SlipNet or DNS tunnel implementation yet. The user needs a genuine authorized SOCKS5 relay, not just an accessible IP.
- UDP/DNS availability depends on SOCKS5 UDP ASSOCIATE at the remote server. Some apps may not work without it.
- Plain SOCKS5 is not encrypted by itself. Do not use a proxy of unknown provenance and do not share credentials. Debug build, not tested on physical Android device.

Build: gradle -p project-b :app:assembleDebug
