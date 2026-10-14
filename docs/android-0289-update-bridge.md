# Android 0.28.9: update bridge

This release is built from the published 0.28.8 source. It retains that application's features and native packaging and changes only the updater, update messages and version metadata.

Installed 0.28.x updaters reject APK assets above 32 MiB. The full 0.29.x APK exceeds that limit. 0.28.9 fits the existing limit, so it can be offered inside the old app. It raises the APK ceiling to 512 MiB, extends the total download budget according to size up to one hour, and reports an unavailable newer release separately from “up to date.” All byte-count, SHA-256, package, higher version code and original signing-certificate checks remain.

Install this bridge without uninstalling. Open it and check again to download the full 0.29.1 release from inside the app. This bridge has versionCode 39; the full 0.29.1 release has versionCode 40. Existing preferences, favorites and legacy cache/checkpoint files are retained for the normal 0.29.x migration.

The bridge source branch is retained for the release and is not merged over main. Actual installer behavior on a physical Android device has not been exercised in this environment.
