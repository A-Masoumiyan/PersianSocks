import ir.persiansocks.app.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.*;

public final class ConfiguredTargetsHarness {
    static int checks;
    static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    public static void main(String[] args)throws Exception {
        ScanHarness.init(args[0]);ServiceTargets.configure(Collections.emptyList());
        String ping=ServiceTargets.ALL[1].url(),file="https://www.youtube.com/fixture.bin?sample=1";
        check(TestDownloadPolicy.validate(file).equals(file),"exact file URL on configured domain");
        for(String bad:new String[]{"http://www.youtube.com/file","https://user:pass@www.youtube.com/file","https://www.youtube.com.evil.test/file","https://127.0.0.1/file","https://www.youtube.com:8443/file",file+"#fragment"}){
            try{TestDownloadPolicy.validate(bad);throw new AssertionError("unsafe URL accepted");}catch(IllegalArgumentException expected){checks++;}
        }
        for(ProxyEndpoint.Type type:ProxyEndpoint.Type.values())try(SingleProxyHarness.Proxy proxy=new SingleProxyHarness.Proxy(type,"valid",300_000)){
            ScanProbePolicy.configure(true,ping);ScanProbePolicy.Profile frozen=ScanProbePolicy.profile();
            Socks5Probe.Result result=frozen.inspect(proxy.endpoint(),false,new AtomicBoolean(),null,Socket::new,InetAddress::getByName);
            check(result.endpoint!=null&&result.endpoint.serviceMask==ProxyEndpoint.YOUTUBE&&result.endpoint.checkedMask==ProxyEndpoint.YOUTUBE&&!result.endpoint.udpAdvertised,"one target cannot claim other services or UDP: "+type);
            check(proxy.destinations.equals(Collections.singleton("www.youtube.com")),"only selected ping domain contacted: "+type);
            check(result.endpoint.latencyTarget.equals(ping)&&ProxyEndpoint.fromJson(result.endpoint.json()).latencyTarget.equals(ping),"ping provenance survives restart");
            ScanCheckpoint state=new ScanCheckpoint(Collections.singletonList(proxy.endpoint()),new ProxyScanner.Options(100,false,false,7),"CATALOG");
            ScanProbePolicy.configure(false,ServiceTargets.ALL[0].url());check(!state.compatible()&&frozen.target().url().equals(ping),"changed scan profile invalidates resume without changing captured profile");
            ScanProbePolicy.configure(false,ping);result=ScanProbePolicy.profile().inspect(proxy.endpoint(),false,new AtomicBoolean(),null,Socket::new,InetAddress::getByName);
            check(result.endpoint!=null&&result.endpoint.full()&&result.endpoint.latencyTarget.equals(ping),"full service scan retains selected latency origin");
            check(!ProxyResults.PingLimit.UNDER_2000.accepts(result.endpoint.withLatency(1,"")),"old unrelated ping is not accepted for selected-domain limit");
            AtomicReference<String> error=new AtomicReference<>();AtomicLong bytes=new AtomicLong();
            new DownloadSpeedTest().run(new DownloadSpeedTest.Transport(){public boolean valid(){return true;}public HttpURLConnection open(URL u){return new ProxyHttpConnection(u,proxy.endpoint(),Socket::new,InetAddress::getByName);}},new DownloadSpeedTest.Listener(){public void progress(long b,double r){}public void finished(long b,double r,String e){bytes.set(b);error.set(e);}},file);
            check(error.get().isEmpty()&&bytes.get()==300_000,"real custom download succeeds through selected proxy: "+type);
            check(proxy.requests.stream().anyMatch(r->r.startsWith("GET /fixture.bin?sample=1 HTTP/1.1")),"custom file URL is not changed to a default server or cache query");
        }
        try(SingleProxyHarness.Proxy proxy=new SingleProxyHarness.Proxy(ProxyEndpoint.Type.HTTP,"valid",1000)){
            AtomicReference<String> error=new AtomicReference<>();
            new DownloadSpeedTest().run(new DownloadSpeedTest.Transport(){public boolean valid(){return true;}public HttpURLConnection open(URL u){return new ProxyHttpConnection(u,proxy.endpoint(),Socket::new,InetAddress::getByName);}},new DownloadSpeedTest.Listener(){public void progress(long b,double r){}public void finished(long b,double r,String e){error.set(e);}},file);
            check(error.get().contains("کافی")&&proxy.accepted.get()==1,"small samples fail without contacting a substitute download server");
        }
        try(SingleProxyHarness.Proxy proxy=new SingleProxyHarness.Proxy(ProxyEndpoint.Type.HTTP,"html",300_000)){
            AtomicReference<String> error=new AtomicReference<>();
            new DownloadSpeedTest().run(new DownloadSpeedTest.Transport(){public boolean valid(){return true;}public HttpURLConnection open(URL u){return new ProxyHttpConnection(u,proxy.endpoint(),Socket::new,InetAddress::getByName);}},new DownloadSpeedTest.Listener(){public void progress(long b,double r){}public void finished(long b,double r,String e){error.set(e);}},file);
            check(error.get().contains("HTML")&&proxy.accepted.get()==1,"HTML page cannot masquerade as a file speed sample");
        }
        ServiceTargets.configure(Collections.singletonList(ServiceTargets.custom("wrong certificate","https://certificate-mismatch.example/")));
        try(SingleProxyHarness.Proxy proxy=new SingleProxyHarness.Proxy(ProxyEndpoint.Type.HTTP,"valid",300_000)){
            AtomicReference<String> error=new AtomicReference<>();
            new DownloadSpeedTest().run(new DownloadSpeedTest.Transport(){public boolean valid(){return true;}public HttpURLConnection open(URL u){return new ProxyHttpConnection(u,proxy.endpoint(),Socket::new,InetAddress::getByName);}},new DownloadSpeedTest.Listener(){public void progress(long b,double r){}public void finished(long b,double r,String e){error.set(e);}},"https://certificate-mismatch.example/fixture.bin");
            check(!error.get().isEmpty()&&proxy.accepted.get()==1,"custom-domain certificate error cannot trigger a default-server fallback");
        }
        ServiceTargets.configure(Collections.emptyList());
        AtomicInteger opened=new AtomicInteger(),callbacks=new AtomicInteger();
        new DownloadSpeedTest().run(new DownloadSpeedTest.Transport(){public boolean valid(){return true;}public HttpURLConnection open(URL u){opened.incrementAndGet();throw new AssertionError();}},new DownloadSpeedTest.Listener(){public void progress(long b,double r){}public void finished(long b,double r,String e){callbacks.incrementAndGet();check(!e.isEmpty(),"invalid domain reports failure");}},"https://other.example/file");
        check(opened.get()==0&&callbacks.get()==1,"invalid custom destination never leaves the transport and finishes once");
        check(Arrays.stream(SourceCatalog.HTTP).filter(s->s.type==ProxyEndpoint.Type.HTTPS).count()==10,"ten separately scanned TLS candidate feeds");
        ScanProbePolicy.configure(false,"");
        System.out.println("PASS: "+checks+" configured ping/download target checks with real local proxy TLS");
    }
}
