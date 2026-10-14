# Network services evaluation — 2026-10-14

Baseline: signed Android 0.28.7/code37, hev-socks5-tunnel 2.18.0. PR #23 is merged; GitHub v0.28.7 and the existing Drive APK have the same SHA-256. The public release feed passed 31 update-selection checks using the application's actual selector. No service below is implemented or benchmarked on a phone by this research.

## Candidate decisions

| Candidate | Integration evidence | Decision for this application |
|---|---|---|
| Speedify SDK | Vendor supplies Android SDK and a client/server packet-bonding service | Genuine commercial candidate; validate architecture and licensing before a prototype |
| Google / Cloudflare / Quad9 DoH | Published DNS-wireformat HTTPS endpoints | Optional measured fallback when there is evidence of a destination DNS problem |
| WARP | Current consumer client uses MASQUE; documented local proxy is desktop-only | Alternate transport, not a demonstrated Android upstream for the existing gateway |
| MASQUE / Hysteria 2 / WireGuard | Standards and libraries need a compatible remote endpoint | Separate experimental transport using an authorized endpoint/account |
| sing-box | URLTest has interval, tolerance and existing-connection interruption controls | Evaluate as an alternate engine; an engine is not a bandwidth service |
| Cronet | App HTTP engine has explicit proxy configuration | Possible source/download HTTP implementation; require exact-proxy fail-closed tests |
| MPTCP / Argo-Spectrum | Remote/kernel support or configured origin infrastructure is required | Lower priority for unknown public SOCKS/HTTP endpoints |

## Important integration constraints

- Speedify Android 17.2.1 documentation requires backend authorization by package and signing certificate, authenticated Maven/AAR access, and compile/target SDK37. This app uses SDK36. Availability, licensing, cost and public-repository compatibility remain unverified. `connectProxy()` is documented as local proxy operation **without a remote server connection**; it is not evidence of bonded-cloud socket transport. Android allows one active VpnService per profile. A separate bonded connection mode is plausible; chaining today's protected upstream sockets behind it needs a documented transport interface and routing tests. Do not assume two VPN services can be stacked.
- Keep remote-proxy DNS as the baseline. A controlled DoH comparison must use the selected proxy, validated provider TLS and original destination TLS/SNI/Host. Key TTL-bounded caches by proxy/physical route/generation. Compare actual destination HTTPS outcomes and CDN selection, not only resolver time. Numeric proxy TCP refusal does not become a DNS failure.
- Ordinary HTTP CONNECT does not become CONNECT-UDP/CONNECT-IP by enabling QUIC in a library. First prove real bidirectional UDP; the current SOCKS UDP flag only records an accepted association. Public SOCKS endpoints cannot be relabeled Hysteria/MASQUE/WireGuard.
- Cronet's current `ProxyOptions` can allow or disallow direct fallback. Proxy health/speed tests must disallow direct fallback and test exactly the nominated endpoint. Library improvements do not automatically change other apps' network stacks.
- QUIC or stronger/aggressive congestion settings are not universal performance fixes. Hysteria's bandwidth guidance explicitly cautions against overestimating capacity. Measure mobile CPU, thermals, battery and latency under load.

## Evidence and acceptance

The 2693-household encrypted DNS paper measured US DNS response times, not real mobile page loads; it found no universally best resolver/protocol. The QUIC/DNS/HTTP3 coalescing paper used emulated access conditions and a single-server/single-resolution setup, which differs from arbitrary public proxies. WWW2024's QUIC study found receiver-processing bottlenecks in certain high-bandwidth implementations; it does not establish that current QUIC is always worse. These are reasons for paired measurements, not transferable improvement percentages for Iran.

Next implementation order: per-stage/error diagnostics; scan/download load limits and selective slow retests; bounded per-route reliability history; optional DoH experiment; alternate-engine prototype; licensed bonding prototype if the provider constraints can be satisfied. Keep success/failure rate alongside median/p95 HTTPS completion, recovery time, bytes, CPU/RAM and battery, with identical endpoint/target and alternating before/after runs. Require device handover and sleep/wake validation before claiming a quality improvement.

## Primary references

- [Speedify SDK](https://speedify.com/sdk/), [Android prerequisites](https://speedify.github.io/speedify-sdk-docs/android/guides/prerequisites.html), [authorization](https://speedify.github.io/speedify-sdk-docs/android/guides/sdk_authorization.html), [connectProxy](https://speedify.github.io/speedify-sdk-docs/android/speedifysdk/com.speedify.speedifysdk/-speedify-s-d-k/connect-proxy.html)
- [Android VPN service constraints](https://developer.android.com/develop/connectivity/vpn)
- [Google DoH](https://developers.google.com/speed/public-dns/docs/doh), [Cloudflare wireformat](https://developers.cloudflare.com/1.1.1.1/encryption/dns-over-https/make-api-requests/dns-wireformat/), [Quad9 services](https://docs.quad9.net/services/), [Google DNS/CDN performance](https://developers.google.com/speed/public-dns/docs/performance)
- [WARP modes](https://developers.cloudflare.com/warp-client/warp-modes/), [RFC9298](https://www.rfc-editor.org/rfc/rfc9298.html), [RFC9484](https://www.rfc-editor.org/rfc/rfc9484.html), [WireGuard embedding](https://www.wireguard.com/embedding/), [Hysteria server](https://v2.hysteria.network/docs/getting-started/Server/), [Hysteria client bandwidth](https://v2.hysteria.network/docs/advanced/Full-Client-Config/)
- [sing-box URLTest](https://sing-box.sagernet.org/configuration/outbound/urltest/), [Cronet ProxyOptions](https://developer.android.com/develop/connectivity/cronet/reference/org/chromium/net/ProxyOptions), [MPTCP](https://docs.kernel.org/networking/mptcp.html), [Spectrum options](https://developers.cloudflare.com/spectrum/reference/configuration-options/)
- [Can Encrypted DNS Be Fast?](https://arxiv.org/pdf/2007.06812), [QUIC/DNS/HTTP3 study](https://arxiv.org/pdf/2306.11643), [QUIC over Fast Internet — WWW2024](https://arxiv.org/pdf/2310.09423)
