# Android 0.29.8 — targeted rendering and measured scan capacity

## Changes

- Removed every `notifyDataSetChanged()` call from the main result list. Paging still handles insertions/removals/order changes. The visible holders compare their presentation state before updating views; equivalent endpoint instances do not redraw cards. Selection, favorites, connection badges, cached icons, mutable test measurements, rejected rows and reattached/offscreen holders are handled separately from page data.
- Fast scans start at up to 72 concurrent tests and deep scans at 12, within device limits. A measured ten-second saturated window may increase capacity by 24/4, up to the existing 216/24 ceilings. A throughput regression rolls back the last increase and waits 30 seconds before another trial. Failures count as completed work, not congestion. Cancelled work does not count.
- Device caps consider CPU count, Android low-RAM/memory-class signals, available system/heap memory, battery saver, thermal status and (API 30+) thermal headroom. Sample system state at most once per two seconds and headroom at most once per ten seconds. On resource pressure, stop admitting excess work; existing tests drain under their unchanged deadlines. Recovery is gradual. Background and VPN-connection limits still apply. Idle pool threads expire after five seconds.
- Display admitted concurrency and measured completed tests/second during a foreground scan. This is observed throughput on the current scan, not a promised speedup or a prediction of useful proxies.
- Add a local **Source quality report** under source settings. Store valid fetch age, observed content-change age, distinct candidates, duplicate parsed entries within a source, overlap across sources, fetch reliability, and latest HTTPS successes / tested candidates. Source fingerprinting ignores feed ordering. Empty/failed fetches retain the last successful timestamp and explicitly show failure.
- Source observations use Room tables with a non-destructive v1→v2 migration. Provenance and test samples expire after seven days. Metadata expires after 30 inactive days. Probe writes are batched in transactions. Original source definitions/order remain unchanged; no source is removed based on insufficient evidence.
- Count one latest probe per endpoint/network category/test profile since the latest fetch. Shared endpoints contribute to each known source. Untested candidates are not failures. Cancelled/lost-network attempts and latency-only scans are excluded. Network category is captured from the physical test network, not the current UI state. No SSID, identifier or telemetry upload is added.
- Cards, service accessibility labels and details now explicitly describe **HTTPS endpoint response**, not playback/login/full-app success. The Full filter still means all three configured built-in endpoint checks, with that scope explained. Authentication, playback and app behavior remain untested.

## Connection and transfer contention

- Compared the relay and VPN startup path against v0.18.0. The relay retains its 8 KiB copy buffer and does not introduce a bandwidth limiter; no end-to-end phone throughput regression has been quantified.
- Previously the fast scan could rise back to 216 probes as soon as the VPN became connected. Limit admissions while connected to 24 fast / 6 deep probes; during a download measurement to 4 / 2. Connecting still imposes its stricter 8 / 1 limit. Overlapping measurements hold independent, idempotently closed reservations, released on success, failure, cancellation and callback exceptions. Existing probes drain within unchanged deadlines.
- Remove the artificial 300 ms wait between successful preflight and tunnel startup. Choose the preflight service from successful measured timings, without a fixed service preference.
- Verify the real VPN path only for the requested filter. ALL stops at the first fresh HTTPS success but retains other targets as fallbacks. FULL still checks every built-in service; a specific/custom filter checks its own endpoint. Only actually checked services appear as confirmed. Certificate checks, network routing, retry policy and deadlines remain intact.
- Low latency is not a bandwidth measurement. The existing download test and download sort remain available; public proxy bandwidth depends on server load and the physical network. No automatic bulk downloads are added.

## Validation

- 32 Android/Robolectric tests: layout/targeted-card rendering, source statistics/stream chunk boundaries, v1 database migration preserving favorites/proxies, existing startup and platform integration.
- Complete host regression suite, including source parsing, TLS/protocol handling, scan pause/resume, fixed ceilings, reduced-resource scheduling and deterministic throughput/backoff/recovery cases. Actual concurrent scan fixtures cover connected/measurement caps and restoration; real TLS fixtures cover scoped connection verification, fallbacks and honest service flags.
- Native-rendered Android preview inspected at 320 dp; bilingual resources checked.
- Signed personal Release build; APK identity, original certificate, permissions and native binary alignment audited.
- Android lint: 0 errors, 50 warnings.

Actual phone frame time, thermals, battery use and ISP throughput have not been measured. The thermal APIs are vendor-dependent; unavailable thermal headroom is ignored while other resource caps remain active. A change in feed contents is an observed change, not proof of a newer publisher timestamp or future quality.

Android API reference: https://developer.android.com/games/optimize/adpf/thermal
