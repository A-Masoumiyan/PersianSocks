import ir.persiansocks.app.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class OptionalTlsHarness {
    static int passed;
    static void check(boolean ok,String name){if(!ok)throw new AssertionError(name);passed++;System.out.println("PASS: "+name);}
    public static void main(String[] args)throws Exception {
        check(!DistributionPolicy.play()&&!DistributionPolicy.encryptedOnly(),"Normal app starts with TLS-only mode off");
        ScanHarness.init(args[0]);ServiceTargets.configure(Collections.emptyList());
        try(ScanHarness.Mock socks=new ScanHarness.Mock("valid");ProtocolTrafficHarness.HttpProxy http=new ProtocolTrafficHarness.HttpProxy(false,"valid");ProtocolTrafficHarness.HttpProxy tls=new ProtocolTrafficHarness.HttpProxy(true,"valid")){
            check(Socks5Probe.check(socks.endpoint())!=null,"SOCKS service probes work with TLS-only off");
            check(Socks5Probe.check(http.endpoint())!=null,"HTTP CONNECT service probes work with TLS-only off");
            check(Socks5Probe.check(tls.endpoint())!=null,"Explicit HTTPS proxy still validates TLS with TLS-only off");
            List<ProxyEndpoint> candidates=new ArrayList<>();for(ProxyEndpoint p:Arrays.asList(socks.endpoint(),http.endpoint(),tls.endpoint()))candidates.add(new ProxyEndpoint(p.host,p.port,10,false,7,7,1).withType(p.type));
            check(ProxyResults.visible(candidates,"FULL",ProxyResults.Sort.FASTEST).size()==3,"All protocol results are eligible when TLS-only is off");
            int saved=3;check(new ProxyScanner.Options(100,false,false,saved).types==saved,"Ordinary scan selection is respected while off");
            DistributionPolicy.setUserTlsOnly(true);
            check(DistributionPolicy.encryptedOnly(),"Optional TLS-only mode can be enabled");
            check(new ProxyScanner.Options(100,false,false,saved).types==4,"Enabling TLS-only overrides ordinary scan selection");
            check(Socks5Probe.check(socks.endpoint())==null&&Socks5Probe.check(http.endpoint())==null,"Enabling TLS-only prevents plain SOCKS and HTTP probes");
            check(Socks5Probe.check(tls.endpoint())!=null,"TLS service probes remain usable when enabled");
            check(ProxyResults.visible(candidates,"FULL",ProxyResults.Sort.FASTEST).size()==1,"Only encrypted result is eligible while enabled");
            FailoverPolicy policy=new FailoverPolicy();policy.reset(tls.endpoint().key());policy.failed(tls.endpoint().key(),1);
            check(policy.next(candidates,tls.endpoint().key(),"ALL",ProxyResults.Sort.FASTEST,1)==null,"Enabled mode cannot switch to a plaintext backup");
            DistributionPolicy.setUserTlsOnly(false);
            check(!DistributionPolicy.encryptedOnly()&&new ProxyScanner.Options(100,false,false,saved).types==saved,"Disabling TLS-only restores the original scan type selection");
            check(ProxyResults.visible(candidates,"FULL",ProxyResults.Sort.FASTEST).size()==3,"Disabling TLS-only restores all eligible protocols");
            check(Socks5Probe.check(socks.endpoint())!=null&&Socks5Probe.check(http.endpoint())!=null,"Plain protocol connections work again after toggling off");
            for(boolean enabled:new boolean[]{true,false}){
                DistributionPolicy.setUserTlsOnly(enabled);ProxyScanner scanner=new ProxyScanner();CountDownLatch done=new CountDownLatch(1);AtomicReference<List<ProxyEndpoint>> found=new AtomicReference<>();
                scanner.start(Arrays.asList(socks.endpoint(),http.endpoint(),tls.endpoint()),Collections.emptyList(),Collections.emptyList(),new ProxyScanner.Options(100,false,false,7),new ProxyScanner.Listener(){
                    public void onFound(ProxyEndpoint p){}public void onProgress(int d,int t,int c){}public void onFinished(List<ProxyEndpoint> results,String message){found.set(results);done.countDown();}
                });
                check(done.await(10,TimeUnit.SECONDS)&&found.get().size()==(enabled?1:3),"Actual scanner follows optional mode: "+enabled);scanner.stop();
            }
        }
        DistributionPolicy.setUserTlsOnly(false);
        try(PlaySecurityHarness.EchoTlsProxy server=new PlaySecurityHarness.EchoTlsProxy("TLSv1.3");PlaySecurityHarness.WireTap tap=new PlaySecurityHarness.WireTap(server.listener.getLocalPort());ProtectedSocksGateway gate=new ProtectedSocksGateway(tap.endpoint(),PlaySecurityHarness.protector(new AtomicInteger()));Socket client=new Socket()){
            PlaySecurityHarness.nativeConnect(client,gate.port(),"example.com",80);String marker="TLS-OFF-"+UUID.randomUUID();
            client.getOutputStream().write(("GET /"+marker+" HTTP/1.1\r\nHost: example.com\r\n\r\n").getBytes(StandardCharsets.US_ASCII));client.getOutputStream().flush();
            check(PlaySecurityHarness.header(client.getInputStream()).startsWith("HTTP/1.1 200"),"Native-style gateway still supports encrypted proxy with optional mode off");
            check(server.payload.get().contains(marker)&&!new String(tap.bytes(tap.outbound),StandardCharsets.ISO_8859_1).contains(marker),"Off setting does not downgrade an explicitly selected HTTPS proxy");
        }
        check(!DistributionPolicy.encryptedOnly(),"Optional test leaves the default-off policy intact");
        System.out.println("RESULT: "+passed+" checks passed");
    }
}
