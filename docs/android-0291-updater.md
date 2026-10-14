# Android 0.29.1: support larger in-app updates

The published 0.29.0 universal APK is 43,201,604 bytes. Installed 0.28.x Android updaters accept at most 32 MiB (33,554,432 bytes), silently skip larger assets, and previously displayed “up to date” after a successful metadata request with no eligible result.

0.29.1 increases the download ceiling to 512 MiB and scales the total download budget with the declared APK size, capped at one hour. Downloads still stream through a 32 KiB buffer and retain cancellation, connect/read timeouts, exact byte count and integrity checks. APK native packaging, all four ABIs, Cronet and connection features remain unchanged. The release audit checks the new ceiling.

For users still on 0.28.x, the separate 0.28.9 bridge release retains the 0.28.8 application and changes only its updater, version and related messages. Its APK fits the old limit without compressing or removing features from the full 0.29.1 release. The old updater discovers 0.28.9; after that is installed, its repaired updater discovers and downloads 0.29.1. No manual APK download or app uninstall is required. The bridge is based on the published 0.28.8 source and is not merged over the newer main branch. Its version code is 39; the full 0.29.1 version code is 40.

Update metadata parsing now distinguishes “no newer version” from “a newer version exists but its asset is unavailable or invalid.” Manual checks in the latter case show the version, retry action and official release page instead of “up to date.” Downloads still require the exact repository URL, allowed redirects, declared byte count, SHA-256, matching package, higher version code and original signer.

Regression coverage includes the original full 0.29.0 asset, a future 200 MiB asset, the 512 MiB boundary and rejection above it, missing assets, invalid digests, an older usable release alongside a newer unusable release, Windows filtering, equal versions and size-dependent download budgets. Release validation also runs the frozen 0.28.8 parser against the published feed and verifies the second step with the bridge parser.

The installer has not been exercised on a physical Android device in this environment.
