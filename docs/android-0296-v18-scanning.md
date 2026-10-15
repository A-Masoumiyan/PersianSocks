# Android 0.29.6 — restore v18 scanning

- Restore the exact 17 SOCKS5 and six HTTP/HTTPS feed definitions from tag `v0.18.0`. Bootstrap assets already match v18. Custom source URL validation remains current. Collection uses source-order round robin; the filtered HProxy API is no longer a default or preferred source.
- Restore v18 fast deadlines (2500 ms connect, 4500 ms read, 11000 ms hard deadline), and deep deadlines (6000/9000/20000 ms). At least one certificate-verified service must respond successfully; failure of a chosen measurement target cannot erase another service's success. Existing service masks remain truthful.
- Remove unconditional Instagram sorting and Instagram-first connection preflight. The explicit service filter still chooses its service. Fresh installs/one-time migration default to ordinary ping and all-service scanning; later explicit target choices are retained.
- Foreground fast capacity is bounded at 216 workers, versus the old fast starting capacity of 24. Deep uses v18's 24 workers. Background caps are 54/6 and VPN-setup caps are 8/1. Independent proxy timeouts cannot repeatedly collapse the entire scan to six workers. Checkpoint persistence remains every 500 completions or 15 seconds without waiting for a slow batch tail before scheduling the next probes.
- Remove Cronet engine dependency, Java adapter, UI, localized strings, native-library inventory and verification entries. App-only HTTP continues through captured-network OkHttp; TLS checks and the native Hev tunnel remain in place.
- Remove the in-page loading spinner/text and numeric Max input. Cached content is published before country-table parsing. Keep the scan-limit slider, scan progress, and start-only fast/deep choice. The obsolete slow/normal sidebar selector was already absent in 0.29.5.

## Validation

Host tests cover local certificate-verified SOCKS5/HTTP/HTTPS, service-specific rejection, no Instagram ordering, 216-worker bounds, pause/resume, cancellation, source ordering, persistence/recovery and 96k-input memory pressure. The 1728-row fixture with identical 250 ms artificial delays completed in 2121 ms fast versus 18123 ms with 24 workers (8.54×). This isolates scheduling; it cannot predict throughput or false negatives on a phone/ISP. Android/Robolectric checks cover immediate content without a loading overlay, migration, sidebar and scan controls. Build, lint, APK certificate/native inventory checks are required before delivery.

No physical phone is attached. Android's OS splash and actual disk/network time cannot be reduced to zero. Restoring source definitions does not restore the historical live contents or quality of third-party lists. Fetch a fresh list after updating to repopulate candidates previously removed by scans.
