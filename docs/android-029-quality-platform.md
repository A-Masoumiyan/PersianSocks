> Historical 0.29.0 design. Version 0.29.6 removes Cronet and Instagram priority, restores the v18 source catalog and scan deadlines, and uses fixed bounded scan profiles. See [current changes](android-0296-v18-scanning.md).

# Historical Android 0.29.0 — local connection quality and the Android data platform

The SOCKS5, HTTP CONNECT and HTTPS CONNECT tunnel transports are unchanged. This release adds all seven proposed improvements around those transports.

| Improvement | Implementation and bounds |
|---|---|
| Local quality history | Room records separate `wifi`, `mobile` and `other` categories. Only local probes and VPN sessions contribute. A Wilson lower confidence bound, recent latency median/MAD, age decay, duration and confirmed disconnects drive the selectable Quality sort. Existing explicit sort choices and Instagram priority remain. No SSID, subscriber or device identifier is recorded; history is not uploaded. |
| Adaptive scanning | Per-run AIMD starts at eight probes, increases after repeated successful samples, and reduces concurrency on slow proxy/destination responses. The existing 24 foreground / 8 background / 4 connecting caps remain hard limits. Attempt deadlines adapt within 1.8–6 seconds connect / 3.5–9 seconds read / 20 seconds overall. Explicit slow mode retains its original budget. Pause closes sockets and does not complete cancelled items. |
| Real SOCKS5 UDP | A physically bound, protected datagram socket keeps UDP ASSOCIATE's TCP control alive. A small DNS A question for `example.com` is sent through the relay to `1.1.1.1:53`. A matching transaction, source destination, complete question and A answer are required. ACK-only, fragmented, mismatched or private redirected relays do not establish UDP support. Old association-only flags are cleared on deserialization. An inconclusive UDP test never removes a working TCP proxy. |
| Room + Paging 3 + RecyclerView | Actual AndroidX libraries replace the healthy cache/catalog persistence and ListView. Migration is transactional; failure tombstones and saved proxies remain, and original files are retained. Existing checkpoint/journal files stay the durable resume format. RecyclerView uses a header ConcatAdapter and a PagingDataAdapter, with 80-item pages and a 400-item paging window. Filtered snapshots are ordered off the UI thread and serialized in 128-row batches. The shared endpoint index still lives in memory for VPN selection; this release does not eliminate that memory or all list sorting. |
| OkHttp | Network-bound clients fetch source lists, direct site icons and updates. Redirect validation, TLS checks, response bounds, timeouts, cancellation and updater APK signature/digest checks remain. A proxy-specific icon, probe or speed test continues using its explicit SOCKS/CONNECT transport. |
| WorkManager | One unique six-hour periodic job, first run after six hours, flex 30 minutes, unmetered network and battery-not-low constraints. It refreshes sources and rechecks at most six existing results against one HTTPS target, with a 90-second budget. It yields to VPN, scans and manual tests, never starts a VPN/foreground service and never changes paused scan inputs. The sidebar can disable it. WorkManager adds normal WAKE_LOCK/RECEIVE_BOOT_COMPLETED permissions; AndroidX adds a signature permission for its internal receivers. No automatic GitHub build trigger is enabled. |
| Experimental Cronet | Sidebar opt-in, off by default. A bundled Cronet engine negotiates HTTP/2/3 for the app's own HTTPS requests, bound to the chosen Android network. Streaming holds at most one 16-KiB chunk. Unavailable engines or pre-header failures fall back to OkHttp on that same network; cancellation and body/integrity failures do not retry. User traffic and the proxy tunnel protocol do not switch to QUIC. Four native ABIs increase universal APK size. |

## Build

AGP 8.10.1, Gradle 8.11.1, Java 17 and Android API 36. Actual pinned dependencies: Room 2.8.4, Paging 3.4.2, RecyclerView 1.4.0, Activity 1.10.1, WorkManager 2.11.2, OkHttp 5.3.2 and Cronet bundled 500.1.0. The original Hev AAR and release certificate remain pinned. Cronet's native hashes are audited in `scripts/platform-native-sha256.json`; manifest permissions and 16-KiB ZIP alignment are checked by the release audit.

```
./gradlew :app:testPersonalDebugUnitTest :app:assemblePersonalRelease
python scripts/run-tests.py --jdk /path/to/jdk17 --json /path/to/test-only-json.jar
python scripts/audit-release-apk.py app/build/outputs/apk/personal/release/app-personal-release.apk --sdk /path/to/android-sdk
```

`build-apk-offline.py` and `build-bundle-offline.py` now delegate to the pinned Gradle pipeline with `--offline`; populate its dependency/distribution cache once before using them. A manual javac-only build cannot merge AndroidX manifests, run Room's processor or package these dependencies correctly. Play-specific permission/privacy gates remain required for Play release builds.

## Validation and limits

The complete host transport/security suites include a new real local UDP fixture, fake-ACK/spoof/fragment rejection, quality confidence/decay and adaptive limits. Robolectric exercises real Room SQLite, two thousand paged rows, old-success/tombstone ordering, Wi-Fi/mobile separation, legacy migration with favorites/resume, OkHttp streaming/redirect/cancellation creating both language UIs, physical address-loss handling and unique/cancellable WorkManager scheduling. Native Android VPN performance, live ISP connectivity and Cronet negotiation still require a real device; host tests do not establish them.
