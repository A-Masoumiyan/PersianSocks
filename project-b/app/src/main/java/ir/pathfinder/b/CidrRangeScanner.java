package ir.pathfinder.b;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Opt-in, CIDR-restricted auditing of a network the user controls.
 * Pass 1: bounded, rate-limited TCP port discovery across 37.32.0.0/19.
 * Pass 2: check SOCKS4, SOCKS4a, SOCKS5, HTTP CONNECT and verify
 *         TLS+HTTP requests to Telegram and YouTube THROUGH that proxy.
 *
 * A finished 128-attempt batch is committed atomically to prefs.
 * Resuming might repeat only an unfinished batch. Never scans outside /19.
 */
final class CidrRangeScanner {
    static final String CIDR="37.32.0.0/19";
    static final int HOSTS=8192;
    private static final int DISCOVERY_THREADS=24, VERIFY_THREADS=5;
    private static final int BATCH=128;
    private static final int TIMEOUT_MS=900;
    private static final long GAP_MS=30; // 33 SYNs/second at most, per device
    private static final String PREFS="pathfinder_cidr_v1";

    interface Progress {
        void onProgress(String phase,int completed,int total,int openPorts,int hits);
    }
    static final class State {
        final String signature;
        final int stage, cursor;
        final List<String> opened;
        final List<IranProxyScanner.Hit> hits;
        State(String signature,int stage,int cursor,List<String> opened,List<IranProxyScanner.Hit> hits){
            this.signature=signature;this.stage=stage;this.cursor=cursor;
            this.opened=opened;this.hits=hits;
        }
        boolean completed(){return stage==2;}
    }
    private static final class Rate {
        private long next=0;
        synchronized void acquire(AtomicBoolean cancelled)throws InterruptedException {
            if(cancelled.get())throw new InterruptedException("paused");
            long now=SystemClock.elapsedRealtime();
            long wait=Math.max(0,next-now);
            next=Math.max(next,now)+GAP_MS;
            if(wait>0)Thread.sleep(wait);
        }
    }

    static int[] parsePorts(String text)throws IOException {
        if(text==null)throw new IOException("Enter at least one port");
        LinkedHashSet<Integer> ports=new LinkedHashSet<>();
        for(String item:text.split("[,\\s;]+")){
            if(item.trim().isEmpty())continue;
            int p;
            try{p=Integer.parseInt(item.trim());}catch(Exception e){throw new IOException("Invalid port: "+item);}
            if(p<1||p>65535)throw new IOException("Port out of range: "+p);
            ports.add(p);
            if(ports.size()>24)throw new IOException("Maximum 24 ports per sweep");
        }
        if(ports.isEmpty())throw new IOException("No ports selected");
        int[] v=new int[ports.size()],i={0};
        for(Integer p:ports)v[i[0]++]=p;
        return v;
    }
    static String signature(int[] ports){
        return CIDR+" "+Arrays.toString(ports);
    }
    static String address(int i){
        if(i<0||i>=HOSTS)throw new IllegalArgumentException("Outside authorized CIDR");
        return "37.32."+(i>>>8)+"."+(i&255);
    }
    private static SharedPreferences prefs(Context context){
        return context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
    }
    static void reset(Context context){prefs(context).edit().clear().commit();}

