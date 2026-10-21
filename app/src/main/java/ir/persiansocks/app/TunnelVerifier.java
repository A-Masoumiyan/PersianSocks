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
    /** Race up to three real HTTPS checks through the same established VPN; never race VPN routes. */
    private static Result parallelFirst(Transport transport,AtomicBoolean cancelled,Set<Socket> active,ServiceTargets.Target[] targets){
        AtomicBoolean stop=new AtomicBoolean();Set<Socket> owned=ConcurrentHashMap.newKeySet();
        ExecutorService pool=Executors.newFixedThreadPool(Math.min(3,targets.length),r->{Thread t=new Thread(r,"vpn-https-race");t.setDaemon(true);return t;});
        CompletionService<Result> completed=new ExecutorCompletionService<>(pool);
        Transport guarded=new Transport(){
            public Socket create()throws Exception{if(stop.get()||cancelled.get())throw new SocketException("Cancelled");Socket socket=transport.create();owned.add(socket);active.add(socket);if(stop.get()||cancelled.get()){socket.close();throw new SocketException("Cancelled");}return socket;}
            public InetAddress[] resolve(String host)throws Exception{return transport.resolve(host);}
        };
        int passed=0,checked=0;Set<String> good=new HashSet<>(),tried=new HashSet<>();Map<String,Long> timings=new HashMap<>();StringBuilder report=new StringBuilder();
        try{
            for(ServiceTargets.Target target:targets)completed.submit(()->check(guarded,stop,active,new ServiceTargets.Target[]{target},true,false));
            long deadline=System.nanoTime()+8_000_000_000L;
            for(int remaining=targets.length;remaining>0&&!cancelled.get()&&System.nanoTime()<deadline;){
                Future<Result> future=completed.poll(100,TimeUnit.MILLISECONDS);if(future==null)continue;remaining--;
                Result r;try{r=future.get();}catch(ExecutionException failed){continue;}
                passed|=r.passed;checked|=r.checked;good.addAll(r.extraPassed);tried.addAll(r.extraChecked);timings.putAll(r.timings);report.append(r.report);
                if(passed!=0||!good.isEmpty())break;
            }
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        finally{stop.set(true);for(Socket socket:owned){try{socket.close();}catch(Exception ignored){}active.remove(socket);}pool.shutdownNow();}
        return new Result(cancelled.get()?0:passed,checked,report.toString(),cancelled.get()?java.util.Collections.emptySet():good,tried,timings);
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
        if(quick&&firstSuccess&&targets.length>1)return parallelFirst(transport,cancelled,active,targets);
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
