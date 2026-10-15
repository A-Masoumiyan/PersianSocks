import ir.persiansocks.app.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.sun.net.httpserver.HttpServer;

public final class HProxySourceHarness {
    static int checks;
    static final long NOW=1791972000000L;
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;System.out.println("PASS: "+message);}
    static JSONObject row(int suffix,int minutes)throws Exception {
        return new JSONObject().put("ip","8.8.8."+suffix).put("port",1080).put("protocols",new JSONArray().put("socks5"))
            .put("status","alive").put("latency_ms",300).put("uptime_pct",98).put("uptime_24h",95)
            .put("verification_count",100).put("last_verified_at",Instant.ofEpochMilli(NOW-minutes*60_000L).toString());
    }
    static HProxySource.Result parse(JSONArray rows)throws Exception{return HProxySource.parse(rows.toString(),NOW,new AtomicBoolean(false),false);}
    static void reject(JSONObject row,String message)throws Exception{check(parse(new JSONArray().put(row)).endpoints.isEmpty(),message);}
    static ProxyScanner.Source source(String url){return new ProxyScanner.Source("quality fixture",url,"",ProxyEndpoint.Type.SOCKS5,ProxyScanner.Source.Format.HPROXY_QUALITY);}
    static final class Collected implements ProxyScanner.CollectionListener {
        final CountDownLatch done=new CountDownLatch(1);List<ProxyEndpoint> candidates;String report;
        public void onProgress(String message){}
        public void onFinished(List<ProxyEndpoint> rows,String report){candidates=rows;this.report=report;done.countDown();}
        public void onFailed(String error){report=error;done.countDown();}
        void await()throws Exception{check(done.await(8,TimeUnit.SECONDS)&&candidates!=null,"Collection completes without a proxy probe");}
    }
    public static void main(String[] args)throws Exception {
        check(SourceCatalog.DEFAULT.length==17&&SourceCatalog.DEFAULT[0].name.equals("Roosterkid")&&Arrays.stream(SourceCatalog.DEFAULT).allMatch(s->s.format==ProxyScanner.Source.Format.TEXT),"Version 18 raw source catalog restored without quality API preference");
        ProxyEndpoint p=parse(new JSONArray().put(row(1,10))).endpoints.get(0);
        check(p.type==ProxyEndpoint.Type.SOCKS5&&p.latencyMs==-1&&p.latencyAt==0&&p.testedAt==0&&p.serviceMask==0&&p.checkedMask==0&&!p.udpAdvertised,
            "Provider ping and alive status never become local connection evidence");
        reject(row(2,10).put("protocols",new JSONArray().put("http")),"HTTP metadata never gets relabelled SOCKS5");
        reject(row(2,10).put("status","recently_alive"),"Recently alive status is rejected");
        reject(row(2,10).put("latency_ms",1501),"Latency above 1500ms is rejected even if API ignored the filter");
        reject(row(2,10).put("uptime_pct",89.99),"Low lifetime uptime is rejected");
        reject(row(2,10).put("uptime_24h",89.99),"Low daily uptime is rejected");
        reject(row(2,10).put("verification_count",3),"A 100 percent result with too few checks is rejected");
        reject(row(2,10).put("latency_ms",JSONObject.NULL),"Missing latency cannot pass quality validation");
        reject(row(2,10).put("verification_count","100"),"String fields do not silently count as measurements");
        reject(row(2,10).put("port",1080.5),"Fractional ports are rejected");
        reject(row(2,10).put("ip","192.168.1.2"),"Private candidates are rejected");
        reject(row(2,10).put("ip","invalid.example"),"Hostnames cannot bypass public IPv4 validation");
        reject(row(2,121),"Checks older than two hours are rejected");
        reject(row(2,-2),"Future checks beyond the clock-skew allowance are rejected");
        reject(row(2,10).put("last_verified_at","invalid"),"Malformed timestamps are rejected");
        reject(row(2,10).put("uptime_pct",101),"Impossible percentage values are rejected");
        reject(row(2,10).put("verification_count",10.5),"Fractional verification counts are rejected");
        check(parse(new JSONArray().put(row(2,120))).endpoints.size()==1,"The two-hour boundary is inclusive");
        check(parse(new JSONArray().put(row(1,70)).put(row(1,5))).endpoints.size()==1,"Repeated endpoints are deduplicated");
        JSONArray fresh=new JSONArray();for(int i=1;i<=20;i++)fresh.put(row(i,i));fresh.put(row(21,50));
        HProxySource.Result strict=parse(fresh);
        check(strict.windowMinutes==30&&strict.endpoints.size()==20,"A sufficiently large fresh pool never widens its age window");
        JSONArray medium=new JSONArray().put(row(1,10));for(int i=2;i<=21;i++)medium.put(row(i,50).put("uptime_pct",100).put("uptime_24h",100));medium.put(row(22,90));
        HProxySource.Result wider=parse(medium);
        check(wider.windowMinutes==60&&wider.endpoints.size()==21&&wider.endpoints.get(0).host.equals("8.8.8.1"),"Sparse fresh pools widen to 60 minutes while fresh candidates keep priority");
        check(parse(new JSONArray().put(row(1,90))).windowMinutes==120,"The final relaxation is bounded at 120 minutes");
        try{HProxySource.parse("<html>8.8.8.8:1080</html>",NOW,new AtomicBoolean(),false);throw new AssertionError("HTML accepted");}catch(JSONException expected){checks++;}
        JSONArray huge=new JSONArray();for(int i=0;i<=300;i++)huge.put(row(1,10));
        try{parse(huge);throw new AssertionError("Oversized rows accepted");}catch(IOException expected){checks++;}
        try{HProxySource.parse("[]",NOW,new AtomicBoolean(true),false);throw new AssertionError("Cancelled empty response accepted");}catch(IOException expected){checks++;}
        AtomicInteger downloads=new AtomicInteger();String cacheBody=new JSONArray().put(row(1,119)).toString();String cacheKey="cache-"+UUID.randomUUID();
        HProxySource.fetch(cacheKey,NOW,new AtomicBoolean(),()->{downloads.incrementAndGet();return cacheBody;});
        HProxySource.Result cached=HProxySource.fetch(cacheKey,NOW+2*60_000L,new AtomicBoolean(),()->{downloads.incrementAndGet();return cacheBody;});
        check(downloads.get()==1&&cached.cached&&cached.endpoints.isEmpty(),"Cached metadata is revalidated and expires candidates before the next API download");
        String retryKey="retry-"+UUID.randomUUID();
        for(int i=0;i<2;i++)try{HProxySource.fetch(retryKey,NOW+i*1000,new AtomicBoolean(),()->{downloads.incrementAndGet();throw new HProxySource.RateLimited(60_000);});}catch(HProxySource.RateLimited expected){}
        check(downloads.get()==2,"Retry-After prevents a second request during the cooldown");
        HProxySource.fetch(retryKey,NOW+61_000,new AtomicBoolean(),()->{downloads.incrementAndGet();return "[]";});
        check(downloads.get()==3,"A request is permitted again after cooldown");
        check(HProxySource.retryDelay("12",NOW)==12_000&&HProxySource.retryDelay("bad",NOW)==60_000,"Numeric and missing Retry-After values are bounded");
        String date=java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(NOW+30_000).atZone(ZoneOffset.UTC));
        check(HProxySource.retryDelay(date,NOW)==30_000,"HTTP-date Retry-After is respected");
        ProxyEndpoint raw=ProxyEndpoint.parse("9.9.9.9:1080"),backup=ProxyEndpoint.parse("1.1.1.1:1080");
        List<ProxyEndpoint> preferred=ProxyScanner.mergePreferredCandidates(Collections.emptyList(),Arrays.asList(Arrays.asList(raw,p),Arrays.asList(p)),Arrays.asList(false,true),Arrays.asList(backup),2);
        check(preferred.size()==2&&preferred.get(0).key().equals(p.key())&&preferred.get(1).key().equals(raw.key()),"Quality candidates precede raw sources within a bounded scan and deduplicate");
        Path folder=Files.createTempDirectory("source-catalog-priority-");ScanFiles files=new ScanFiles(folder.toFile());files.saveCatalog(Arrays.asList(raw,backup));
        ScanCheckpoint paused=new ScanCheckpoint(Arrays.asList(raw,backup),new ProxyScanner.Options(100,false),"CATALOG");files.save(paused);
        files.mergeCatalog(Arrays.asList(p,raw));
        check(files.catalog().size()==3&&files.catalog().get(0).key().equals(p.key())&&files.checkpoint().candidate(0).key().equals(raw.key()),"Catalog refresh preserves priority, older entries, and a paused scan's original order");
        files.mergeCatalog(Collections.emptyList());check(files.catalogCount()==3,"An empty or failed source refresh never erases the saved catalog");
        AtomicInteger networkCalls=new AtomicInteger();HttpServer server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
        String live=new JSONArray().put(row(3,0).put("last_verified_at",Instant.now().minusSeconds(60).toString())).toString();
        server.createContext("/quality",exchange->{networkCalls.incrementAndGet();byte[] body=live.getBytes("UTF-8");exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);try(OutputStream out=exchange.getResponseBody()){out.write(body);}});
        server.createContext("/html",exchange->{byte[] body="<html>8.8.8.8:1080</html>".getBytes("UTF-8");exchange.getResponseHeaders().set("Content-Type","text/html");exchange.sendResponseHeaders(200,body.length);try(OutputStream out=exchange.getResponseBody()){out.write(body);}});
        server.createContext("/raw",exchange->{byte[] body="9.9.9.9:1080".getBytes("UTF-8");exchange.sendResponseHeaders(200,body.length);try(OutputStream out=exchange.getResponseBody()){out.write(body);}});
        server.start();String base="http://127.0.0.1:"+server.getAddress().getPort();
        Socks5Probe.setSocketMaker(()->{throw new AssertionError("Collection opened a proxy-test socket");});
        try{
            ProxyScanner scanner=new ProxyScanner();Collected got=new Collected();scanner.collect(Collections.emptyList(),Collections.emptyList(),Arrays.asList(new ProxyScanner.Source("raw",base+"/raw",""),source(base+"/quality")),got);got.await();
            check(got.candidates.size()==2&&got.candidates.get(0).host.equals("9.9.9.9")&&got.report.contains("تأیید اتصال پس از اسکن"),"Real source download uses v18 source order and identifies candidates as unverified");
            Collected repeat=new Collected();scanner.collect(Collections.emptyList(),Collections.emptyList(),Arrays.asList(source(base+"/quality")),repeat);repeat.await();
            check(networkCalls.get()==1&&repeat.report.contains("حافظهٔ موقت"),"Collection uses the shared metadata cache on a repeated fetch");
            Collected invalid=new Collected();scanner.collect(Collections.emptyList(),Collections.emptyList(),Arrays.asList(source(base+"/html"),new ProxyScanner.Source("raw",base+"/raw","")),invalid);invalid.await();
            check(invalid.candidates.size()==1&&invalid.candidates.get(0).host.equals("9.9.9.9"),"HTTP 200 HTML cannot produce fake quality candidates and raw fallback still works");
            scanner.stop();
        }finally{server.stop(0);Socks5Probe.setSocketMaker(null);}
        System.out.println("RESULT: "+checks+" HProxy source checks passed");
    }
}
