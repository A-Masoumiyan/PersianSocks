# Android 0.29.2: startup and connection availability

Version 0.29.2 / versionCode 41 fixes two regressions reported on 0.29.1: delayed first-screen creation and failed proxies retaining old successful results.

Initial Activity creation no longer reads favorites, country ranges or test-note JSON. Background loading preserves those records. WorkManager initializes on demand after the first screen; the existing scheduled work remains enabled. Test notes migrate into Room before the legacy preference values are removed. A failed database write leaves the original notes intact.

Paging uses an immutable snapshot of ranked endpoint references instead of rewriting the derived result_pages table on every render. Stored endpoints are not rewritten simply because they were loaded. Quality ranking computes each score once per snapshot, and history is bounded per network category. Never-successful scan noise does not generate quality-history writes.

A completed unsuccessful scan or connection probe on the captured valid route invalidates its previous success. The bounded preflight retries remain. Two failed VPN health samples also invalidate that endpoint. Cancellation and captured-route loss keep pending work and do not reject previously healthy proxies. A later successful scan can restore availability; old checkpoints cannot restore an invalidated result.

Favorites are preserved. An unavailable favorite keeps its star but shows unavailable status instead of old latency, throughput and service checks. Ranking discards snapshots made before the latest availability revision. Legacy notes containing a newer explicit failed connection probe also invalidate older cached successes during background reload.

SOCKS5, HTTP CONNECT and HTTPS CONNECT are unchanged. The original signing identity, four native ABIs, pinned Hev/Cronet libraries and 512 MiB in-app update budget remain. Experimental Cronet remains off by default. Features and bundled native libraries have not been removed to reduce APK size.

Validation results are recorded in verification.txt. New coverage exercises completed failures, cancellation/route loss, stale labels, fresh recovery, favorite retention, immutable paging, bounded quality history and durable test-note migration. Device startup time and connectivity on the user's ISP require checking the installed APK; no physical Android device is attached to this environment.
