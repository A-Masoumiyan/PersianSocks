# Android 0.29.7 — interface and performance review

This review covers the Android source based on 0.29.6. It is not a measurement of an actual phone, an ISP, or the present quality of remote proxy feeds.

## Corrected in this change

- Fixed seven-item service rows could overflow narrow cards, especially with custom test domains. `ServiceFlowLayout` now wraps badges to the available width and respects RTL/LTR.
- Five 34 × 38 dp actions competed with country/protocol text on one line. Metadata now has a separate full-width row; the actions share their own row with at least 48 × 48 dp targets at the tested 320 dp minimum screen width.
- The connection panel had a largely empty power-button row and an ambiguous disconnected badge. Power, labelled connection state, and traffic now form a compact panel. Selected and connected addresses remain distinct from the measured public IP.
- Main action icons now have short labels and a visible selected background. Scan pause/resume/restart and the source-fetch stop action retain their original handlers.
- Settings tools now have text labels and grouped sections. The drawer is sized before opening, including narrow windows, and remains scrollable.
- Power-button accessibility descriptions now follow the selected app language and expose a button role.
- `UiIconButton`, `ScanLimitView`, and placeholder icons reuse paint/path objects rather than allocate them during each draw. Disabled opacity is updated on state changes, not during drawing.
- `SiteIcons.sync()` no longer copies the proxy index when all required icons are already in memory/on disk. Candidate snapshots are created only when an icon fetch needs routing.

## Remaining improvements, in priority order

1. **Targeted result updates.** `MainActivity.render()` still calls `notifyDataSetChanged()` in several paths, even though it uses `PagingDataAdapter` and `DiffUtil`. A follow-up should distinguish data changes, selected-row changes, live measurements, and icon updates, then update only affected visible items. Measure frame time on a large result list before and after.
2. **Resource-aware scan scheduling.** The fast profile deliberately preserves the 216-worker foreground limit requested for 0.29.6. It is not tied to device thermal status or memory pressure. Add bounded resource-based backoff and recovery using actual device signals; do not let a collection of dead proxies throttle every healthy candidate. Establish throughput, success-rate, battery, and thermal baselines on real phones first.
3. **Measure source yield.** Restoring v18 URLs restores the source definitions, not historical feed contents. Persist each source's recent fetch age, unique candidates, duplicates, and verified success yield. Use those measurements to balance fetching and report why a source contributes little. Preserve the restored sources until evidence supports a change.
4. **Explain test scope.** `ServiceTargets` verifies HTTPS endpoints; an endpoint response does not prove that an entire app, media playback, or login works. Keep transport/HTTPS evidence separate from a fuller optional service test instead of displaying broader guarantees.
5. **Separate screen state from the Activity.** `MainActivity` combines scan, VPN, collection, filtering, tests, dialogs, and lifecycle handling. Extract UI state and sections incrementally, with regression coverage for rotation, cancellation, resume, and background/foreground transitions.

The scan sources, concurrency limits, TLS checks, scoring, VPN engine, favorites format, startup behavior, and updater protocol are unchanged here. No Cronet dependency or loading overlay was reintroduced.

## Verification

- Existing Android integration/startup tests, plus native-rendered interface tests for Persian and English.
- Badges measured for 200/264/304/388 px available content widths; actual cards measured at 320/360/412 dp in the mdpi fixture. Tests check bounds, non-overlap, action target sizes, and visible metric space.
- Review images are rendered from the actual Android views with deterministic documentation-range IP addresses. They are fixtures, not screenshots of a connected phone or proof of proxy availability.
- Generate the review images with `:app:testPersonalDebugUnitTest -PpreviewDir=/absolute/output/directory`.
- Signed personal release, Android lint, bilingual resource checks, and APK identity/integrity audit.

Real-device touch behavior, TalkBack navigation, large-font rendering, battery usage, and scan throughput still require device testing.
