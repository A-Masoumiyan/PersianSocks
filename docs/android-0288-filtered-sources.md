# Android 0.28.8: filtered SOCKS5 source

HProxy's public filtered API is now the first default SOCKS5 source. Existing
SOCKS5, HTTP and TLS-to-proxy HTTPS transports and certificate checks are unchanged.

## Request and candidate selection

The default request is:

```text
https://hproxy.com/api/proxy-list?format=json&protocol=socks5&min_uptime_pct=90&max_latency_ms=1500&sort=uptime&recent=false&limit=300
```

The API requires no account or key. The JSON adapter independently validates:

- public IPv4 and an integer port in 1–65535;
- `protocols` containing `socks5` and `status` exactly `alive`;
- finite latency in 0–1500 milliseconds;
- lifetime and daily uptime in 90–100 percent;
- at least 10 integer `verification_count` checks;
- a valid `last_verified_at`, no more than two hours old, with a one-minute
  tolerance for a checker clock slightly ahead of the phone.

Selection starts with checks from the last 30 minutes. If fewer than 20 candidates
qualify, it widens to 60 minutes and then at most 120 minutes. These are initial
tuning values, not a claim of optimal latency for every carrier. Candidates within
30 minutes come before the 31–60 minute tier, followed by the 61–120 minute tier;
within a tier, recorded uptime, check count and provider latency determine order.
Repeated endpoints are deduplicated using their most recent eligible check.

Provider latency, geography and alive status are not copied into local ping,
country, service success, UDP evidence or verification timestamps. Every endpoint
starts as an untested SOCKS5 candidate. Existing local service/TLS verification
determines whether it is healthy and usable on the user's network.

## Integration and fallback

Manual and previously healthy endpoints retain priority. Quality-filtered source
results come before the balanced raw-source selection and embedded fallback.
Fresh ordering survives catalog refreshes; endpoints present only in the previous
catalog remain afterward. Refreshes do not rewrite a paused scan's frozen input
or remove favorites, settings or previous service results.

The existing 23 SOCKS5 feeds, including HProxy's raw GitHub feed, remain available
as fallback. An empty result, schema error, HTML response, timeout or unavailable
API does not erase the saved catalog. Raw fallback is not labelled quality-verified.

Only this bounded metadata source has a strict 300-row / 1.5 MB response bound;
ordinary text feeds retain their existing streaming behavior for Max scans.
The new source is filtered out by the existing TLS-only and Play policies.

A process-local metadata cache prevents repeated API downloads for 10 minutes.
Candidate ages are recomputed on every use; caching never makes an expired check
fresh. A rate-limit response obeys numeric or HTTP-date `Retry-After`, with a
bounded 60-second default when absent or invalid. A prior valid cached response
may be reused after a network error, subject to the same age and quality checks.
The cache contains no user traffic, credentials or local health measurements.

## Validation

`HProxySourceHarness` exercises real local HTTP collection in addition to schema,
quality, freshness, deduplication, cancellation, cache expiration, rate-limit and
catalog-ordering cases. The complete host suite, Android build, pinned native
input/signing verification and release APK audit must pass before publication.

Live API ingestion checks establish provider response compatibility. They do not
measure the success rate or speed of those proxy endpoints from the user's phone.

Before publication, all host suites passed, including 40 new HProxy checks and
33 Play transport checks. A live JSON response fetched at 2026-10-14 09:27 UTC
contained 182 rows; the production adapter selected 37 candidates within its
60-minute window. The JVM's direct external download could not resolve
`hproxy.com` in the build environment, while the environment's HTTP retrieval
path obtained the JSON. The Android downloader was built and its real local HTTP
fixtures passed; external Android DNS/reachability requires a device test.

Provider documentation: <https://hproxy.com/docs/free-proxy-list>
