# Android 0.28.5: reconnect and Instagram priority

Disconnect now stops the service only while its start ID and connection generation remain current. A later start cancels that idle stop, preventing an old disconnect from destroying a replacement connection. TCP preflight captures a fresh physical route for each of at most three attempts, with 900 ms and 1800 ms backoff for transient failures. Authentication, certificate and protocol errors remain terminal. Permanent TCP refusal is still reported accurately.

Preflight checks one required target (Instagram preferred) instead of duplicating a complete service scan. Only verification through the actual VPN may announce connected status or commit full service grades. Old candidate errors are cleared when switching candidates; the connection card follows the actual connecting/active endpoint.

Service response times are persisted separately. Instagram success and then Instagram response time take priority over secondary sorting. The actual connected endpoint is pinned to the first row; saved results never add it automatically to favorites.

## Validation

- All host suites passed, including 340 reconnect/ranking checks and 100 rapid stop/start cycles.
- Local TLS fixtures exercised SOCKS5, HTTP and HTTPS reconnects, real TCP refusal followed by recovery, cancellation, terminal failures, repeated disconnect/reconnect, single-target preflight and service timing persistence.
- Existing configured-target, VPN verification, certificate, update, favicon and 96,000-row memory suites passed.
- Signed APK built successfully; version 0.28.5/code 35 and existing signing certificate verified. SHA-256: 54b549bedda7355555aace5753568d7f81524e998dacdbdda3040cbf8a556e47.

These are host and local network fixture tests. An Android phone, native VPN lifecycle on the user's device and the user's mobile network were not available for validation. Public proxies may still refuse connections, expire or rate-limit traffic; no application change can guarantee remote server availability. The screenshot alone does not establish that the lifecycle race caused that particular TCP refusal.
