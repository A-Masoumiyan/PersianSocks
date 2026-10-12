package ir.persiansocks.app;

import org.json.JSONObject;
import java.util.Locale;
import java.util.*;
import org.json.JSONArray;

/** Published IPv4 endpoints; manually supplied TLS proxy hostnames are also supported. */
public final class ProxyEndpoint {
    public enum Type { SOCKS5(1), HTTP(2), HTTPS(4); public final int bit; Type(int bit){this.bit=bit;} }
    public final Type type;
    public final String host;
    public final int port;
    public final long latencyMs;
    public final boolean udpAdvertised;
    public static final int TELEGRAM = 1, YOUTUBE = 2, INSTAGRAM = 4;
    public final int serviceMask, checkedMask;
    public final long testedAt;
    public final long latencyAt;
    public final Set<String> extraPassed, extraChecked;
    public final String countryCode;

    public ProxyEndpoint(String host, int port, long latencyMs, boolean udpAdvertised) {
        this(host, port, latencyMs, udpAdvertised, 0, 0, 0);
    }
    public ProxyEndpoint(String host, int port, long latencyMs, boolean udpAdvertised,
            int serviceMask, int checkedMask, long testedAt) {
        this(host, port, latencyMs, udpAdvertised, serviceMask, checkedMask, testedAt,
            Collections.emptySet(), Collections.emptySet(), "");
    }
    public ProxyEndpoint(String host, int port, long latencyMs, boolean udpAdvertised,
            int serviceMask, int checkedMask, long testedAt, Set<String> extraPassed,
            Set<String> extraChecked, String countryCode) {
        this(host,port,latencyMs,udpAdvertised,serviceMask,checkedMask,testedAt,extraPassed,extraChecked,countryCode,Type.SOCKS5);
    }
    public ProxyEndpoint(String host,int port,long latencyMs,boolean udpAdvertised,int serviceMask,int checkedMask,
            long testedAt,Set<String> extraPassed,Set<String> extraChecked,String countryCode,Type type) {
        this(host,port,latencyMs,udpAdvertised,serviceMask,checkedMask,testedAt,extraPassed,extraChecked,countryCode,type,testedAt);
    }
    private ProxyEndpoint(String host,int port,long latencyMs,boolean udpAdvertised,int serviceMask,int checkedMask,
            long testedAt,Set<String> extraPassed,Set<String> extraChecked,String countryCode,Type type,long latencyAt) {
        this.type=type;
        this.host = host;
        this.port = port;
        this.latencyMs = latencyMs;
        this.udpAdvertised = udpAdvertised;
        this.serviceMask = serviceMask & 7;
        this.checkedMask = checkedMask & 7;
        this.testedAt = testedAt;
        this.latencyAt = latencyAt;
        this.extraPassed = Collections.unmodifiableSet(new HashSet<>(extraPassed));
        this.extraChecked = Collections.unmodifiableSet(new HashSet<>(extraChecked));
        this.countryCode = countryCode == null ? "" : countryCode;
    }
    public String address() { return host + ":" + port; }
    public String key(){return type==Type.SOCKS5?address():type.name().toLowerCase(Locale.US)+"://"+address();}
    public ProxyEndpoint withType(Type t){return new ProxyEndpoint(host,port,latencyMs,udpAdvertised,serviceMask,checkedMask,testedAt,extraPassed,extraChecked,countryCode,t,latencyAt);}
    public ProxyEndpoint withLatency(long value){return new ProxyEndpoint(host,port,value,udpAdvertised,serviceMask,checkedMask,testedAt,extraPassed,extraChecked,countryCode,type,System.currentTimeMillis());}
    public boolean isPublicIpv4() {
        String[] parts = host.split("\\.");
        if(parts.length!=4 || !host.matches("[0-9.]+"))return false;
        int a = Integer.parseInt(parts[0]), b = Integer.parseInt(parts[1]);
        return a > 0 && a < 224 && a != 10 && a != 127 &&
               !(a == 169 && b == 254) && !(a == 172 && b >= 16 && b <= 31) &&
               !(a == 192 && b == 168) && !(a == 100 && b >= 64 && b <= 127) &&
               !(a == 198 && (b == 18 || b == 19));
    }
    public JSONObject json() {
        JSONObject o = new JSONObject();
        try {
            o.put("host", host); o.put("port", port); o.put("latency", latencyMs); o.put("udp", udpAdvertised);
            o.put("services", serviceMask); o.put("checked", checkedMask); o.put("testedAt", testedAt);
            o.put("latencyAt",latencyAt);
            o.put("extraPassed", new JSONArray(extraPassed)); o.put("extraChecked", new JSONArray(extraChecked));
            o.put("country", countryCode); o.put("type",type.name());
        }
        catch (Exception ignored) {}
        return o;
    }
    public static ProxyEndpoint fromJson(JSONObject o) {
        ProxyEndpoint p=new ProxyEndpoint(o.optString("host"), o.optInt("port"), o.optLong("latency", -1), o.optBoolean("udp", false),
            o.optInt("services", 0), o.optInt("checked", 0), o.optLong("testedAt", 0),
            strings(o.optJSONArray("extraPassed")), strings(o.optJSONArray("extraChecked")), o.optString("country"), typeOf(o.optString("type","SOCKS5")),o.optLong("latencyAt",o.optLong("testedAt",0)));
        return p;
    }
    private static Type typeOf(String value){try{return Type.valueOf(value);}catch(Exception ignored){return Type.SOCKS5;}}
    private static Set<String> strings(JSONArray array) {
        Set<String> out = new HashSet<>();
        if (array != null) for (int i = 0; i < Math.min(array.length(), 32); i++) {
            String value = array.optString(i);
            if (!value.isEmpty() && value.length() <= 1300) out.add(value);
        }
        return out;
    }
    public boolean hasAnyService() {
        if (serviceMask != 0) return true;
        for (ServiceTargets.Target t : ServiceTargets.current()) if (passed(t)) return true;
        return false;
    }
    public boolean passed(ServiceTargets.Target target) { return target.bit != 0 ? passed(target.bit) : extraPassed.contains(target.id); }
    public boolean checked(ServiceTargets.Target target) { return target.bit != 0 ? (checkedMask & target.bit) != 0 : extraChecked.contains(target.id); }
    public int serviceCount() {
        int count = 0; for (ServiceTargets.Target t : ServiceTargets.current()) if (passed(t)) count++;
        return count;
    }
    public boolean full() { return (serviceMask & 7) == 7; }
    public boolean passed(int bit) { return (serviceMask & bit) != 0; }
    public String serviceStatus(int bit) {
        return passed(bit) ? "✓ پاسخ تأیید شد" : (checkedMask & bit) != 0 ? "✕ پاسخ تأیید نشد" : "• آزمایش نشد";
    }
    public String passedServices() {
        StringBuilder b = new StringBuilder();
        for (ServiceTargets.Target target : ServiceTargets.current()) if (passed(target)) {
            if (b.length() > 0) b.append("، "); b.append(target.name);
        }
        return b.length() == 0 ? "هیچ سرویس تأیید نشده" : b.toString();
    }
    public static ProxyEndpoint parse(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        Type type=Type.SOCKS5;
        if (s.startsWith("socks5://")) s = s.substring(9);
        else if(s.startsWith("http://")){type=Type.HTTP;s=s.substring(7);}
        else if(s.startsWith("https://")){type=Type.HTTPS;s=s.substring(8);}
        String[] pair = s.split(":", -1);
        if (pair.length != 2) return null;
        String[] pieces = pair[0].split("\\.", -1);
        if(pieces.length!=4||!pair[0].matches("[0-9.]+")){
            if(type!=Type.HTTPS)return null;
            try{String h=java.net.IDN.toASCII(pair[0],java.net.IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.US);
                int p=Integer.parseInt(pair[1]);
                if(h.length()>253||!h.contains(".")||h.endsWith(".")||p<1||p>65535)return null;
                return new ProxyEndpoint(h,p,-1,false).withType(type);
            }catch(Exception ex){return null;}
        }
        try {
            for (String octet : pieces) {
                if (octet.length() < 1 || octet.length() > 3) return null;
                if (!octet.matches("[0-9]+")) return null;
                int x = Integer.parseInt(octet);
                if (x > 255 || x < 0) return null;
            }
            int p = Integer.parseInt(pair[1]);
            if (p < 1 || p > 65535) return null;
            String h = String.format(Locale.US, "%d.%d.%d.%d", Integer.parseInt(pieces[0]),
                    Integer.parseInt(pieces[1]), Integer.parseInt(pieces[2]), Integer.parseInt(pieces[3]));
            return new ProxyEndpoint(h, p, -1, false).withType(type);
        } catch (NumberFormatException e) { return null; }
    }
}
