# Android 0.28.6: filtered ping action

The main toolbar's circular arrows now retest ping for the complete frozen set of healthy proxies matching the current service, protocol, ping and smooth-results filters. This does not reset or write filter settings and does not use the pinned connected endpoint as an exception to membership. Tests run one at a time using the existing physical-network ping implementation. Pressing again cancels the queue and active test. The icon changes to stop while testing, with accessible progress. Saved-proxy ping remains scoped to saved entries; favorites are not removed automatically.

The reset action remains explicitly inside the filter dialog. All host suites and 544-string bilingual validation passed. Signed APK 0.28.6/code36 built and integrity audit passed with the existing certificate. Android device UI was not available for testing.
