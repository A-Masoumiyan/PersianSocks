package ir.persiansocks.app;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Presentation choices operate on a copy, preserving scanner and connection state. */
public final class ProxyResults {
    private ProxyResults() {}
    public enum Filter {
        ALL("همه", 0), TELEGRAM("تلگرام", ProxyEndpoint.TELEGRAM),
        YOUTUBE("یوتیوب", ProxyEndpoint.YOUTUBE), INSTAGRAM("اینستاگرام", ProxyEndpoint.INSTAGRAM), FULL("کامل", 7);
        public final String label;
        public final int bit;
        Filter(String label, int bit) { this.label = label; this.bit = bit; }
        public boolean accepts(ProxyEndpoint p) { if(!DistributionPolicy.allows(p))return false;return this == FULL ? p.full() : p.hasAnyService() && (bit == 0 || p.passed(bit)); }
    }
    public enum Protocol {
        ALL("همهٔ انواع"), SOCKS("SOCKS5"), HTTP("HTTP / HTTPS");
        public final String label;Protocol(String label){this.label=label;}
        public boolean accepts(ProxyEndpoint p){if(!DistributionPolicy.allows(p))return false;return this==ALL||(this==SOCKS)==(p.type==ProxyEndpoint.Type.SOCKS5);}
    }
    public static List<ProxyEndpoint> byProtocol(List<ProxyEndpoint> source,Protocol protocol){
        List<ProxyEndpoint> out=new ArrayList<>();for(ProxyEndpoint p:source)if(protocol.accepts(p))out.add(p);return out;
    }
    public enum PingLimit {
        NONE(0, "بدون محدودیت"), UNDER_500(500, "زیر ۵۰۰ میلی‌ثانیه"),
        UNDER_1000(1000, "زیر ۱۰۰۰ میلی‌ثانیه"), UNDER_1500(1500, "زیر ۱۵۰۰ میلی‌ثانیه"),
        UNDER_2000(2000, "زیر ۲۰۰۰ میلی‌ثانیه");
        public final int milliseconds;
        public final String label;
        PingLimit(int milliseconds, String label) { this.milliseconds = milliseconds; this.label = label; }
        public boolean accepts(ProxyEndpoint p) {
            return this == NONE || (p.latencyMs >= 0 && p.latencyMs < milliseconds);
        }
        public static PingLimit fromMilliseconds(int value) {
            for (PingLimit limit : values()) if (limit.milliseconds == value) return limit;
            return NONE;
        }
    }
    public static List<ProxyEndpoint> byPing(List<ProxyEndpoint> source, PingLimit limit) {
        List<ProxyEndpoint> out = new ArrayList<>();
        for (ProxyEndpoint p : source) if (limit.accepts(p)) out.add(p);
        return out;
    }
    public static List<ProxyEndpoint> visible(List<ProxyEndpoint> source,String key,Sort sort,Protocol protocol){
        return visible(byProtocol(source,protocol),key,sort);
    }
    public static List<ProxyEndpoint> visible(List<ProxyEndpoint> source,String key,Sort sort,Protocol protocol,PingLimit limit){
        return visible(byPing(byProtocol(source,protocol),limit),key,sort);
    }
    public enum Sort {
        FASTEST("کمترین تأخیر"), MOST_SERVICES("بیشترین سرویس تأییدشده"),
        RECENT("جدیدترین تست"), UDP_FIRST("اولویت UDP، سپس تأخیر");
        public final String label;
        Sort(String label) { this.label = label; }
    }
    public static List<ProxyEndpoint> visible(List<ProxyEndpoint> source, Filter filter, Sort sort) {
        return visible(source, filter.name(), sort);
    }
    public static boolean accepts(ProxyEndpoint p, String key) {
        if(!DistributionPolicy.allows(p))return false;
        if (key.startsWith("https://")) {
            for (ServiceTargets.Target t : ServiceTargets.current()) if (t.id.equals(key)) return p.passed(t);
            return false;
        }
        try { return Filter.valueOf(key).accepts(p); }
        catch (IllegalArgumentException ex) { return Filter.ALL.accepts(p); }
    }
    public static String label(String key) {
        for (ServiceTargets.Target t : ServiceTargets.current()) if (t.id.equals(key)) return t.name;
        try { return Filter.valueOf(key).label; }
        catch (IllegalArgumentException ex) { return Filter.ALL.label; }
    }
    public static List<ProxyEndpoint> visible(List<ProxyEndpoint> source, String key, Sort sort) {
        List<ProxyEndpoint> list = new ArrayList<>();
        for (ProxyEndpoint p : source) if (accepts(p, key)) list.add(p);
        Comparator<ProxyEndpoint> latency = Comparator.comparingLong(p -> p.latencyMs < 0 ? Long.MAX_VALUE : p.latencyMs);
        Comparator<ProxyEndpoint> comparator;
        switch (sort) {
            case MOST_SERVICES:
                comparator = Comparator.comparingInt((ProxyEndpoint p) -> -p.serviceCount()).thenComparing(latency);
                break;
            case RECENT:
                comparator = Comparator.comparingLong((ProxyEndpoint p) -> p.testedAt).reversed().thenComparing(latency);
                break;
            case UDP_FIRST:
                comparator = Comparator.comparingInt((ProxyEndpoint p) -> p.udpAdvertised ? 0 : 1).thenComparing(latency);
                break;
            default: comparator = latency;
        }
        list.sort(comparator.thenComparing(ProxyEndpoint::key));
        return list;
    }
}
