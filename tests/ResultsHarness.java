import ir.persiansocks.app.*;
import java.util.*;

public final class ResultsHarness {
    static int passed;
    static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
        passed++; System.out.println("PASS: " + name);
    }
    static ProxyEndpoint p(String host, long latency, boolean udp, int services, long time) {
        return new ProxyEndpoint(host, 1080, latency, udp, services, 7, time);
    }
    public static void main(String[] args) {
        ProxyEndpoint telegram = p("1.1.1.1", 900, false, 1, 500);
        ProxyEndpoint youtube = p("2.2.2.2", 20, true, 2, 100);
        ProxyEndpoint instagram = p("3.3.3.3", 250, false, 4, 300);
        ProxyEndpoint mixed = p("4.4.4.4", 100, true, 7, 200);
        ProxyEndpoint legacy = p("5.5.5.5", 1, true, 0, 999);
        List<ProxyEndpoint> original = Arrays.asList(telegram, youtube, instagram, mixed, legacy);
        List<ProxyEndpoint> saved = new ArrayList<>(original);
        List<ProxyEndpoint> all = ProxyResults.visible(original, ProxyResults.Filter.ALL, ProxyResults.Sort.FASTEST);
        check(all.size() == 4 && all.get(0) == youtube, "All shows every verified proxy and excludes untested entries");
        List<ProxyEndpoint> tg = ProxyResults.visible(original, ProxyResults.Filter.TELEGRAM, ProxyResults.Sort.FASTEST);
        check(tg.equals(Arrays.asList(mixed, telegram)), "Telegram filter excludes faster proxies that only support other services");
        check(ProxyResults.visible(original, ProxyResults.Filter.YOUTUBE, ProxyResults.Sort.FASTEST)
            .equals(Arrays.asList(youtube, mixed)), "YouTube supports multi-service matches and sorts filtered results");
        check(ProxyResults.visible(original, ProxyResults.Filter.INSTAGRAM, ProxyResults.Sort.FASTEST)
            .equals(Arrays.asList(mixed, instagram)), "Instagram supports multi-service matches and sorts filtered results");
        check(ProxyResults.visible(original, ProxyResults.Filter.TELEGRAM, ProxyResults.Sort.RECENT)
            .equals(Arrays.asList(telegram, mixed)), "Changing sort preserves active Telegram filter");
        check(ProxyResults.visible(original, ProxyResults.Filter.YOUTUBE, ProxyResults.Sort.MOST_SERVICES)
            .equals(Arrays.asList(mixed, youtube)), "Service-count sorting runs inside YouTube filter");
        check(ProxyResults.visible(original, ProxyResults.Filter.ALL, ProxyResults.Sort.UDP_FIRST)
            .equals(Arrays.asList(youtube, mixed, instagram, telegram)), "UDP priority uses latency within each group");
        check(original.equals(saved), "Filtering and sorting preserve source list and selection objects");
        check(ProxyResults.visible(Arrays.asList(telegram), ProxyResults.Filter.INSTAGRAM, ProxyResults.Sort.FASTEST)
            .isEmpty(), "An empty service filter has no fallback into unrelated proxies");
        check(ProxyResults.visible(Collections.emptyList(), ProxyResults.Filter.ALL, ProxyResults.Sort.FASTEST)
            .isEmpty(), "Empty scan results are supported");
        ProxyEndpoint unknown = p("8.8.8.8", -1, true, 7, 400);
        check(ProxyResults.visible(Arrays.asList(unknown, mixed), ProxyResults.Filter.ALL, ProxyResults.Sort.FASTEST)
            .get(0) == mixed, "Unknown latency cannot rank ahead of a measured latency");
        ProxyEndpoint equal = p("7.7.7.7", 100, true, 7, 200);
        check(ProxyResults.visible(Arrays.asList(equal, mixed), ProxyResults.Filter.ALL, ProxyResults.Sort.FASTEST)
            .get(0) == mixed, "Ties have a stable address order during live scanning");
        System.out.println("RESULT: " + passed + " checks passed");
    }
}
