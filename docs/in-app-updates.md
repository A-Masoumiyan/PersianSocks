# Android in-app updates — 0.27.0

Since 0.28.0, the drawer footer has an update icon next to the installed version. A red dot and a small localized Update: vX.Y.Z label identify a newer stable release. Newer releases change the update label/color. Opening/foregrounding the main screen checks GitHub releases each time; tapping the update icon checks immediately; when a cached update is already offered, Check again refreshes the release metadata. A successful check caches the selected release, preserving the badge across restarts and clearing it after installing that version.

The updater uses the public repository release list, not the Latest label, because a Windows release may have that label. Only non-draft, non-prerelease tags `vMAJOR.MINOR.PATCH` with a matching uploaded `PersianSocks-MAJOR.MINOR.PATCH.apk` are eligible. GitHub SHA-256 digest and asset size are mandatory. The first 100 releases are checked; APK size is bounded to 32 MiB. API JSON is bounded to 2 MiB. Redirects are limited to HTTPS on GitHub and the official release-asset hosts. Download is cancelled on activity destruction, and partial/error files are removed; network work runs off the UI thread and does not change VPN/scan state.

After a user-requested download, SHA-256 and length must match, then Android parses the APK: package must equal the installed app, versionName must match the release, versionCode must increase, and the exact current signing-certificate set must match. No silent install: the user chooses Install, optionally grants Android's unknown-app-source permission, then confirms the system installer. Installation can stop the app/VPN; this is disclosed before proceeding. APKs are shared through a non-exported, read-only URI provider restricted to randomly named verified APKs in a dedicated cache folder. No broad storage permission or private-data URI is exposed. Personal builds declare REQUEST_INSTALL_PACKAGES for this flow. Gradle and offline Play manifests remove it and the provider; Play update buttons open the Play listing and cannot start the APK downloader.

## Publisher contract

Increment versionName and versionCode in app/build.gradle, build with the existing official signing key, merge source through PR, and publish a stable release tag matching versionName. Upload the signed APK with the generated exact filename and allow GitHub to finish uploading/digest calculation. Do not change the signing key, label a debug build as stable, or replace APK content without increasing the version. A release with an absent hash/wrong name cannot be offered as an installable update.

Versions before 0.27.0 do not contain this updater; users must install 0.27.0 once from GitHub/Drive. Adding an updater in the repository cannot remotely change already installed older APKs. Normal same-package/same-signature Android upgrades preserve local saved data; users should not uninstall to upgrade.

Validation: full host suites, 30 selection/version/cache/redirect cases, 512 localization checks and a full signed Android build passed. Live GitHub metadata was also parsed by the same selection class. The final release APK passed signature/native/permission/alignment checks. Android installer UI, unknown-source return flow and VPN behavior during a real system upgrade require device testing; these have not been device-tested here. Exact current hash is in verification.txt. Google Play Protect may still request a scan.

Official API references consulted:
- https://docs.github.com/en/rest/releases/releases
- https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls()
- https://developer.android.com/reference/android/content/ContentProvider
- https://developer.android.com/reference/android/content/Intent#FLAG_GRANT_READ_URI_PERMISSION
