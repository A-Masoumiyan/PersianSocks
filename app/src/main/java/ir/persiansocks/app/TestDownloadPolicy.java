package ir.persiansocks.app;

/** Exact HTTPS file URL on a configured test domain; no silent alternate destination. */
public final class TestDownloadPolicy {
    public static final int MIN_BYTES=256_000;
    private TestDownloadPolicy(){}
    public static String validate(String input){
        ServiceTargets.Target parsed=ServiceTargets.custom("download",input);
        if(input.length()>2048||input.contains("#"))throw new IllegalArgumentException("لینک فایل معتبر نیست");
        for(ServiceTargets.Target t:ServiceTargets.current())if(t.host.equals(parsed.host))return parsed.url();
        throw new IllegalArgumentException("دامنهٔ لینک باید در دامنه‌های تست باشد");
    }
}
