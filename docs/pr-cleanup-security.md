# Security PR resolution

The old security PR was based on Android 0.18–0.22. Its independent changes were ported onto current main: session-specific RFC1929 authentication for the local gateway, signature-protected internal broadcasts, removal of the local tunnel configuration on stop, pinned native inputs, and release certificate validation for Gradle/APK/AAB builds.

The certificate pin is the approved 2026-10-13 signing identity used by published Android 0.23–0.25, rather than the obsolete certificate embedded in the old PR. Only its public certificate is committed. Personal release builds remain supported without Play-specific policy declarations; Play release builds require an HTTPS privacy URL and explicit confirmation of the Google QUERY_ALL_PACKAGES approval. Debug builds and the manual-only Android workflow remain available.

The VPN/privacy notice distinguishes personal plaintext SOCKS/HTTP from encrypted HTTPS/Play, includes current IP/download services and is translated fully in Persian and English. The existing personal connection consent behavior is preserved.

Validation: complete personal/Play host suite, 10 gateway authentication checks, 6 release-guard checks and a signed offline Android build. Actual native tunnel integration on a phone and Gradle execution were not exercised. This source change does not replace the already-published 0.25 APK or its release tag.
