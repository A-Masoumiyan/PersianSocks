package ir.persiansocks.app;

import java.net.*;
import java.util.Set;
import java.util.HashSet;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Caller must provide sockets and DNS bound to the active VPN network. */
public final class TunnelVerifier {
    public interface Transport {
        Socket create() throws Exception;
        InetAddress[] resolve(String host) throws Exception;
    }
    public static final class Result {
        public final int passed, checked;
        public final String report;
        public final Set<String> extraPassed, extraChecked;
        public final Map<String,Long> timings;
        Result(int p,int c,String r,Set<String> good,Set<String> tried,Map<String,Long> timings){
            this.timings=java.util.Collections.unmodifiableMap(new HashMap<>(timings));passed=p;checked=c;report=r;extraPassed=good;extraChecked=tried;
        }
        public ProxyEndpoint profile(ProxyEndpoint p) {
            ServiceTargets.Target selected=ScanProbePolicy.profile().target();String target=selected==null?p.latencyTarget:selected.url();if(p.type==ProxyEndpoint.Type.OPENVPN&&!timings.containsKey(target)&&!timings.isEmpty())target=timings.keySet().iterator().next();
            return new ProxyEndpoint(p.host,p.port,timings.getOrDefault(target,p.latencyMs),p.udpAdvertised,passed,checked,
                System.currentTimeMillis(),extraPassed,extraChecked,p.countryCode,p.type).withServiceLatencies(timings).withLatency(timings.getOrDefault(target,p.latencyMs),target);
        }
        public boolean accepts(String filter) {
            return ProxyResults.accepts(profile(new ProxyEndpoint("",0,0,false).withType(ProxyEndpoint.Type.HTTPS)),filter);
        }
    }
    private TunnelVerifier(){}
    public static Result check(Transport transport,AtomicBoolean cancelled,Set<Socket> active) {
        return check(transport,cancelled,active,ServiceTargets.current(),false);
    }
    public static Result check(Transport transport,AtomicBoolean cancelled,Set<Socket> active,
            ServiceTargets.Target[] targets,boolean quick) {
        return check(transport,cancelled,active,targets,quick,false);
    }
    public static Result check(Transport transport,AtomicBoolean cancelled,Set<Socket> active,
            ServiceTargets.Target[] targets,boolean quick,boolean firstSuccess) {
        ScheduledThreadPoolExecutor deadlines=new ScheduledThreadPoolExecutor(1);
        deadlines.setRemoveOnCancelPolicy(true);
        int passed=0,checked=0;
        Set<String> extraPassed=new HashSet<>(),extraChecked=new HashSet<>();
        Map<String,Long> timings=new HashMap<>();
        StringBuilder report=new StringBuilder("آزمون HTTPS از داخل شبکهٔ VPN:\n");
        try {
            for(ServiceTargets.Target target:targets) {
                if(cancelled.get())break;
                checked|=target.bit;
                if(target.bit==0)extraChecked.add(target.id);
                boolean success=false;String error="پاسخ نرسید";long begin=System.nanoTime();
                try {
                    InetAddress[]addresses=transport.resolve(target.host);
                    java.util.Arrays.sort(addresses,(a,b)->Integer.compare(a instanceof Inet4Address?0:1,b instanceof Inet4Address?0:1));
                    for(int i=0;i<Math.min(addresses.length,quick?1:2)&&!cancelled.get();i++) {
                        Socket socket=transport.create();active.add(socket);
                        ScheduledFuture<?> budget=deadlines.schedule(()->{try{socket.close();}catch(Exception ignored){}},quick?7:30,TimeUnit.SECONDS);
                        try(Socket owned=socket) {
                            socket.connect(new InetSocketAddress(addresses[i],443),quick?3000:5000);
                            ServiceTargets.exchange(socket,target,quick?4000:25000);success=true;break;
                        }catch(Exception ex){error=ex.getClass().getSimpleName()+": "+String.valueOf(ex.getMessage());}
                        finally{budget.cancel(false);active.remove(socket);}
                    }
                }catch(Exception ex){error=ex.getClass().getSimpleName()+": "+String.valueOf(ex.getMessage());}
                if(success){passed|=target.bit;timings.put(target.url(),Math.max(1,(System.nanoTime()-begin)/1_000_000L));}
                if(success&&target.bit==0)extraPassed.add(target.id);
                report.append(target.name).append(success?": تأیید شد\n":": "+error+"\n");
                if(success&&firstSuccess)break;
            }
            if(cancelled.get())extraPassed.clear();
            return new Result(cancelled.get()?0:passed,checked,report.toString(),extraPassed,extraChecked,timings);
        }finally{deadlines.shutdownNow();}
    }
}
