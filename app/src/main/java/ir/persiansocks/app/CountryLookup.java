package ir.persiansocks.app;

import java.io.*;
import java.nio.*;
import java.util.Locale;

/** Compact, offline DB-IP IPv4 country ranges. No DNS or external lookup requests. */
public final class CountryLookup {
    private static volatile ByteBuffer ranges;
    private static volatile int count;
    private CountryLookup() {}
    public static synchronized void initialize(InputStream input) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] block = new byte[8192]; int n;
            while ((n = in.read(block)) != -1) {
                out.write(block, 0, n);
                if (out.size() > 12_000_000) throw new IOException("Country database too large");
            }
            byte[] bytes = out.toByteArray();
            ByteBuffer data = ByteBuffer.wrap(bytes).asReadOnlyBuffer().order(ByteOrder.BIG_ENDIAN);
            if (bytes.length < 8 || data.getInt(0) != 0x50534343) throw new IOException("Country database format");
            int records = data.getInt(4);
            if (records < 0 || bytes.length != 8L + records * 10L) throw new IOException("Country database length");
            count = records; ranges = data;
        }
    }
    public static String lookup(String host) {
        ByteBuffer data = ranges;
        ProxyEndpoint parsed = ProxyEndpoint.parse(host + ":1");
        if (data == null || parsed == null || !parsed.isPublicIpv4()) return "";
        long address = 0;
        for (String part : host.split("\\.")) address = (address << 8) | Integer.parseInt(part);
        int lo = 0, hi = count - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1, offset = 8 + mid * 10;
            long start = Integer.toUnsignedLong(data.getInt(offset));
            long end = Integer.toUnsignedLong(data.getInt(offset + 4));
            if (address < start) hi = mid - 1;
            else if (address > end) lo = mid + 1;
            else {
                String code = "" + (char) data.get(offset + 8) + (char) data.get(offset + 9);
                return code.equals("ZZ") || code.equals("--") ? "" : code;
            }
        }
        return "";
    }
    public static String display(String code) { return display(code,new Locale("fa")); }
    public static String display(String code,Locale locale) {
        if (!code.matches("[A-Z]{2}")) return "نامشخص";
        String name = new Locale("", code).getDisplayCountry(locale);
        String flag = new String(Character.toChars(0x1F1E6 + code.charAt(0) - 'A')) +
            new String(Character.toChars(0x1F1E6 + code.charAt(1) - 'A'));
        return flag + " " + name;
    }
}
