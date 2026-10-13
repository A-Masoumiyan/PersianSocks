package ir.persiansocks.app;

import java.util.List;

/** A frozen, complete filtered queue, independent of selection and visible cards. */
public final class FilteredSpeedBatch {
    private final List<ProxyEndpoint> input;
    private int done;
    private boolean cancelled;
    public FilteredSpeedBatch(List<ProxyEndpoint> source, String service, ProxyResults.Sort sort,
            ProxyResults.Protocol protocol, ProxyResults.PingLimit ping) {
        input = ProxyResults.visible(source, service, sort, protocol, ping);
    }
    public FilteredSpeedBatch(List<ProxyEndpoint> source, String service, ProxyResults.Sort sort,
            ProxyResults.Protocol protocol, ProxyResults.PingLimit ping,
            java.util.Map<String, Double> speeds, boolean smooth) {
        input = ProxyResults.visible(source, service, sort, protocol, ping, speeds, smooth);
    }
    public FilteredSpeedBatch(List<ProxyEndpoint> saved) { input=new java.util.ArrayList<>(saved); }
    public ProxyEndpoint next() { return cancelled || done >= input.size() ? null : input.get(done); }
    public void completed() { if (!cancelled && done < input.size()) done++; }
    public void cancel() { cancelled = true; }
    public int done() { return done; }
    public int total() { return input.size(); }
    public long maximumBytes() { return (long) input.size() * DownloadSpeedTest.MAX_BYTES; }
}
