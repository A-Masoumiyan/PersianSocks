import ir.persiansocks.app.*;
import java.util.*;
import java.io.*;
import java.nio.*;

public final class FeatureHarness {
    static int passed;
    static void check(boolean ok,String name) {
        if(!ok)throw new AssertionError(name);
        passed++;System.out.println("PASS: "+name);
    }
    static ProxyEndpoint p(String ip,long latency,int mask) {
        return new ProxyEndpoint(ip,1080,latency,false,mask,7,100);
    }
    static void rejects(String url) {
        try { ServiceTargets.custom("test",url); throw new AssertionError("Accepted: "+url); }
        catch(IllegalArgumentException expected) { passed++;System.out.println("PASS: Reject "+url.replace("\n","\\n")); }
    }
    public static void main(String[] args)throws Exception {
        ServiceTargets.Target facebook=ServiceTargets.custom("فیسبوک","www.facebook.com");
        check(facebook.id.equals("https://www.facebook.com/"),"A bare Facebook domain normalizes to stable HTTPS identity");
        check(ServiceTargets.custom("","https://example.org/a%20b?q=hello#fragment").path.equals("/a%20b?q=hello"),
            "Custom test keeps path and query and excludes fragment");
        check(ServiceTargets.custom("سایت","https://example.org/سلام").path.equals("/%D8%B3%D9%84%D8%A7%D9%85"),
            "Persian URL paths are percent-encoded in HTTPS requests");
        ServiceTargets.configure(Arrays.asList(facebook,facebook));
        check(ServiceTargets.current().length==4,"Duplicate custom domains never duplicate tests");
        List<ServiceTargets.Target> loaded=ServiceTargets.decode(ServiceTargets.encode(Arrays.asList(facebook)));
        check(loaded.size()==1&&loaded.get(0).id.equals(facebook.id)&&loaded.get(0).name.equals("فیسبوک"),
            "Domain and Persian display name persist across restart");
        rejects("http://example.org");rejects("https://user:secret@example.org");rejects("https://example.org:8443/");
        rejects("127.0.0.1");rejects("example.local");rejects("https://foo..org");rejects("https://example.org/\r\nX-Evil:yes");
        List<ServiceTargets.Target> tooMany=new ArrayList<>();
        for(int i=0;i<12;i++)tooMany.add(ServiceTargets.custom("site"+i,"site"+i+".example.org"));
        ServiceTargets.configure(tooMany);
        check(ServiceTargets.current().length==11,"Custom tests are bounded to eight domains");
        ServiceTargets.configure(Arrays.asList(facebook));
        ProxyEndpoint all=p("1.1.1.1",200,7),tg=p("2.2.2.2",50,1),yt=p("3.3.3.3",20,2);
        ProxyEndpoint extra=new ProxyEndpoint("4.4.4.4",1080,10,false,0,7,500,
            Collections.singleton(facebook.id),Collections.singleton(facebook.id),"US");
        List<ProxyEndpoint> candidates=Arrays.asList(all,tg,yt,extra);
        check(ProxyResults.visible(candidates,"FULL",ProxyResults.Sort.FASTEST).equals(Arrays.asList(all)),
            "Full requires all three built-in services, not one match or a custom service");
        check(ProxyResults.visible(candidates,facebook.id,ProxyResults.Sort.FASTEST).equals(Arrays.asList(extra)),
            "A custom filter returns only domain-verified proxies before sorting");
        check(ProxyResults.visible(candidates,"ALL",ProxyResults.Sort.FASTEST).get(0)==extra,
            "All includes proxies that only support a configured custom domain");
        check(ProxyEndpoint.fromJson(extra.json()).countryCode.equals("US"),"Country survives saved result round-trip");
        ServiceTargets.configure(Collections.emptyList());
        check(!extra.hasAnyService()&&!ProxyResults.accepts(extra,facebook.id),"Removing a domain removes its filter eligibility");
        ServiceTargets.configure(Arrays.asList(ServiceTargets.custom("فیسبوک جدید","https://www.facebook.com/robots.txt")));
        check(!extra.hasAnyService(),"A changed test URL never inherits an old service confirmation");
        ServiceTargets.configure(Arrays.asList(facebook));
        FailoverPolicy policy=new FailoverPolicy();
        policy.reset(tg.address());
        check(!policy.health(false),"One failed health sample does not trigger a switch");
        check(policy.health(false),"Two consecutive health failures trigger recovery");
        check(!policy.health(true)&&!policy.health(false),"A successful health sample resets the failure streak");
        policy.failed(tg.address(),1000);
        check(policy.next(candidates,tg.address(),"TELEGRAM",ProxyResults.Sort.FASTEST,1000)==all,
            "Failover skips faster incompatible proxies and preserves selected service filter");
        policy.failed(all.address(),2000);
        check(policy.next(candidates,all.address(),"TELEGRAM",ProxyResults.Sort.FASTEST,2000)==null,
            "Failed alternatives are not retried in a loop");
        policy.connected(all.address());
        check(policy.next(candidates,all.address(),"TELEGRAM",ProxyResults.Sort.FASTEST,2500)==null,
            "A recent failed proxy remains in cooldown after another connects");
        check(policy.next(candidates,all.address(),"TELEGRAM",ProxyResults.Sort.FASTEST,400_000)==tg,
            "Cooldown permits a later retry after five minutes");
        policy.reset(extra.address());
        check(policy.next(candidates,extra.address(),facebook.id,ProxyResults.Sort.FASTEST,0)==null,
            "Custom-domain failover cannot silently switch to unrelated services");
        check(policy.healthTargets("FULL",all).length==3,"Full health monitoring checks exactly the original three services");
        check(policy.healthTargets(facebook.id,extra).length==1,"Custom health monitoring tests the selected domain");
        ProxyEndpoint extended=new ProxyEndpoint("6.6.6.6",1080,10,false,7,7,100,
            Collections.singleton(facebook.id),Collections.singleton(facebook.id),"US");
        check(policy.healthTargets("ALL",extended).length==4,"All health monitoring retains custom-service fallback when built-ins stop responding");
        List<ProxyEndpoint> many=new ArrayList<>();
        for(int i=1;i<20;i++)many.add(p("8.8.8."+i,i,7));
        policy.reset(many.get(0).address());String current=many.get(0).address();int attempts=1;
        while(true){ProxyEndpoint next=policy.next(many,current,"FULL",ProxyResults.Sort.FASTEST,0);if(next==null)break;current=next.address();attempts++;}
        check(attempts==8,"Recovery is bounded to eight candidates before reporting exhaustion");
        CountryLookup.initialize(new FileInputStream(args[0]));
        check(CountryLookup.lookup("8.8.8.8").equals("US"),"Offline lookup returns US for 8.8.8.8");
        check(CountryLookup.lookup("127.0.0.1").isEmpty()&&CountryLookup.lookup("192.168.1.1").isEmpty(),
            "Private and loopback IPs are never assigned a public country");
        check(CountryLookup.lookup("not-an-ip").isEmpty(),"Invalid IP is displayed as unknown");
        check(!CountryLookup.display("US").isEmpty()&&CountryLookup.display("").equals("نامشخص"),"Country labels handle valid and unknown results");
        byte[] db=java.nio.file.Files.readAllBytes(java.nio.file.Path.of(args[0]));
        ByteBuffer buffer=ByteBuffer.wrap(db);int count=buffer.getInt(4),samples=0;
        for(int i=0;i<count;i+=499){
            int off=8+i*10;long start=Integer.toUnsignedLong(buffer.getInt(off));
            String host=(start>>>24)+"."+((start>>>16)&255)+"."+((start>>>8)&255)+"."+(start&255);
            ProxyEndpoint endpoint=ProxyEndpoint.parse(host+":1");
            String code=""+(char)buffer.get(off+8)+(char)buffer.get(off+9);
            if(endpoint.isPublicIpv4()&&!code.equals("ZZ")&&!code.equals("--")){
                if(!CountryLookup.lookup(host).equals(code))throw new AssertionError("Country range "+i);
                samples++;
            }
        }
        check(samples>600,"Country binary search matches over 600 source range boundaries");
        ServiceTargets.configure(Collections.emptyList());
        System.out.println("RESULT: "+passed+" checks passed");
    }
}
