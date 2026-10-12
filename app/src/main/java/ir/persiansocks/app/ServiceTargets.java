package ir.persiansocks.app;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.*;
import java.net.*;
import java.util.*;
import org.json.*;

/** A test concerns the service's public HTTPS endpoint, not login or media playback. */
public final class ServiceTargets {
    public static final class Target {
        public final int bit;
        public final String name, host, path, id;
        Target(int bit, String name, String host, String path) {
            this.bit=bit; this.name=name; this.host=host; this.path=path;
            this.id = bit == 0 ? "https://" + host + path : Integer.toString(bit);
        }
        public String url() { return "https://" + host + path; }
    }
    public static final Target[] ALL = {
        new Target(ProxyEndpoint.TELEGRAM, "تلگرام", "web.telegram.org", "/k/"),
        new Target(ProxyEndpoint.YOUTUBE, "یوتیوب", "www.youtube.com", "/"),
        new Target(ProxyEndpoint.INSTAGRAM, "اینستاگرام", "www.instagram.com", "/robots.txt")
    };
    public static final int MAX_CUSTOM = 8;
    private static volatile Target[] configured = ALL;
    public static Target[] current() { return configured.clone(); }
    public static synchronized void configure(List<Target> custom) {
        List<Target> targets = new ArrayList<>(Arrays.asList(ALL));
        Set<String> urls = new HashSet<>();
        for (Target t : ALL) urls.add(t.url());
        for (Target t : custom) {
            if (targets.size() >= ALL.length + MAX_CUSTOM) break;
            if (t.bit == 0 && urls.add(t.url())) targets.add(t);
        }
        configured = targets.toArray(new Target[0]);
    }
    public static Target custom(String name, String input) {
        try {
            String raw = input.trim();
            if (!raw.contains("://")) raw = "https://" + raw;
            URI uri = new URI(raw);
            URL url = uri.toURL();
            if (!"https".equalsIgnoreCase(url.getProtocol()) || url.getUserInfo() != null ||
                (url.getPort() != -1 && url.getPort() != 443)) throw new IllegalArgumentException();
            String host = IDN.toASCII(url.getHost()).toLowerCase(Locale.ROOT);
            if (host.length() > 253 || !host.matches("[a-z0-9.-]+") || !host.contains(".") ||
                host.matches("[0-9.]+") || host.endsWith(".local") || host.endsWith(".localhost"))
                throw new IllegalArgumentException();
            for (String part : host.split("\\.", -1))
                if (part.isEmpty() || part.length() > 63 || part.startsWith("-") || part.endsWith("-"))
                    throw new IllegalArgumentException();
            URI ascii = new URI(uri.toASCIIString());
            String path = ascii.getRawPath();
            if (path == null || path.isEmpty()) path = "/";
            if (ascii.getRawQuery() != null) path += "?" + ascii.getRawQuery();
            if (path.length() > 1024 || path.matches(".*[\\r\\n\\x00-\\x20].*")) throw new IllegalArgumentException();
            String label = name.trim();
            if (label.isEmpty()) label = host;
            if (label.length() > 30 || label.matches(".*[\\r\\n].*")) throw new IllegalArgumentException();
            return new Target(0, label, host, path);
        } catch (Exception ex) { throw new IllegalArgumentException("دامنه یا نشانی HTTPS معتبر وارد کن"); }
    }
    public static List<Target> decode(String json) {
        List<Target> out = new ArrayList<>();
        try {
            JSONArray rows = new JSONArray(json);
            for (int i = 0; i < rows.length() && out.size() < MAX_CUSTOM; i++) {
                try {
                    JSONObject row = rows.getJSONObject(i);
                    out.add(custom(row.optString("name"), row.optString("url")));
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return out;
    }
    public static String encode(List<Target> targets) {
        JSONArray rows = new JSONArray();
        for (Target t : targets) if (t.bit == 0) {
            JSONObject row = new JSONObject();
            try { row.put("name", t.name); row.put("url", t.url()); rows.put(row); }
            catch (Exception ignored) {}
        }
        return rows.toString();
    }
    private ServiceTargets() {}
    public static int exchange(Socket connected, Target target, int timeout) throws Exception {
        try (SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                .createSocket(connected, target.host, 443, true)) {
            tls.setSoTimeout(timeout);
            DistributionPolicy.configureTls(tls);
            tls.startHandshake();
            tls.getOutputStream().write(("GET " + target.path + " HTTP/1.1\r\nHost: " + target.host +
                "\r\nUser-Agent: Mozilla/5.0 (Android) PersianSocks/0.10.0\r\nAccept-Encoding: identity" +
                "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            tls.getOutputStream().flush();
            InputStream in=tls.getInputStream();
            String line=readLine(in,512);
            if(!line.matches("HTTP/1\\.[01] [0-9]{3}( .*)?")) throw new IOException("Malformed HTTP status");
            int code=Integer.parseInt(line.substring(9,12));
            if(code<200 || code>=400) throw new IOException("HTTP " + code);
            // Receive the complete header, not just a status prefix.
            int headerBytes=0;
            for(int count=0;count<100;count++) {
                line=readLine(in,8192); headerBytes+=line.length();
                if(line.isEmpty()) return code;
                if(headerBytes>32768) throw new IOException("HTTP headers too large");
            }
            throw new IOException("Incomplete HTTP headers");
        }
    }
    private static String readLine(InputStream in,int max) throws Exception {
        StringBuilder out=new StringBuilder();
        for(int n=0;n<max;n++) {
            int c=in.read(); if(c<0) throw new EOFException();
            if(c=='\n') return out.toString();
            if(c!='\r') out.append((char)c);
        }
        throw new IOException("HTTP line too long");
    }
}
