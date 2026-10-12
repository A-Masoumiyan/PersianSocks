package ir.persiansocks.app;

import java.util.*;

/** Only freshly tested, compatible results can initiate widget auto-connect. */
public final class AutoScanSelection {
    private final String filter;
    private final ProxyResults.Protocol protocol;
    private final ProxyResults.Sort sort;
    private final ProxyResults.PingLimit pingLimit;
    private int matches;
    private final Map<String,ProxyEndpoint> fresh = new LinkedHashMap<>();
    public AutoScanSelection(String filter,ProxyResults.Sort sort) { this(filter,sort,ProxyResults.Protocol.ALL); }
    public AutoScanSelection(String filter,ProxyResults.Sort sort,ProxyResults.Protocol protocol){this(filter,sort,protocol,ProxyResults.PingLimit.NONE); }
    public AutoScanSelection(String filter,ProxyResults.Sort sort,ProxyResults.Protocol protocol,ProxyResults.PingLimit pingLimit){this.filter=filter;this.sort=sort;this.protocol=protocol;this.pingLimit=pingLimit; }
    private boolean matches(ProxyEndpoint p){return p!=null&&protocol.accepts(p)&&pingLimit.accepts(p)&&ProxyResults.accepts(p,filter);}
    public synchronized void add(ProxyEndpoint endpoint) { if(endpoint.hasAnyService()){ProxyEndpoint old=fresh.put(endpoint.key(),endpoint);if(matches(old))matches--;if(matches(endpoint))matches++;} }
    public synchronized List<ProxyEndpoint> results() { return new ArrayList<>(fresh.values()); }
    public synchronized List<ProxyEndpoint> compatible() { return ProxyResults.visible(results(),filter,sort,protocol,pingLimit); }
    public synchronized ProxyEndpoint best() { if(matches==0)return null;List<ProxyEndpoint> list=compatible();return list.isEmpty()?null:list.get(0); }
    public synchronized boolean haveBackups() { return matches>=3; }
    public synchronized List<ProxyEndpoint> mergeSaved(List<ProxyEndpoint> previous) {
        Map<String,ProxyEndpoint> merged=new LinkedHashMap<>();
        for(ProxyEndpoint p:compatible())merged.put(p.key(),p);
        for(ProxyEndpoint p:fresh.values())merged.putIfAbsent(p.key(),p);
        for(ProxyEndpoint p:previous)merged.putIfAbsent(p.key(),p);
        return new ArrayList<>(merged.values());
    }
}
