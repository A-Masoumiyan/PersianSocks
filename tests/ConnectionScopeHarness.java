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
        ServiceTargets.configure(Collections.emptyList());
        System.out.println("PASS: "+checks+" scoped connection checks with actual TLS");
    }
}
