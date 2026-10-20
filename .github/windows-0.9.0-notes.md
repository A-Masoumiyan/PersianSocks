# PersianSocks Windows 0.9.0

- New Quick Scan button uses eight times the normal proxy-worker count: 24 -> 192 by default, bounded at 384. It uses the same TLS certificate validation, destinations, response checks, timeouts, UDP capability check, country/type scope and scan limit as normal scanning. No shortened timeout or skipped service test is used to advertise speed. Stop/resume preserves the rapid-mode flag and the original settings. Eightfold concurrency is not a promise of eightfold wall-clock speed on arbitrary hardware/networks.
- Fresh profiles automatically download the catalog after physical-network discovery on their first launch. No proxy scan starts automatically. Successful discovery is persisted; failed/cancelled empty discovery can retry on the next launch, and Stop does not trigger an immediate retry. Existing profiles with a previous list/checkpoint retain their workflow.
- 13 additional feed paths bring the built-in catalog to 41, with GitHub/jsDelivr fallback. Country-specific feeds and JSON output from public collectors are supported. Data only is downloaded; upstream collector scripts are never executed. Unsupported SOCKS4, private addresses, invalid ports, duplicate protocol/endpoints and forged country/health metadata are rejected. Iranian geography is derived from the bundled server-IP database, not a feed title or exit-IP claim.
- Iranian endpoints are placed first in the scan input so the normal 5,000-candidate limit no longer pushes them behind large global lists. Other countries remain available. Health is established by the app's own scan, not by upstream labels.

Validation:
- Local race-enabled suite passed. Controlled comparison with 128 HTTP proxy checks, three identical TLS destinations and 120ms origin response delay: normal 4 workers 4.189s, rapid 32 workers 0.759s (5.52x measured). Actual Windows validation also runs before publication and logs its own measurement.
- Stop/resume exercises the rapid worker path; persistence, first-discovery retry/completion, worker bounds, malformed JSON, protocol/country filtering and Iran priority are covered.
- Download-only snapshot on 2026-10-21 Tehran time: previous 28-feed union contained 913 Iranian protocol/endpoints; expanded 41-feed union contained 962, adding 49 unique candidates. These are not confirmed working proxies. Older/stale endpoints must pass the scan. Feed availability and counts change over time.
- Existing native Windows routing, WFP kill-switch IPv4/IPv6 TCP/UDP, forced-core-termination and actual EXE/tray/restore smoke checks remain required before publication.

New feed origins:
- https://github.com/daniyal-abbassi/iran-proxy — docs/proxies.json; current snapshot contributed 46 Iranian server-IP candidates, with an older upstream update timestamp.
- https://github.com/mmbateni/iran-proxy-checker — working_iran_proxies.json; country-filtered, because its current output consists mostly of foreign servers with Iran reachability. The audited snapshot contributed zero Iranian server-IP candidates; never relabeled as Iran.
- https://github.com/ProxyScrape/free-proxy-list — Iran shard plus HTTP and HTTPS-capable shards. Protocol URI schemes are preserved.
- https://github.com/maximilianfeix/free-proxy-list — Iran, SOCKS5, HTTP and mixed HTTPS-capable lists. The current Iran shard contains only unsupported SOCKS4 and contributes zero until supported protocols appear.
- https://github.com/vakhov/fresh-proxy-list — HTTP and HTTPS-destination-capable HTTP CONNECT proxies.
- https://github.com/mmpx12/proxy-list — HTTP and HTTPS-destination-capable HTTP CONNECT proxies.

The kill-switch scope and operational limits from Windows 0.8.0 still apply: explicit OFF/full Exit removes protection; tray mode retains it; protection is not installed before app startup or after reboot. Shared unknown WebView2 requests are rejected in ChatGPT-only mode and can affect other WebView2 apps. This is routing protection, not a guarantee of anonymity or every unknown component.
