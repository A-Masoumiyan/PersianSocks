import ir.persiansocks.app.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Actual TLS checks: selected scope avoids unrelated requests without accepting unverified services. */
public final class ConnectionScopeHarness {
    static int checks;
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);checks++;}
    static TunnelVerifier.Result verify(String mode,String filter,ProxyEndpoint known,List<String> contacted)throws Exception {
        try(ScanHarness.Mock fixture=new ScanHarness.Mock(mode)){
            return TunnelVerifier.check(new TunnelVerifier.Transport(){
                public Socket create(){return new Socket(){@Override public void connect(SocketAddress address,int timeout)throws IOException{
                    super.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(),fixture.listener.getLocalPort()),timeout);
                }};}
                public InetAddress[] resolve(String host){contacted.add(host);return new InetAddress[]{InetAddress.getLoopbackAddress()};}
            },new AtomicBoolean(),ConcurrentHashMap.newKeySet(),ConnectionPreflight.verificationTargets(filter,known),false,"ALL".equals(filter));
        }
    }
    static void parallelChecks()throws Exception {
        try(ScanHarness.Mock valid=new ScanHarness.Mock("tunnel-youtube");ScanHarness.Mock stalled=new ScanHarness.Mock("stall")){
            CountDownLatch started=new CountDownLatch(3);ThreadLocal<String> host=new ThreadLocal<>();Set<Socket> active=ConcurrentHashMap.newKeySet();
            long begin=System.nanoTime();
            TunnelVerifier.Result result=TunnelVerifier.check(new TunnelVerifier.Transport(){
                public InetAddress[] resolve(String value)throws Exception{host.set(value);started.countDown();if(!started.await(2,TimeUnit.SECONDS))throw new IOException("Checks did not run concurrently");return new InetAddress[]{InetAddress.getLoopbackAddress()};}
                public Socket create(){return new Socket(){@Override public void connect(SocketAddress ignored,int timeout)throws IOException{int port=host.get().equals("www.youtube.com")?valid.listener.getLocalPort():stalled.listener.getLocalPort();super.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(),port),timeout);}};}
            },new AtomicBoolean(),active,ServiceTargets.ALL,true,true);
            long ms=(System.nanoTime()-begin)/1_000_000;
            check(result.passed==2&&result.accepts("ALL")&&!result.accepts("FULL"),"Concurrent verification only accepts the real HTTPS response");
            check(ms<3500&&active.isEmpty(),"A successful HTTPS target overtakes two stalled targets and closes their sockets ("+ms+" ms)");
            System.out.println("PASS: real HTTPS race with two stalled targets: "+ms+" ms");
        }
        AtomicBoolean cancelled=new AtomicBoolean();Set<Socket> active=ConcurrentHashMap.newKeySet();Thread canceller=new Thread(()->{try{Thread.sleep(150);}catch(InterruptedException ignored){}cancelled.set(true);});canceller.start();long begin=System.nanoTime();
        TunnelVerifier.Result stopped=TunnelVerifier.check(new TunnelVerifier.Transport(){
            public Socket create(){throw new AssertionError("DNS cancellation must stop socket creation");}
            public InetAddress[] resolve(String host)throws Exception{Thread.sleep(10000);return new InetAddress[]{InetAddress.getLoopbackAddress()};}
        },cancelled,active,ServiceTargets.ALL,true,true);
        check(stopped.passed==0&&active.isEmpty()&&(System.nanoTime()-begin)/1_000_000<1500,"Stop cancels concurrent DNS waits promptly without success evidence");canceller.join();
    }
    public static void main(String[] args)throws Exception {
        ScanHarness.init(args[0]);
        ServiceTargets.Target custom=ServiceTargets.custom("Facebook","https://www.facebook.com/");
        ServiceTargets.configure(Collections.singletonList(custom));
        ProxyEndpoint known=new ProxyEndpoint("8.8.8.8",1080,30,false,7,7,System.currentTimeMillis())
            .withServiceLatencies(Map.of(ServiceTargets.ALL[0].url(),500L,ServiceTargets.ALL[1].url(),30L,ServiceTargets.ALL[2].url(),200L));
        check(ConnectionPreflight.target("ALL",known).bit==2,"Fastest successful service is chosen from measured timing");
        check(ConnectionPreflight.target("TELEGRAM",known).bit==1,"Explicit service overrides measured ranking");
        check(ConnectionPreflight.verificationTargets("FULL",known).length==3,"Full requires built-ins, not unrelated custom domains");
        List<String> hosts=new ArrayList<>();TunnelVerifier.Result result=verify("tunnel-valid","ALL",known,hosts);
        check(hosts.equals(Collections.singletonList("www.youtube.com"))&&result.accepts("ALL")&&!result.accepts("FULL")&&result.checked==2,"First real TLS success ends ALL verification and claims only checked service");
        hosts.clear();result=verify("tunnel-valid","FULL",known,hosts);
        check(hosts.size()==3&&!hosts.contains(custom.host)&&result.accepts("FULL"),"Full still freshly verifies all three built-ins");
        hosts.clear();result=verify("tunnel-youtube","ALL",null,hosts);
        check(hosts.equals(Arrays.asList("web.telegram.org","www.youtube.com"))&&result.passed==2,"Failed first service retains working fallback");
        hosts.clear();result=verify("tunnel-youtube","TELEGRAM",known,hosts);
        check(hosts.size()==1&&!result.accepts("TELEGRAM")&&!result.accepts("ALL"),"Specific filter cannot borrow success from an unrelated service");
        hosts.clear();result=verify("tunnel-facebook",custom.id,known,hosts);
        check(hosts.equals(Collections.singletonList(custom.host))&&result.accepts(custom.id)&&!result.accepts("FULL"),"Custom scope verifies only requested HTTPS endpoint");
        parallelChecks();
        ServiceTargets.configure(Collections.emptyList());
        System.out.println("PASS: "+checks+" scoped connection checks with actual TLS");
    }
}