    static State load(Context ctx,int[] ports){
        SharedPreferences p=prefs(ctx);
        String sig=signature(ports);
        if(!sig.equals(p.getString("signature","")))return new State(sig,0,0,new ArrayList<>(),new ArrayList<>());
        List<String> opens=new ArrayList<>();
        for(String item:p.getString("opened","").split("\n")){
            if(item.matches("37\\.32\\.(?:[0-9]|[12][0-9]|3[01])\\.(?:[0-9]{1,3}):[0-9]{1,5}"))opens.add(item);
        }
        List<IranProxyScanner.Hit> hits=new ArrayList<>();
        try{
            JSONArray array=new JSONArray(p.getString("hits","[]"));
            for(int i=0;i<array.length();i++){
                JSONObject o=array.getJSONObject(i);
                String ip=o.getString("ip");
                if(!ip.startsWith("37.32."))continue;
                int port=o.getInt("port");
                IranProxyScanner.Type type=IranProxyScanner.Type.valueOf(o.getString("type"));
                hits.add(new IranProxyScanner.Hit(
                    new IranProxyScanner.Candidate(ip,port,type),
                    o.getBoolean("telegram"),o.getBoolean("youtube"),
                    o.getBoolean("thumbnail"),o.getLong("duration")));
            }
        }catch(Exception ignored){}
        return new State(sig,p.getInt("stage",0),p.getInt("cursor",0),opens,hits);
    }
    private static void save(Context ctx,State s){
        JSONArray arr=new JSONArray();
        for(IranProxyScanner.Hit hit:s.hits){
            try{
                JSONObject o=new JSONObject();
                o.put("ip",hit.proxy.ip).put("port",hit.proxy.port)
                    .put("type",hit.proxy.type.name())
                    .put("telegram",hit.telegram).put("youtube",hit.youtube)
                    .put("thumbnail",hit.thumbnail).put("duration",hit.duration);
                arr.put(o);
            }catch(Exception ignored){}
        }
        StringBuilder opened=new StringBuilder();
        for(String entry:s.opened)opened.append(entry).append('\n');
        prefs(ctx).edit().putString("signature",s.signature)
            .putInt("stage",s.stage).putInt("cursor",s.cursor)
            .putString("opened",opened.toString())
            .putString("hits",arr.toString()).commit();
    }
    private static Network physicalNetwork(Context ctx){
        ConnectivityManager m=(ConnectivityManager)ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if(m==null)return null;
        Network def=m.getActiveNetwork();
        NetworkCapabilities dc=def==null?null:m.getNetworkCapabilities(def);
        if(dc!=null&&!dc.hasTransport(NetworkCapabilities.TRANSPORT_VPN))return def;
        for(Network n:m.getAllNetworks()){
            NetworkCapabilities c=m.getNetworkCapabilities(n);
            if(c!=null&&!c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                &&c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))return n;
        }
        return null;
    }
    private static String discover(Network network,String ip,int port,AtomicBoolean stop,Rate gate){
        if(stop.get())return null;
        try{
            gate.acquire(stop);
            if(stop.get())return null;
            try(Socket socket=network!=null?network.getSocketFactory().createSocket():new Socket()){
                socket.connect(new InetSocketAddress(InetAddress.getByName(ip),port),TIMEOUT_MS);
                return ip+":"+port;
            }
        }catch(Exception ignored){return null;}
    }
    private static final IranProxyScanner.Type[] TYPES={
        IranProxyScanner.Type.SOCKS5,IranProxyScanner.Type.SOCKS4A,
        IranProxyScanner.Type.SOCKS4,IranProxyScanner.Type.HTTP
    };
    private static IranProxyScanner.Hit verify(Network net,String endpoint,int which,AtomicBoolean stop){
        if(stop.get())return null;
        String[] parts=endpoint.split(":");
        if(parts.length!=2)return null;
        int port=Integer.parseInt(parts[1]);
        IranProxyScanner.Candidate c=new IranProxyScanner.Candidate(parts[0],port,TYPES[which]);
        return IranProxyScanner.test(net,c,stop);
    }

    static State run(Context ctx,int[] ports,AtomicBoolean pause,Progress listener) throws Exception {
        final String sig=signature(ports);
        State saved=load(ctx,ports);
        final ArrayList<String> opened=new ArrayList<>(new LinkedHashSet<>(saved.opened));
        final ArrayList<IranProxyScanner.Hit> hits=new ArrayList<>(saved.hits);
        final Set<String> foundKeys=new HashSet<>();
        for(IranProxyScanner.Hit h:hits)foundKeys.add(h.proxy.key());
        if(saved.completed())return saved;
        Network net=physicalNetwork(ctx);
        if(net==null)throw new IOException("Physical Wi-Fi or mobile network unavailable");
        int stage=saved.stage,cursor=saved.cursor;
        if(stage==0){
            ExecutorService pool=Executors.newFixedThreadPool(DISCOVERY_THREADS);
            Rate rate=new Rate();
            final int total=HOSTS*ports.length;
            try{
                while(cursor<total&&!pause.get()){
                    final int start=cursor;
                    final int end=Math.min(total,start+BATCH);
                    List<Callable<String>> tasks=new ArrayList<>();
                    for(int pos=start;pos<end;pos++){
                        String ip=address(pos/ports.length);
                        int port=ports[pos%ports.length];
                        tasks.add(()->discover(net,ip,port,pause,rate));
                    }
                    List<Future<String>> futures=pool.invokeAll(tasks);
                    for(Future<String> f:futures){
                        try{
                            String open=f.get();
                            if(open!=null&&!opened.contains(open))opened.add(open);
                        }catch(Exception ignored){}
                    }
                    if(pause.get())break; // repeat unfinished batch safely on resume
                    cursor=end;
                    save(ctx,new State(sig,0,cursor,opened,hits));
                    listener.onProgress("کشف پورت‌ها",cursor,total,opened.size(),hits.size());
                }
            }finally{pool.shutdownNow();}
            if(pause.get())return new State(sig,0,cursor,opened,hits);
            stage=1;cursor=0;
            save(ctx,new State(sig,stage,cursor,opened,hits));
        }
        if(stage==1){
            ExecutorService pool=Executors.newFixedThreadPool(VERIFY_THREADS);
            final int total=opened.size()*TYPES.length;
            try {
                while(cursor<total&&!pause.get()){
                    int start=cursor,end=Math.min(total,start+24);
                    List<Callable<IranProxyScanner.Hit>> tasks=new ArrayList<>();
                    for(int p=start;p<end;p++){
                        final String addr=opened.get(p/TYPES.length);
                        final int typeIndex=p%TYPES.length;
                        tasks.add(()->verify(net,addr,typeIndex,pause));
                    }
                    List<Future<IranProxyScanner.Hit>> futures=pool.invokeAll(tasks);
                    for(Future<IranProxyScanner.Hit> f:futures){
                        try {
                            IranProxyScanner.Hit hit=f.get();
                            if(hit!=null&&foundKeys.add(hit.proxy.key()))hits.add(hit);
                        }catch(Exception ignored){}
                    }
                    if(pause.get())break;
                    cursor=end;
                    save(ctx,new State(sig,1,cursor,opened,hits));
                    listener.onProgress("تأیید تلگرام و یوتیوب",cursor,total,opened.size(),hits.size());
                }
            }finally{pool.shutdownNow();}
            if(pause.get())return new State(sig,1,cursor,opened,hits);
        }
        hits.sort((a,b)->{
            int qa=(a.telegram?1:0)+(a.youtube?1:0)+(a.thumbnail?1:0);
            int qb=(b.telegram?1:0)+(b.youtube?1:0)+(b.thumbnail?1:0);
            int diff=Integer.compare(qb,qa);
            return diff!=0?diff:Long.compare(a.duration,b.duration);
        });
        State done=new State(sig,2,opened.size()*TYPES.length,opened,hits);
        save(ctx,done);
        listener.onProgress("پایان اسکن",done.cursor,done.cursor,opened.size(),hits.size());
        return done;
    }
}