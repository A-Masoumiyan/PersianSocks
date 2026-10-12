import ir.persiansocks.app.*;
import org.json.JSONObject;

public final class ResultDisplayHarness {
    static int passed;
    static void check(boolean value, String name) { if (!value) throw new AssertionError(name); passed++; System.out.println("PASS: " + name); }
    public static void main(String[] args) throws Exception {
        check(ProxyTestResult.fromMbps(8) == 1, "8 megabits/s becomes exactly 1 megabyte/s");
        check(ProxyTestResult.speed(ProxyTestResult.fromMbps(5.75)).equals("0.72 MB/s"), "Screenshot rate 5.75 Mbps is shown as 0.72 MB/s");
        check(ProxyTestResult.speed(ProxyTestResult.fromMbps(DownloadSpeedTest.mbps(2_000_000, 1_000_000_000L))).equals("2.00 MB/s"),
            "Two million bytes in one second stays 2 MB/s through the actual engine's bit-rate conversion");
        check(ProxyTestResult.speed(0).equals("0.00 MB/s"), "A measured zero remains distinct from untested");
        check(ProxyTestResult.speed(-1).equals("تست نشده"), "Untested download is never reported as measured zero");
        check(ProxyTestResult.fromMbps(Double.NaN) == -1 && ProxyTestResult.fromMbps(Double.POSITIVE_INFINITY) == -1 && ProxyTestResult.fromMbps(-8) == -1,
            "Invalid rates cannot appear as successful measurements");
        check(ProxyTestResult.speed(.0012).equals("0.001 MB/s") && ProxyTestResult.speed(.00012).equals("0.0001 MB/s"),
            "Slow downloads retain useful precision instead of rounding to zero");
        check(ProxyTestResult.speed(.000001).equals("< 0.0001 MB/s"), "Extremely small rates have an honest lower precision label");
        ProxyTestResult legacy = ProxyTestResult.legacy("↓ 5.75 Mbps • مصرف تست: 4.92 MB", "✓ اتصال: 888 ms • 3 سرویس تأیید شد");
        check(legacy.speedValue().equals("0.72 MB/s") && legacy.downloadedBytes == 4_920_000,
            "Upgrading migrates old bit-rate notes without mistaking consumed megabytes for speed");
        check(legacy.pingValue(888).equals("888 ms"), "Ping has a standalone value and unit");
        ProxyTestResult restored = ProxyTestResult.fromJson(new JSONObject(legacy.json().toString()));
        check(restored.speedValue().equals("0.72 MB/s") && restored.downloadedBytes == legacy.downloadedBytes,
            "Restart retains the migrated byte rate without converting it by eight again");
        ProxyTestResult bytesAlready = ProxyTestResult.legacy("↓ 0.72 MB/s • مصرف تست: 1.25 MB", null);
        check(bytesAlready.speedValue().equals("0.72 MB/s"), "An already byte-based cached rate is never divided again");
        ProxyTestResult failed = ProxyTestResult.legacy("✕ سرعت: Software caused connection abort", null);
        check(failed.speedValue().equals("قطع شد") && failed.speedError.equals("Software caused connection abort"),
            "Connection abort has a short Persian card label and retains the exact cause for details");
        failed.downloadMBps = 2;
        check(failed.speedValue().equals("قطع شد"), "A failed latest test cannot expose an older successful rate as the new result");
        check(ProxyTestResult.fromJson(new JSONObject(failed.json().toString())).speedError.equals(failed.speedError),
            "Raw error details survive restart");
        check(ProxyTestResult.shortError("Read timed out").equals("مهلت تمام شد"), "Timeout has a clear short Persian label");
        check(ProxyTestResult.shortError("TLS handshake or certificate failed").equals("خطای TLS"), "TLS failure is not described as a successful download");
        check(ProxyTestResult.shortError("شبکهٔ تست در دسترس نیست").equals("عدم دسترسی"), "Network failure is concise on the card");
        check(ProxyTestResult.shortError("تست متوقف شد").equals("متوقف شد"), "User cancellation has a distinct short status");
        check(ProxyTestResult.shortError("Unrecognized platform error").equals("ناموفق"), "Unknown causes are not diagnosed from a generic exception");
        failed.probeError = "مهلت HTTPS تمام شد";
        check(failed.pingValue(888).equals("مهلت تمام شد"), "Failed ping hides its stale cached number");
        failed.probeError = "";
        check(failed.pingValue(-1).equals("تست نشده"), "Unmeasured ping is explicit");
        ProxyTestResult badCache = ProxyTestResult.fromJson(new JSONObject("{\"downloadMBps\":-8,\"downloadedBytes\":-1}"));
        check(badCache.speedValue().equals("تست نشده") && badCache.downloadedBytes == 0, "Invalid persisted values cannot create a fake measurement");
        System.out.println("RESULT: " + passed + " checks passed");
    }
}
