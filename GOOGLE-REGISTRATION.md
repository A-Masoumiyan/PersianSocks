# وضعیت نسخهٔ فعلی

فایل تحویلی 0.10.0 حالت معمولی personal است؛ الزام TLS در آن اختیاری و پیش‌فرض خاموش است. انتشار Play فعلاً دنبال نمی‌شود. مطالب زیر مربوط به پروفایل جداگانهٔ Play با الزام ثابت TLS هستند؛ نسخهٔ اختیاری را به عنوان بستهٔ دارای الزام رمزنگاری برای Play معرفی نکن.

# ثبت رسمی PersianSocks نسخهٔ TLS

بستهٔ برنامه: `ir.persiansocks.app`؛ versionName 0.10.0؛ versionCode 11؛ minSdk 29؛ target/compile SDK 36.
گواهی امضای نسخه‌های قبلی حفظ شده است. SHA-256 گواهی:
`01093c8c0026d46c2d4dd45914c1bb5c5667a5d703286b612911582d202673dd`

## اصلاح رمزنگاری

نسخهٔ Play فقط HTTPS CONNECT با TLS 1.2 یا TLS 1.3 به خود سرور پروکسی را می‌پذیرد. گواهی زنجیرهٔ CA و نام میزبان/IP بررسی می‌شود. HTTP CONNECT معمولی، SOCKS5 معمولی، گواهی نامعتبر و سرور ساده‌ای که فقط برچسب HTTPS خورده رد می‌شوند. هیچ fallback به پروتکل بدون رمزنگاری وجود ندارد. انتخاب اسکن، کش نسخهٔ قبلی، فرمان ویجت و تعویض خودکار نیز این الزام را رعایت می‌کنند. سیاست در BuildConfig هنگام کامپایل تثبیت می‌شود و یک گزینهٔ قابل خاموش‌کردن در تنظیمات نیست.

HTTP مقصد داخل TLS تا پروکسی حمل می‌شود؛ فقط اتصال تا پروکسی رمزنگاری شده و اپراتور پروکسی می‌تواند دادهٔ مقصدهای فاقد رمزنگاری را ببیند. این محصول وعدهٔ رمزنگاری سرتاسری برای مقصد HTTP نمی‌دهد. UDP پروکسی در این نسخه پشتیبانی نمی‌شود.

دستور ساخت APK: `python3 scripts/build-apk-offline.py --profile play --sdk /path/to/sdk --jdk /path/to/jdk --aapt2 /path/to/google-maven-aapt2`
دستور ساخت AAB: `python3 scripts/build-bundle-offline.py --profile play --sdk /path/to/sdk --jdk /path/to/jdk --aapt2 /path/to/google-maven-aapt2 --bundletool /path/to/bundletool.jar`
در Gradle از `assemblePlayRelease` و `bundlePlayRelease` استفاده کن و signingConfig release را با کلید موجود تنظیم کن. نسخهٔ personal قابلیت‌های SOCKS5/HTTP/HTTPS عمومی را حفظ می‌کند و برای Play با الزام TLS ارائه نشده است.

## سرور لازم

سرور باید TLS با گواهی معتبر عمومی برای نام میزبانش، HTTP CONNECT و دسترسی TCP به مقصدهای کاربر داشته باشد. ورودی نمونه `https://proxy.example.com:443` است. داشتن صرفاً یک وب‌سایت HTTPS، پشتیبانی CONNECT را تضمین نمی‌کند. منابع عمومی فقط کاندیدا هستند و فراهم‌بودن یک سرور قابل‌استفاده تضمین نشده است. نسخهٔ فعلی احراز هویت پروکسی با رمز را پیاده نکرده؛ برای server دارای حساب باید پشتیبانی احراز هویت به‌طور جدا افزوده شود. VPS جدید خریداری یا سروری مستقر نشده است.

## رضایت و حریم خصوصی

پیش از نخستین اتصال در برنامه، پنل سریع یا ویجت، توضیح عبور ترافیک و دادهٔ قابل مشاهده برای طرف سوم نشان داده می‌شود و تأیید صریح لازم است. نپذیرفتن، اتصال را آغاز نمی‌کند. سرویس نیز بدون این تأیید، فرمان شروع را نمی‌پذیرد. رضایت Android برای VPN مرحلهٔ جداگانهٔ بعدی است. شمارنده‌ها، تنظیمات، نتایج و گزارش‌های اتصال روی گوشی نگه داشته می‌شوند؛ تبلیغات، تحلیل رفتار و ارسال خودکار گزارش پیاده نشده است.

پیش از انتشار واقعی، سیاست حریم خصوصی باید روی URL عمومی فعال قرار گیرد و نام ناشر و روش تماس معتبر تکمیل شود. فایل `publication/privacy-policy-draft.html` پیش‌نویس قابل بررسی است؛ این فایل هنوز عمومی نشده و مشخصات ناشر در آن تکمیل نشده است. URL نهایی را هنگام ساخت APK با `--privacy-policy-url https://your-domain/privacy` بده؛ لینک آنلاین داخل صفحهٔ حریم خصوصی فعال می‌شود. در Gradle معادل آن `-PprivacyPolicyUrl=https://your-domain/privacy` است. AAB از همان ساخت APK ایجاد می‌شود. فایل فعلی URL متعلق به ناشر ندارد و نباید این بخش را در Play تکمیل‌شده اعلام کرد.

## مراحل باقی‌ماندهٔ Google Play

ثبت/تأیید حساب توسعه‌دهنده و مالکیت بسته، تنظیم Play App Signing با کلید فعلی برای حفظ ارتقا، آزمون واقعی روی Android، آزمون موردنیاز حساب در کنسول، اظهارنامهٔ VPN و foreground service، Data Safety مطابق رفتار واقعی و اطلاعات و صفحهٔ حریم خصوصی لازم‌اند. برای بررسی گوگل باید یک endpoint TLS کارکرده و دستور دسترسی به آن ارائه شود و فیلم واقعی جریان VPN/رضایت طبق فرم رسمی تهیه شود. هیچ حساب، فرم، اعتراض، پرداخت، مدارک هویتی یا فایل VirusTotal ارسال نشده است. پذیرش نهایی و تصمیم Play Protect با گوگل است.

توضیح کوتاه پیشنهادی صفحهٔ برنامه: «ابزار VPN مبتنی بر پروکسی TLS برای اتصال TCP، آزمون سرویس‌ها و مشاهدهٔ مصرف».

مراجع رسمی بررسی‌شده در ۲۰۲۶-۱۰-۱۲:
- https://support.google.com/googleplay/android-developer/answer/12564964?hl=en
- https://support.google.com/googleplay/android-developer/answer/9859455
- https://developer.android.com/privacy-and-security/security-ssl
- https://developer.android.com/studio/publish/app-signing
- https://support.google.com/googleplay/android-developer/answer/11926878

## اگر فقط APK توزیع می‌کنی

Android Developer Console مسیر مستقل ثبت هویت و نام بسته برای توزیع خارج از Play است. https://developer.android.com/developer-verification/guides/android-developer-console
ثبت یا امضای معتبر به‌تنهایی تضمین حذف هشدار Play Protect نیست. گواهی عمومی در signing/PersianSocks-public.pem است؛ کلید خصوصی و رمز را در فرم عمومی یا چت نفرست.
