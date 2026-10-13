# Android security review — candidate 0.22.0

Status: changes and host validation completed; **not approved for release**. No GitHub Release or Google Drive APK publication was performed.

## Verified baseline

On 2026-10-13 the GitHub releases/latest API returned [v0.21.0](https://github.com/A-Masoumiyan/PersianSocks/releases/tag/v0.21.0), commit `a0bd2f3ac121f1dd550ab7b94a007b9bae435b06`, Android versionCode 22. Work starts at that tag, not the default branch: the default branch had regressed Android to 0.18.0 and lacked the release's download-ranking features. The candidate restores/preserves the 0.21.0 feature set and declares versionName 0.22.0 / versionCode 23. Windows is outside this change.

At final recheck, releases/latest changed to windows-v0.5.0, which bundles the same Android 0.21.0 APK. The releases collection still identifies 0.21.0 as the newest Android version. The branch was synchronized with main `37372be6a7c269b36bdff086e82df10699ec7594`; all Windows files, existing release workflows and non-Android release artifacts were preserved from main, and no APK assets or publication workflows were added by this PR.

## Play Protect diagnosis

[Issue #1](https://github.com/A-Masoumiyan/PersianSocks/issues/1) has no exact warning text, screenshot, affected APK hash, or device scan verdict. No device is connected to ADB. Therefore the actual cause and whether the warning is an unknown-app scan prompt or a harmful-app classification remain **undetermined**. These fixes address observed security problems; they are not evidence that Google has removed a warning. Play Protect has not been disabled or bypassed.

[Google's warning guidance](https://developers.google.com/android/play-protect/warning-dev-guidance) distinguishes these messages. Capture the exact message, installed APK hash, device/Android version, Play Protect version, and normal scan result. A harmful classification can be appealed after review; an unknown-app security-check prompt follows Google's normal scanning process. Neither a permission change nor a new signature is a guaranteed remedy.

## Changes

- The local protected SOCKS gateway formerly accepted anonymous clients from any app able to reach loopback. Such TCP clients could bypass per-app VPN routing choices through protected upstream sockets. It now requires RFC1929 authentication with fresh independent 256-bit username/password values per gateway. The native engine receives these in its private configuration. Incorrect, anonymous, and other-session credentials are rejected before a physical socket is created. The private config is deleted on tunnel shutdown. Native credential support is documented in [upstream 2.18.0 configuration](https://github.com/heiher/hev-socks5-tunnel/blob/2.18.0/conf/main.yml). UDP functionality is retained; native/device validation is still required, and TCP authentication does not amount to proof of complete local UDP isolation.
- The APK builder could silently generate a different release key when the original was missing. It now fails before building, never creates a replacement, and pins the actual historical signing certificate. Gradle release and AAB paths also require the original identity. Diagnostic unsigned APKs use an explicit flag, remain in diagnostic directories, and are not publication artifacts.
- Every release path requires an HTTPS privacy URL and the pinned native AAR hash. URL syntax validation does not prove publication or publisher accuracy: the owner must supply and verify the actual policy page. The policy template remains a draft with missing publisher/contact information.
- The personal profile no longer falsely states that SOCKS5/HTTP connections encrypt the device-to-proxy leg. The Play profile remains restricted to certificate-validated TLS 1.2/1.3. VPN consent is explicit in both profiles, including quick-control entry points. Privacy details are accessible from both profiles.
- Installed-app inventory is read in the Split Tunneling screen after separate consent. Cancelling closes that screen without reading the inventory. No inventory is uploaded; selected package IDs remain in private preferences. The full-list/system-app selection and ALL / ONLY_SELECTED / EXCEPT_SELECTED modes remain intact.
- CI now runs host tests, release-input tests, and builds/lints both debug profiles. This review did not run the remote workflow.

## Permissions and Play policies

The manifest retains INTERNET, ACCESS_NETWORK_STATE, foreground-service/special-use, notifications, and QUERY_ALL_PACKAGES. No install-packages, accessibility, SMS, contacts, camera, microphone, or storage permissions were added. Exported quick-settings service is protected by BIND_QUICK_SETTINGS_TILE; the VPN service is protected by BIND_VPN_SERVICE and not exported. Backup remains disabled.

Scoped launcher `<queries>` cannot preserve selection of every non-launchable/system app. Broad visibility is retained for complete per-app routing. [Google's visibility policy](https://support.google.com/googleplay/android-developer/answer/10158779) requires a documented core need, rejection of narrower alternatives, and a permission declaration; VPN status does **not** automatically establish eligibility. Play Console approval is pending, not claimed. The declaration should show all-app/system-app selection, explain local-only usage, and demonstrate why launcher-only visibility is insufficient.

[VpnService policy](https://support.google.com/googleplay/android-developer/answer/12564964) requires listing disclosure, encrypted device-to-endpoint transport, an accurate declaration, and affirmative disclosure/consent where applicable. Existing transport tests cover cached insecure candidates, scans, failover and gateway connections; the Play flag is enforced in the transport layer, not just metadata. Personal SOCKS5/HTTP features are retained outside the Play profile.

[User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311) treats installed-app inventory as sensitive. The in-app inventory and VPN disclosures and policy draft now distinguish local settings/results/error logs, selected-app inventory, third-party proxy destination/IP visibility, source/test servers, and user-triggered Cloudflare speed downloads. Publisher review, published policy, Data safety answers, VpnService declaration/video, and specialUse foreground-service justification remain required. This source review is not Play approval or a complete third-party/native malware audit.

## Signing evidence

The official 0.18.0, 0.20.0 and 0.21.0 APKs were downloaded and each SHA-256 matched GitHub's asset digest:

| APK | SHA-256 |
| --- | --- |
| 0.18.0 | `1f43b01b79f4b4011f31a0e019ec7fd29253de6e9b435566f7b6a9ec473f23ec` |
| 0.20.0 | `88fbefa8a82c78fca76392a3b431bda708e0826961f35cde5324b002a365e2b8` |
| 0.21.0 | `b92a774ab159d9dc8b3c1c4032b342cc7dcd0bf40d23f9d60fbc167e22efad0f` |

Official SDK 35.0.0 apksigner verification exited 0 for all three, with APK Signature Scheme v3 valid. All have certificate SHA-256 `01093c8c0026d46c2d4dd45914c1bb5c5667a5d703286b612911582d202673dd`, identical to `signing/PersianSocks-public.pem`. Package ID remains `ir.persiansocks.app` and candidate versionCode increases to 23. These are prerequisites for in-place updates, **not** a tested installation upgrade. The private release key/password files are absent in this checkout.

## Validation actually performed

- Final host suite: **387 checks passed**, including **21 Split Tunneling checks**, **10 new gateway authentication checks**, and **33 strict Play security checks**. Loopback fixtures carried TCP through SOCKS5/HTTP/HTTPS, UDP through the existing SOCKS relay, destination TLS, TLS 1.2/1.3 wire recording, certificate/hostname failures, cancellation, scans/cache/resume, ranking, and failover. Test-only trust configuration remains outside the app APK.
- Release-input suite: **5 tests passed**, including missing-key refusal without key generation, rejection of a newly generated unrelated test key, public certificate pin, invalid/missing privacy URL, and native AAR tampering.
- Complete source/resource/DEX/native packaging with official SDK 36 platform + SDK 35.0.0 tools and JDK 17 succeeded for **personal and play**, producing **unsigned diagnostic APKs**. Manifest inspection confirmed version 0.22.0 / code 23 / minSdk 29 / targetSdk 36. Both include the four existing native ABIs and pass 16 KB zip alignment checks. No new signed release APK or AAB was built.
- Native AAR hash matches pinned upstream 2.18.0: `15ec8ed121663b562c99caa5bb602d1009f24e5b09e733438b81988f12feaaab`. AAR contains its manifest, classes.jar, proguard rules and four native libraries. This is provenance/integrity verification, not exhaustive native-code security certification.
- Normal Gradle build/lint was attempted but failed resolving `com.android.application` 8.7.3 from Google/Maven/Plugin repositories. Accordingly **Gradle Android integration and lint did not pass**. SDK downloads were recovered using Google's official redirector distribution path with repository checksums; repository policy/proxy access still prevented Gradle plugin resolution.
- ADB device enumeration succeeded with **zero connected devices**. Android installation, JNI credential integration, real per-app routing, consent UI behavior, Play Protect scan, and upgrade tests were not executed.

## Release blockers and required device tests

1. Provision the original signing key privately and verify the final APK with apksigner against the pinned historical fingerprint. Do not commit credentials or create a replacement key.
2. Provide Android 10 and Android 16 devices. With Play Protect enabled, install/upgrade from official 0.18.0/0.20.0/0.21.0; confirm persisted settings and original signature compatibility. Record the warning and normal scan verdict.
3. Verify native-engine RFC1929 authentication and disconnect/reconnect, IPv4/IPv6/DNS, SOCKS UDP, HTTP/HTTPS, failover, quick controls, speed tests, and scanner cancellation on-device. Confirm unrelated apps cannot use the local TCP gateway without credentials. Review local UDP peer isolation separately.
4. Test ALL, ONLY_SELECTED and EXCEPT_SELECTED with included/excluded user and system apps, non-launchable apps, app removal during connect, empty allow-list refusal, and reconnect after selection changes. Host builder-rule tests do not prove Android traffic routing.
5. Finish Gradle build/lint, publisher-reviewed public policy and Play declarations/approval. Permission eligibility remains unresolved.
6. Only after signed-artifact and device evidence succeeds may an actual Release/APK checksum be published to GitHub and the intended Google Drive folder. Drive upload permissions/destination were not verified because no publishable signed/tested artifact exists.
