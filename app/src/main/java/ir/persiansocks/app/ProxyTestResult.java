package ir.persiansocks.app;

import java.util.Locale;
import java.util.regex.*;
import org.json.JSONObject;

/** Numeric values are stored in decimal megabytes per second; raw errors belong in details. */
public final class ProxyTestResult {
    public double downloadMBps = -1;
    public long downloadedBytes;
    public long speedAt,probeAt;
    public String speedError = "", probeError = "", probeInfo = "";
    public static double fromMbps(double mbps) {
        return Double.isFinite(mbps) && mbps >= 0 ? mbps / 8.0 : -1;
    }
    public static String speed(double megabytesPerSecond) {
        if (!Double.isFinite(megabytesPerSecond) || megabytesPerSecond < 0) return "تست نشده";
        if (megabytesPerSecond > 0 && megabytesPerSecond < .0001) return "< 0.0001 MB/s";
        int digits = megabytesPerSecond > 0 && megabytesPerSecond < .001 ? 4 : megabytesPerSecond > 0 && megabytesPerSecond < .01 ? 3 : 2;
        return String.format(Locale.US, "%." + digits + "f MB/s", megabytesPerSecond);
    }
    public String speedValue() { return speedError.isEmpty() ? speed(downloadMBps) : shortError(speedError); }
    public String pingValue(long latency) { return !probeError.isEmpty() ? shortError(probeError) : latency < 0 ? "تست نشده" : latency + " ms"; }
    public static String shortError(String error) {
        String lower = error.toLowerCase(Locale.ROOT);
        if (lower.contains("متوقف") || lower.contains("cancel")) return "متوقف شد";
        if (lower.contains("abort") || lower.contains("reset") || lower.contains("broken pipe") || lower.contains("socket closed") || lower.contains("قطع")) return "قطع شد";
        if (lower.contains("timeout") || lower.contains("timed out") || lower.contains("مهلت")) return "مهلت تمام شد";
        if (lower.contains("tls") || lower.contains("ssl") || lower.contains("certificate") || lower.contains("گواهی")) return "خطای TLS";
        if (lower.contains("شبکه") || lower.contains("network") || lower.contains("unknownhost") || lower.contains("resolve")) return "عدم دسترسی";
        if (lower.contains("http") || lower.contains("پاسخ") || lower.contains("chunk") || lower.contains("بدنه")) return "پاسخ نامعتبر";
        return "ناموفق";
    }
    public JSONObject json() {
        JSONObject json = new JSONObject();
        try {
            json.put("downloadMBps", Double.isFinite(downloadMBps) && downloadMBps >= 0 ? downloadMBps : -1);
            json.put("downloadedBytes", Math.max(0, downloadedBytes));json.put("speedAt",speedAt);json.put("probeAt",probeAt);
            json.put("speedError", speedError); json.put("probeError", probeError); json.put("probeInfo", probeInfo);
        } catch (Exception ignored) {}
        return json;
    }
    public static ProxyTestResult fromJson(JSONObject json) {
        ProxyTestResult result = new ProxyTestResult();
        double speed = json.optDouble("downloadMBps", -1);
        result.downloadMBps = Double.isFinite(speed) && speed >= 0 ? speed : -1;
        result.downloadedBytes = Math.max(0, json.optLong("downloadedBytes", 0));
        result.speedError = json.optString("speedError"); result.probeError = json.optString("probeError");
        result.speedAt=Math.max(0,json.optLong("speedAt"));result.probeAt=Math.max(0,json.optLong("probeAt"));result.probeInfo = json.optString("probeInfo"); return result;
    }
    public static ProxyTestResult legacy(String speedNote, String probeNote) {
        ProxyTestResult result = new ProxyTestResult();
        if (speedNote != null) {
            if (speedNote.startsWith("✕")) result.speedError = legacyError(speedNote);
            else {
                Matcher rate = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*(Mbps|MB/s)").matcher(speedNote);
                if (rate.find()) try {
                    double value = Double.parseDouble(rate.group(1));
                    result.downloadMBps = rate.group(2).equals("Mbps") ? fromMbps(value) : value;
                } catch (Exception ignored) {}
                Matcher volume = Pattern.compile("مصرف تست:\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(GB|MB|KB|B)").matcher(speedNote);
                if (volume.find()) try {
                    long scale = volume.group(2).equals("GB") ? 1_000_000_000L : volume.group(2).equals("MB") ? 1_000_000 : volume.group(2).equals("KB") ? 1000 : 1;
                    result.downloadedBytes = Math.max(0, Math.round(Double.parseDouble(volume.group(1)) * scale));
                } catch (Exception ignored) {}
            }
        }
        if (probeNote != null) {
            if (probeNote.startsWith("✕")) result.probeError = legacyError(probeNote);
            else result.probeInfo = probeNote;
        }
        return result;
    }
    private static String legacyError(String note) { int colon = note.indexOf(':'); return colon >= 0 ? note.substring(colon + 1).trim() : note; }
}
