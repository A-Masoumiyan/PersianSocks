# ادامهٔ بررسی ابری PersianSocks Android 0.22.0 — انتشار متوقف است

سورس بررسی‌شده: `7db2fcacad50bf631277ef92d1a4cc1f97609a09`، شاخهٔ `fix/android-play-protect` و [PR پیش‌نویس ۴](https://github.com/A-Masoumiyan/PersianSocks/pull/4). این گزارش فقط نتیجهٔ مرور و وضعیت موانع را ثبت می‌کند؛ تأیید ساخت، دستگاه یا Play Protect نیست.

## جلوگیری از کار تکراری و حفظ نسخهٔ قبلی

درخواست ارسال مجدد، ادامهٔ همان کار در نظر گرفته شد. در بررسی مجموعهٔ Releaseها، پوشهٔ موجود Drive، جست‌وجوی نام دقیق و سپس نام ساده‌تر در فایل‌های ذخیره‌شده، و اجرای Actions شاخه، APK یا Release نسخهٔ 0.22.0 پیدا نشد. هیچ ساخت، Release یا آپلود تکراری آغاز نشد.

آخرین Release، `windows-v0.5.0`، همان APK اندروید 0.21.0 را همراه دارد؛ جدیدترین نسخهٔ منتشرشدهٔ اندروید همچنان 0.21.0 است. SHA-256 ثبت‌شدهٔ APK رسمی:

`b92a774ab159d9dc8b3c1c4032b342cc7dcd0bf40d23f9d60fbc167e22efad0f`

فایل Drive با ID `1Py7BT2-jRnUTegpNkEKXya1neKp7Ej_L`، نام `PersianSocks-0.21.0.apk` و اندازهٔ **3,794,953 بایت** در پوشهٔ `1OIfuAPh1ruyEW1UCLDvAAZFsKvoenkxQ` خوانده شد. modifiedTime آن `2026-10-12T23:37:01.696Z` بود. هیچ update/replace/delete روی این فایل، هیچ تغییر ویندوز، هیچ ادغام main/PR و هیچ فعال‌سازی ساخت خودکار اندروید انجام نشد. خواندن metadata امروز، بررسی مستقل checksum بایت‌های Drive نیست.

## آنچه در این ادامه واقعاً بررسی شد

- فایل‌های گزارش امنیتی و سیاست حریم خصوصی در commit فوق خوانده شدند؛ مخزن public است و [permalink ثابت سیاست](https://github.com/A-Masoumiyan/PersianSocks/blob/7db2fcacad50bf631277ef92d1a4cc1f97609a09/docs/privacy-policy.md) از طریق اتصال GitHub با محتوا قابل خواندن بود. نام PersianSocks و راه تماس مخزن رسمی با پروژه سازگارند؛ هویت حقوقی تازه‌ای ساخته نشده است. بازشدن این صفحه روی گوشی آزمایش نشد.
- app/build.gradle نسخهٔ **0.22.0 / code 23**، package **ir.persiansocks.app**، compile/target SDK 36 و minSdk 29 را اعلام می‌کند. این بررسی سورس است، نه بررسی Manifest یک APK جدید.
- scripts/release-security.py و مسیرهای Release در Gradle گواهی اصلی و AAR را pin می‌کنند و با نبود کلید یا تفاوت گواهی متوقف می‌شوند. مسیر APK Release هیچ کلید جایگزینی تولید نمی‌کند. ساخت personal باید با URL ثابت سیاست بالا انجام شود.
- QUERY_ALL_PACKAGES و انتخاب برنامه‌های سیستمی/بدون launcher حفظ شده‌اند. صفحهٔ انتخاب برنامه‌ها پس از رضایت جداگانه inventory را می‌خواند. رضایت VPN در هر دو profile و محافظت broadcast داخلی با permission امضایی در سورس وجود دارد.
- TCP gateway ابتدا RFC1929 را با username/password تصادفی مستقل هر gateway بررسی می‌کند؛ تنظیمات موتور بومی همان مقادیر را دریافت می‌کنند. پس از خطای احراز هویت، سوکت بالادستی ساخته نمی‌شود. کارکرد واقعی JNI/موتور روی دستگاه هنوز تأیید نشده است.
- Play Release هنوز نیازمند تأیید واقعی Google برای مجوز مشاهدهٔ همهٔ برنامه‌هاست؛ هیچ attestation یا پرچم approval فعال نشد. warning نمایان lint مجوز، مجوز انتشار Google نیست.

## یافتهٔ باقی‌مانده: اتصال هویت peer در UDP محلی

در [ProtectedSocksGateway.java](https://github.com/A-Masoumiyan/PersianSocks/blob/7db2fcacad50bf631277ef92d1a4cc1f97609a09/app/src/main/java/ir/persiansocks/app/ProtectedSocksGateway.java)، relayUdp فقط loopback بودن فرستنده را بررسی می‌کند و peer را با `peer.compareAndSet(null, p.getSocketAddress())` از اولین دیتاگرام می‌پذیرد. این انتخاب به هویت/UID یا endpoint مورد انتظار کلاینت بومیِ احرازشده متصل نشده است.

بنابراین احراز هویت TCP به‌تنهایی شاهد جداسازی کامل UDP از برنامه‌های دیگر نیست. در صورت امکان دسترسی cross-app به این loopback روی دستگاه، پذیرش نخستین فرستنده می‌تواند خطر اختلال، تزریق یا استفاده از relay خارج از انتخاب‌های Split Tunneling ایجاد کند. این یک یافتهٔ ایستا و خطر مشروط است؛ سوءاستفادهٔ واقعی روی Android آزمایش یا اثبات نشده است. پیش از انتشار، ارتباط peer با کلاینت مورد انتظار باید بدون حذف SOCKS UDP/قابلیت‌ها طراحی و روی دستگاه تأیید شود.

## شواهد اجرا: گزارش قبلی در برابر این جلسه

| مورد | گزارش قبلی محلی | اجرای تازه در این جلسه |
| --- | --- | --- |
| Host/security/Split Tunneling | 387 بررسی پاس؛ شامل 21 Split، 10 احراز هویت و 33 Play | اجرا نشده |
| Release-input tests | 6 تست پاس | اجرا نشده |
| SDK36، packaging diagnostic هر دو flavor | پاس؛ unsigned | اجرا نشده |
| Gradle debug و lint هر دو flavor | پاس؛ 0 error / 45 warning | اجرا نشده |
| ELF و ZIP alignment 16 KB | طبق گزارش پاس | اجرا نشده |
| Release personal امضاشدهٔ 0.22.0 | ساخته نشده | ساخته نشده |
| نصب/ارتقا، JNI، VPN و Split Tunneling واقعی | اجرا نشده | اجرا نشده |
| Play Protect روشن، متن هشدار و scan verdict | موجود نیست | موجود نیست |

اعداد بالا از [گزارش قبلی](android-security-review-2026-10-13.md) و درخواست ادامه آمده‌اند؛ در این جلسه به‌عنوان نتیجهٔ اجرای مستقل تأیید نشده‌اند.

## مانع محیط و وضعیت امضا

محیط `/workspace/scratch/a9d81df76698` با `environment_offline` گزارش شد و ابزارهای exec/خواندن فایل محلی در فهرست ابزارهای این جلسه موجود نیستند. بنابراین نمی‌توان حضور یا خوانایی کلید خصوصیِ قدیمی، SDK، ADB، دستگاه یا emulator را امروز تأیید کرد. عدم دسترسی به کلید به معنی اثبات حذف‌شدن آن نیست.

گواهی مورد انتظار، طبق APKهای رسمی قبلی و pin سورس، همچنان:

`01093c8c0026d46c2d4dd45914c1bb5c5667a5d703286b612911582d202673dd`

برای APK 0.22.0، **artifact، اندازه، SHA-256 و نتیجهٔ apksigner جدید وجود ندارد**. محتوای کلید یا رمز خوانده، افشا یا منتقل نشد؛ هیچ کلیدی ساخته نشد. اجازهٔ کار قبلاً داده شده و مانع فعلی، دسترسی و شواهد آزمون است؛ مجوز مجدد کاربر لازم نیست.

## شرط‌های باقی‌مانده برای انتشار

1. اتصال دوبارهٔ محیط ساخت و دسترسی خصوصی به همان کلید و SDK؛ اجرای مجدد host و release-input tests و ساخت کامل personal با permalink ثابت سیاست.
2. بررسی APK واقعی: package، 0.22.0/code23، checksum، apksigner verify، تطبیق گواهی اصلی، zipalign16KB و بارگذاری native.
3. آزمون Android 10 و 16: ارتقا از 0.21 با حفظ داده‌ها، رضایت‌ها، RFC1929/JNI، IPv4/IPv6/DNS، SOCKS UDP، HTTP/HTTPS، lifecycle، failover و quick controls.
4. آزمون واقعی هر سه حالت Split Tunneling و انتخاب user/system/non-launcher apps، تغییر/حذف برنامه و allow-list خالی؛ بستن یافتهٔ UDP بالا.
5. ثبت متن دقیق هشدار، hash APK و نتیجهٔ اسکن معمول با Play Protect روشن. [راهنمای رسمی Google](https://developers.google.com/android/play-protect/warning-dev-guidance) هشدار ناشناس و تشخیص آسیب‌رسان را متمایز می‌کند؛ رفع هشدار ادعا نمی‌شود.
6. فقط پس از موفقیت gates، ایجاد Release جدید و **NEW** فایل Drive 0.22.0 با readback checksum/metadata؛ ابتدا وجود همان artifact/hash دوباره بررسی شود و 0.21 به‌طور کامل حفظ شود.

**نتیجه: انتشار یا آپلود نسخهٔ 0.22.0 در این ادامه مجاز به اجرا نشد، چون شرط‌های اعلام‌شده احراز نشده‌اند.**
