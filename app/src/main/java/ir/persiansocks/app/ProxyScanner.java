package ir.persiansocks.app;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.*;

/** Downloads published lists; never enumerates address ranges. */
public final class ProxyScanner {
    public static final int MAX_TESTS = 100000;
    public static final class Source {
        public enum Format { TEXT, HPROXY_QUALITY }
        public final String name, url, mirror;
        public final ProxyEndpoint.Type type;
        public final Format format;
        public Source(String name, String url, String mirror) { this(name,url,mirror,ProxyEndpoint.Type.SOCKS5); }
        public Source(String name,String url,String mirror,ProxyEndpoint.Type type){this(name,url,mirror,type,Format.TEXT);}
        public Source(String name,String url,String mirror,ProxyEndpoint.Type type,Format format){
            if(format==Format.HPROXY_QUALITY&&type!=ProxyEndpoint.Type.SOCKS5)throw new IllegalArgumentException("HProxy quality source requires SOCKS5");
            this.name=name;this.url=url;this.mirror=mirror;this.type=type;this.format=format;
        }
    }
    public static final class Options {
        public final int maxTests, types;
        public final boolean slow, scanAll;
        public Options(int count, boolean slow) { this(count,slow,false); }
        public Options(int count, boolean slow, boolean scanAll) {
            this(count,slow,scanAll,7);
        }
        public Options(int count,boolean slow,boolean scanAll,int types){
            this.types=DistributionPolicy.scanTypes(types);
            this.scanAll=scanAll;
            maxTests=scanAll?Integer.MAX_VALUE:Math.max(100,Math.min(MAX_TESTS,count));
            this.slow=slow;
        }
    }
    private static final Pattern ENDPOINT = Pattern.compile("(?<![0-9.])((?:[0-9]{1,3}\\.){3}[0-9]{1,3}):([0-9]{1,5})(?![0-9])");
    private static final class ScanSession {
        final Options options;
        ScanSession(Options options){this.options=options;}
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        final Set<HttpURLConnection> downloads = ConcurrentHashMap.newKeySet();
        final ScheduledExecutorService deadlines=Executors.newScheduledThreadPool(2);
        volatile ExecutorService fetchPool, probePool;
        Thread thread;
    }
    private static final class DownloadResult {
        final int index; final List<ProxyEndpoint> endpoints; final String report;
        final boolean preferred;
        DownloadResult(int i,List<ProxyEndpoint> e,String r) { this(i,e,r,false); }
        DownloadResult(int i,List<ProxyEndpoint> e,String r,boolean preferred) { index=i; endpoints=e; report=r;this.preferred=preferred; }
    }
    private ScanSession current;
    public interface Listener {
        void onProgress(int done,int total,int healthy);
        void onFound(ProxyEndpoint proxy);
        void onFinished(List<ProxyEndpoint> results,String message);
        default void onCollectProgress(String text) {}
        default void onDiagnostics(String report) {}
    }
    public interface CollectionListener {
        void onProgress(String message);
        void onFinished(List<ProxyEndpoint> candidates, String report);
        void onFailed(String message);
    }
    public synchronized void collect(List<ProxyEndpoint> previous, List<ProxyEndpoint> fallback, List<Source> sources,
            CollectionListener listener) {
        stop(); ScanSession session = new ScanSession(new Options(MAX_TESTS, false, true, 7)); current = session;
        List<Source> urls = new ArrayList<>(sources); urls.removeIf(s -> !DistributionPolicy.allows(s.type));
        List<ProxyEndpoint> own = new ArrayList<>(previous), seeds = new ArrayList<>(fallback);
        own.removeIf(p -> !DistributionPolicy.allows(p)); seeds.removeIf(p -> !DistributionPolicy.allows(p));
        session.thread = new Thread(() -> {
            try {
                List<List<ProxyEndpoint>> lists = new ArrayList<>(); List<String> reports = new ArrayList<>();List<Boolean> preferred=new ArrayList<>();
                for (Source source : urls) { lists.add(Collections.emptyList()); reports.add(source.name);preferred.add(false); }
                session.fetchPool = Executors.newFixedThreadPool(6);
                CompletionService<DownloadResult> downloads = new ExecutorCompletionService<>(session.fetchPool);
                for (int i = 0; i < urls.size(); i++) { final int index = i; downloads.submit(() -> fetchSource(urls.get(index), index, session)); }
                for (int i = 0; i < urls.size() && !session.cancelled.get();) {
                    Future<DownloadResult> future = downloads.poll(250, TimeUnit.MILLISECONDS); if (future == null) continue;
                    DownloadResult result = future.get(); i++; lists.set(result.index, result.endpoints); reports.set(result.index, result.report);preferred.set(result.index,result.preferred);
                    listener.onProgress("دریافت منابع: " + i + " از " + urls.size());
                }
                if (!session.cancelled.get()) listener.onFinished(mergePreferredCandidates(own,lists,preferred,seeds,Integer.MAX_VALUE), String.join("\n", reports));
            } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            catch (Exception ex) { if (!session.cancelled.get()) listener.onFailed("دریافت لیست ناموفق: " + ex.getMessage()); }
            finally {
                if (session.fetchPool != null) session.fetchPool.shutdownNow(); session.deadlines.shutdownNow();
                for (HttpURLConnection connection : session.downloads) connection.disconnect();
            }
        }, "proxy-list-only");
        session.thread.start();
    }
    public synchronized void start(List<ProxyEndpoint> manual, Listener listener) {
        start(manual,Collections.emptyList(),Arrays.asList(SourceCatalog.DEFAULT),new Options(5000,false),listener);
    }
    public synchronized void start(List<ProxyEndpoint> manual,List<ProxyEndpoint> fallback,
            List<Source> sources,Options options,Listener listener) {
        stop();
        ScanSession session=new ScanSession(options); current=session;
        List<ProxyEndpoint> own=new ArrayList<>(manual), seeds=new ArrayList<>(fallback);
        List<Source> urls=new ArrayList<>(sources);
        own.removeIf(e->(e.type.bit&options.types)==0);seeds.removeIf(e->(e.type.bit&options.types)==0);
        urls.removeIf(s->(s.type.bit&options.types)==0);
        session.thread=new Thread(() -> runScan(own,seeds,urls,options,listener,session),"socks5-scan");
        session.thread.start();
    }
    public synchronized void stop() {
        if(current==null) return;
        ScanSession session=current; current=null; session.cancelled.set(true);
        if(session.fetchPool!=null) session.fetchPool.shutdownNow();
        if(session.probePool!=null) session.probePool.shutdownNow();
        session.deadlines.shutdownNow();
        for(Socket s:session.sockets) try { s.close(); } catch(Exception ignored) {}
        for(HttpURLConnection c:session.downloads) c.disconnect();
        if(session.thread!=null) session.thread.interrupt();
    }
    public static List<ProxyEndpoint> parseList(String text, boolean publicOnly) {
        return parseList(text,publicOnly,MAX_TESTS);
    }
    public static List<ProxyEndpoint> parseList(String text, boolean publicOnly,int cap) {
        return parseList(text,publicOnly,cap,ProxyEndpoint.Type.SOCKS5);
    }
    public static List<ProxyEndpoint> parseList(String text,boolean publicOnly,int cap,ProxyEndpoint.Type type){
        Map<String,ProxyEndpoint> unique=new LinkedHashMap<>();
        Matcher matcher=ENDPOINT.matcher(text);
        while(unique.size()<cap && matcher.find()) {
            ProxyEndpoint e=ProxyEndpoint.parse(matcher.group(1)+":"+matcher.group(2));
            if(e!=null)e=e.withType(type);
            if(e!=null && (!publicOnly || e.isPublicIpv4())) unique.putIfAbsent(e.key(),e);
        }
        return new ArrayList<>(unique.values());
    }
    /** Keeps a small overlap so tokens and lookarounds remain valid across reader chunks. */
    public static List<ProxyEndpoint> parseStream(Reader reader,boolean publicOnly,int cap,
            AtomicBoolean cancelled) throws IOException {
        return parseStream(reader,publicOnly,cap,cancelled,ProxyEndpoint.Type.SOCKS5);
    }
    public static List<ProxyEndpoint> parseStream(Reader reader,boolean publicOnly,int cap,
            AtomicBoolean cancelled,ProxyEndpoint.Type type)throws IOException {
        Map<String,ProxyEndpoint> unique=new LinkedHashMap<>();
        char[] chunk=new char[8192];String carry="";boolean trimmed=false;int n;
        while(unique.size()<cap&&!cancelled.get()&&(n=reader.read(chunk))!=-1){
            String text=carry+new String(chunk,0,n);
            int safeEnd=Math.max(0,text.length()-32);
            collectMatches(text,safeEnd,trimmed,publicOnly,cap,unique,cancelled,type);
            int discard=Math.max(0,safeEnd-32);
            carry=text.substring(discard);if(discard>0)trimmed=true;
        }
        if(!cancelled.get()&&unique.size()<cap)
            collectMatches(carry,carry.length(),trimmed,publicOnly,cap,unique,cancelled,type);
        return new ArrayList<>(unique.values());
    }
    private static void collectMatches(String text,int safeEnd,boolean trimmed,boolean publicOnly,int cap,
            Map<String,ProxyEndpoint> unique,AtomicBoolean cancelled,ProxyEndpoint.Type type){
        Matcher matcher=ENDPOINT.matcher(text);
        while(unique.size()<cap&&!cancelled.get()&&matcher.find()){
            if(matcher.end()>safeEnd)break;
            if(trimmed&&matcher.start()==0)continue;
            ProxyEndpoint endpoint=ProxyEndpoint.parse(matcher.group(1)+":"+matcher.group(2));
            if(endpoint!=null)endpoint=endpoint.withType(type);
            if(endpoint!=null&&(!publicOnly||endpoint.isPublicIpv4()))unique.putIfAbsent(endpoint.key(),endpoint);
        }
    }
    /** Balanced selection prevents a large provider from consuming the entire budget. */
    public static List<ProxyEndpoint> mergeCandidates(List<ProxyEndpoint> manual,List<List<ProxyEndpoint>> lists,
            List<ProxyEndpoint> fallback,int cap) {
        return mergePreferredCandidates(manual,lists,Collections.nCopies(lists.size(),false),fallback,cap);
    }
    public static List<ProxyEndpoint> mergePreferredCandidates(List<ProxyEndpoint> manual,List<List<ProxyEndpoint>> lists,
            List<Boolean> preferred,List<ProxyEndpoint> fallback,int cap) {
        if(lists.size()!=preferred.size())throw new IllegalArgumentException("Source priority mismatch");
        Map<String,ProxyEndpoint> out=new LinkedHashMap<>();
        for(ProxyEndpoint e:manual) { if(out.size()>=cap) break; out.putIfAbsent(e.key(),e); }
        for(boolean priority:new boolean[]{true,false})for(int row=0;out.size()<cap;row++) {
            boolean any=false;
            for(int index=0;index<lists.size();index++) {
                if(preferred.get(index)!=priority)continue;List<ProxyEndpoint> list=lists.get(index);
                if(row<list.size()) { any=true; ProxyEndpoint e=list.get(row); out.putIfAbsent(e.key(),e); }
                if(out.size()>=cap) break;
            }
            if(!any) break;
        }
        for(ProxyEndpoint e:fallback) { if(out.size()>=cap) break; out.putIfAbsent(e.key(),e); }
        return new ArrayList<>(out.values());
    }
    private void runScan(List<ProxyEndpoint> manual,List<ProxyEndpoint> fallback,List<Source> sources,
            Options options,Listener listener,ScanSession session) {
        List<List<ProxyEndpoint>> lists=new ArrayList<>();
        List<Boolean> preferred=new ArrayList<>();
        List<String> rows=new ArrayList<>();
        for(Source s:sources) { lists.add(Collections.emptyList()); rows.add(s.name+": در انتظار دریافت");preferred.add(false); }
        List<ProxyEndpoint> good=new ArrayList<>();
        EnumMap<Socks5Probe.Failure,Integer> failures=new EnumMap<>(Socks5Probe.Failure.class);
        int fetched=0,total=0,done=0,tcp=0,socks=0;
        try {
            session.fetchPool=Executors.newFixedThreadPool(6);
            CompletionService<DownloadResult> fetch=new ExecutorCompletionService<>(session.fetchPool);
            for(int i=0;i<sources.size();i++) {
                final int index=i;
                fetch.submit(() -> fetchSource(sources.get(index),index,session));
            }
            for(int n=0;n<sources.size() && !session.cancelled.get();) {
                Future<DownloadResult> task=fetch.poll(300,TimeUnit.MILLISECONDS);
                if(task==null) continue;
                DownloadResult r=task.get(); n++;
                lists.set(r.index,r.endpoints); rows.set(r.index,r.report);preferred.set(r.index,r.preferred);
                if(!r.endpoints.isEmpty()) fetched++;
                int count=mergePreferredCandidates(manual,lists,preferred,Collections.emptyList(),options.scanAll?Integer.MAX_VALUE:MAX_TESTS).size();
                listener.onCollectProgress("منابع: "+n+" از "+sources.size()+" • آدرس یکتا: "+count);
                listener.onDiagnostics(report(rows,0,0,0,0,0,fetched,sources.size(),fallback.size(),options,failures));
            }
            session.fetchPool.shutdownNow();
            if(session.cancelled.get()) return;
            List<ProxyEndpoint> candidates=mergePreferredCandidates(manual,lists,preferred,fallback,options.maxTests);
            total=candidates.size();
            listener.onProgress(0,total,0);
            session.probePool=Executors.newFixedThreadPool(24);
            CompletionService<Socks5Probe.Result> tests=new ExecutorCompletionService<>(session.probePool);
            int submitted=0;
            while(submitted<Math.min(total,48)){
                ProxyEndpoint endpoint=candidates.get(submitted++);
                tests.submit(() -> Socks5Probe.inspect(endpoint,options.slow,session.cancelled,session.sockets));
            }
            long lastUpdate=0;
            while(done<total && !session.cancelled.get()) {
                Future<Socks5Probe.Result> task=tests.poll(300,TimeUnit.MILLISECONDS);
                if(task==null) continue;
                Socks5Probe.Result result=task.get(); done++;
                if(submitted<total&&!session.cancelled.get()){
                    ProxyEndpoint endpoint=candidates.get(submitted++);
                    tests.submit(() -> Socks5Probe.inspect(endpoint,options.slow,session.cancelled,session.sockets));
                }
                if(result.tcpReached) tcp++;
                if(result.socksReached) socks++;
                if(result.endpoint!=null) { good.add(result.endpoint); listener.onFound(result.endpoint); }
                else failures.put(result.failure,failures.getOrDefault(result.failure,0)+1);
                long now=System.nanoTime();
                if(now-lastUpdate>TimeUnit.MILLISECONDS.toNanos(400) || done==total) {
                    listener.onProgress(done,total,good.size());
                    listener.onDiagnostics(report(rows,done,total,tcp,socks,good.size(),fetched,sources.size(),fallback.size(),options,failures));
                    lastUpdate=now;
                }
            }
            good.sort(Comparator.comparingInt((ProxyEndpoint e)->e.udpAdvertised?0:1).thenComparingLong(e->e.latencyMs));
            if(!session.cancelled.get()) listener.onFinished(Collections.unmodifiableList(good),
                "اسکن کامل: "+done+" تست • حداقل یک سرویس تأیید شد: "+good.size()+" • پاسخ پروکسی: "+socks);
        } catch(InterruptedException ex) { Thread.currentThread().interrupt(); }
        catch(Exception ex) {
            if(!session.cancelled.get()) {
                listener.onDiagnostics(report(rows,done,total,tcp,socks,good.size(),fetched,sources.size(),fallback.size(),options,failures)+"\nخطای اسکن: "+ex.getClass().getSimpleName());
                listener.onFinished(Collections.unmodifiableList(good),"اسکن متوقف شد: "+ex.getClass().getSimpleName());
            }
        } finally {
            if(session.fetchPool!=null) session.fetchPool.shutdownNow();
            if(session.probePool!=null) session.probePool.shutdownNow();
            session.deadlines.shutdownNow();
            for(HttpURLConnection c:session.downloads) c.disconnect();
        }
    }
    private static DownloadResult fetchSource(Source s,int index,ScanSession session) {
        String primary="";
        try {
            if(s.format==Source.Format.HPROXY_QUALITY){
                HProxySource.Result result=HProxySource.fetch(s.url,System.currentTimeMillis(),session.cancelled,()->downloadMetadata(s.url,session));
                return new DownloadResult(index,result.endpoints,s.name+": "+result.endpoints.size()+" نامزد؛ آخرین تست منبع حداکثر "+result.windowMinutes+" دقیقه؛ ردشده: "+result.rejected+(result.cached?" (حافظهٔ موقت، تازگی دوباره بررسی شد)":"")+"؛ تأیید اتصال پس از اسکن",true);
            }
            List<ProxyEndpoint> list=download(s.url,session,s.type);
            if(!list.isEmpty()) return new DownloadResult(index,list,s.name+": "+list.size()+" آدرس");
            primary="بدون آدرس معتبر";
        } catch(Exception ex) { primary=ex.getMessage()==null?ex.getClass().getSimpleName():ex.getMessage(); }
        if(!session.cancelled.get() && s.mirror!=null && !s.mirror.isEmpty()) {
            try {
                List<ProxyEndpoint> list=download(s.mirror,session,s.type);
                if(!list.isEmpty()) return new DownloadResult(index,list,s.name+": "+list.size()+" آدرس (مسیر جایگزین)");
                primary+="؛ مسیر جایگزین: بدون آدرس";
            } catch(Exception ex) { primary+="؛ مسیر جایگزین: "+ex.getClass().getSimpleName(); }
        }
        return new DownloadResult(index,Collections.emptyList(),s.name+": "+primary);
    }
    private static String downloadMetadata(String url,ScanSession session)throws Exception {
        if(session.cancelled.get())throw new IOException("Cancelled");
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();session.downloads.add(c);
        ScheduledFuture<?> deadline=session.deadlines.schedule(c::disconnect,14,TimeUnit.SECONDS);
        c.setConnectTimeout(4000);c.setReadTimeout(5000);c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent","PersianSocks/0.28.8");c.setRequestProperty("Accept","application/json");
        try{
            int code=c.getResponseCode();
            if(code==429)throw new HProxySource.RateLimited(HProxySource.retryDelay(c.getHeaderField("Retry-After"),System.currentTimeMillis()));
            if(code!=200)throw new IOException("HTTP "+code);
            String content=c.getContentType();
            if(content==null||!content.toLowerCase(Locale.US).split(";",2)[0].trim().equals("application/json"))throw new IOException("HProxy JSON expected");
            ByteArrayOutputStream body=new ByteArrayOutputStream();
            try(InputStream in=c.getInputStream()){
                byte[] bytes=new byte[4096];int count;
                while((count=in.read(bytes))!=-1){
                    if(session.cancelled.get())throw new IOException("Cancelled");
                    if(body.size()+count>1_500_000)throw new IOException("HProxy response too large");
                    body.write(bytes,0,count);
                }
            }
            if(session.cancelled.get())throw new IOException("Cancelled");
            return new String(body.toByteArray(),StandardCharsets.UTF_8);
        }finally{deadline.cancel(false);c.disconnect();session.downloads.remove(c);}
    }
    private static List<ProxyEndpoint> download(String url,ScanSession session,ProxyEndpoint.Type type) throws Exception {
        if(session.cancelled.get()) return Collections.emptyList();
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        session.downloads.add(c);
        ScheduledFuture<?> deadline=session.deadlines.schedule(c::disconnect,14,TimeUnit.SECONDS);
        c.setConnectTimeout(4000); c.setReadTimeout(5000);
        c.setRequestProperty("User-Agent","PersianSocks/0.10.0");
        c.setInstanceFollowRedirects(true);
        try {
            int code=c.getResponseCode(); if(code!=200) throw new IOException("HTTP "+code);
            if(session.options.scanAll){
                try(Reader reader=new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8)){
                    return parseStream(reader,true,Integer.MAX_VALUE,session.cancelled,type);
                }
            }
            ByteArrayOutputStream buffer=new ByteArrayOutputStream();
            try(InputStream in=c.getInputStream()) {
                byte[] chunk=new byte[4096]; int count;
                while(!session.cancelled.get() && buffer.size()<1500000 && (count=in.read(chunk))!=-1)
                    buffer.write(chunk,0,Math.min(count,1500000-buffer.size()));
            }
            return parseList(new String(buffer.toByteArray(),StandardCharsets.UTF_8),true,MAX_TESTS,type);
        } finally { deadline.cancel(false); c.disconnect(); session.downloads.remove(c); }
    }
    private static String report(List<String> rows,int done,int total,int tcp,int socks,int healthy,
            int fetched,int all,int seeds,Options options,EnumMap<Socks5Probe.Failure,Integer> failures) {
        StringBuilder b=new StringBuilder("PersianSocks 0.10.0\n");
        b.append("منابع دریافت‌شده: ").append(fetched).append(" / ").append(all)
            .append("\nسقف این اسکن: ").append(options.scanAll?"Max (همهٔ نتایج)":Integer.toString(options.maxTests))
            .append("\nمهلت تست: ").append(options.slow?"شبکهٔ کند":"معمولی")
            .append("\nفهرست داخلی: ").append(seeds).append(" آدرس؛ نمونهٔ ۲۰۲۶-۱۰-۱۲، تست‌نشده")
            .append("\nبررسی: ").append(done).append(" / ").append(total)
            .append("\nTCP پاسخ داد: ").append(tcp).append("\nپروکسی پاسخ داد: ").append(socks)
            .append("\nحداقل یک سرویس تأیید شد: ").append(healthy).append("\n\nعلت شکست تست‌ها:\n");
        for(Map.Entry<Socks5Probe.Failure,Integer> e:failures.entrySet())
            b.append(Socks5Probe.describe(e.getKey())).append(": ").append(e.getValue()).append('\n');
        if(done>0 && healthy==0) b.append("\nهیچ‌کدام از تلگرام، یوتیوب یا اینستاگرام تأیید نشد. از این نتیجه به‌تنهایی نمی‌شود خرابی سرورها را از محدودیت شبکه جدا کرد.\n");
        b.append("\nوضعیت منابع:\n");
        for(String row:rows) b.append(row).append('\n');
        b.append("\nتلگرام، یوتیوب و اینستاگرام همگی آزمایش می‌شوند. حداقل یک پاسخ ۲۰۰ تا ۳۹۹ با گواهی معتبر لازم است. تأیید UDP فقط پذیرش فرمان است.");
        return b.toString();
    }
}
