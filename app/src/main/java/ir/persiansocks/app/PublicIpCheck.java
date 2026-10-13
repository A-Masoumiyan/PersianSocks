package ir.persiansocks.app;

import android.content.Context;
import android.net.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** A VPN result is read only on the captured VPN network; there is no physical/direct fallback. */
public final class PublicIpCheck {
    private final AtomicBoolean cancelled=new AtomicBoolean();
    private final Set<HttpURLConnection> connections=java.util.concurrent.ConcurrentHashMap.newKeySet();
    public void cancel(){cancelled.set(true);for(HttpURLConnection c:connections)c.disconnect();}
    public static final class Result {
        public final String actual,expected; public final boolean vpn;
        Result(String actual,String expected,boolean vpn){this.actual=actual;this.expected=expected;this.vpn=vpn;}
        public boolean matches(){return vpn&&PublicIpEvidence.matches(actual,expected);}
    }
    private static final java.util.concurrent.ScheduledExecutorService DEADLINE=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"ip-check-deadline");t.setDaemon(true);return t;});
    public Result inspect(Context context,boolean vpn,ProxyEndpoint proxy)throws Exception {
        java.util.concurrent.ScheduledFuture<?> deadline=DEADLINE.schedule(this::cancel,30,java.util.concurrent.TimeUnit.SECONDS);
        try{return inspectRoute(context,vpn,proxy);}finally{deadline.cancel(false);}
    }
    private Result inspectRoute(Context context,boolean vpn,ProxyEndpoint proxy)throws Exception {
        ConnectivityManager cm=context.getSystemService(ConnectivityManager.class);
        Network active=cm.getActiveNetwork();NetworkCapabilities caps=active==null?null:cm.getNetworkCapabilities(active);
        if(caps==null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)!=vpn)throw new IOException("Requested network is not active");
        String last="";Result observed=null;
        for(String endpoint:new String[]{"https://api.ipify.org/","https://checkip.amazonaws.com/"}){
            try {
                URL url=new URL(endpoint+"?ps="+System.nanoTime());
                String actual=read((HttpURLConnection)active.openConnection(url,java.net.Proxy.NO_PROXY));
                if(!active.equals(cm.getActiveNetwork()))throw new IOException("Network changed");
                String expected="";
                if(vpn){
                    if(proxy==null)throw new IOException("No active proxy");
                    PhysicalTestNetwork physical=PhysicalTestNetwork.capture(context);
                    try{expected=read(new ProxyHttpConnection(url,proxy,physical::socket,physical::resolve));}catch(Exception ex){if(cancelled.get())throw ex;}
                    if(!physical.valid())throw new IOException("Physical network changed");
                }
                caps=cm.getNetworkCapabilities(active);
                if(cancelled.get()||caps==null||caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)!=vpn||!active.equals(cm.getActiveNetwork()))throw new IOException("Network changed");
                Result measured=new Result(actual,expected,vpn);
                if(!vpn||!expected.isEmpty())return measured;observed=measured;
            }catch(Exception ex){last=ex.getMessage();if(cancelled.get())throw ex;}
        }
        if(observed!=null&&!cancelled.get())return observed;
        throw new IOException(last);
    }
    private String read(HttpURLConnection c)throws Exception {
        connections.add(c);
        try {
            if(cancelled.get())throw new IOException("Cancelled");
            c.setConnectTimeout(6000);c.setReadTimeout(6000);c.setUseCaches(false);c.setInstanceFollowRedirects(false);
            c.setRequestProperty("Cache-Control","no-cache, no-store");
            if(c.getResponseCode()!=200)throw new IOException("IP service did not return 200");
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(InputStream in=c.getInputStream()){byte[] block=new byte[128];int n;while((n=in.read(block))!=-1){if(cancelled.get()||bytes.size()+n>256)throw new IOException("Invalid IP response");bytes.write(block,0,n);}}
            String ip=PublicIpEvidence.parse(bytes.toString("US-ASCII"));
            if(ip.isEmpty())throw new IOException("Invalid public IPv4 response");
            return ip;
        }finally{connections.remove(c);c.disconnect();}
    }
}
