import ir.persiansocks.app.*;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.sun.net.httpserver.HttpServer;

public final class ScanHarness {
    static SSLContext tls;
    static int passed;
    static void verify(boolean ok,String name) { if(!ok) throw new AssertionError(name); passed++; System.out.println("PASS: "+name); }
    static void bytes(InputStream in,int n)throws Exception { if(in.readNBytes(n).length!=n) throw new EOFException(); }
    static final class Mock implements AutoCloseable {
        final ServerSocket listener=new ServerSocket(0,64,InetAddress.getLoopbackAddress());
        final ExecutorService pool=Executors.newCachedThreadPool(r->{Thread t=new Thread(r);t.setDaemon(true);return t;});
        final CountDownLatch connected=new CountDownLatch(1);
        final AtomicInteger fallbackHits=new AtomicInteger();
        final String mode;
        final Set<String> seen = ConcurrentHashMap.newKeySet();
        final DatagramSocket echo = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        Mock(String mode)throws Exception {
            this.mode=mode;
            pool.submit(() -> { byte[] b=new byte[65535]; while(!echo.isClosed()) try {
                DatagramPacket packet=new DatagramPacket(b,b.length); echo.receive(packet);
                echo.send(new DatagramPacket(packet.getData(),packet.getLength(),packet.getSocketAddress()));
            } catch(Exception ex) { break; } });
            pool.submit(()-> { while(!listener.isClosed()) try { Socket s=listener.accept();pool.submit(()->serve(s)); }catch(Exception ex){break;} });
        }
        ProxyEndpoint endpoint() { return new ProxyEndpoint("127.0.0.1",listener.getLocalPort(),-1,false); }
        void serve(Socket socket) {
            try(Socket s=socket) {
                s.setSoTimeout(15000); connected.countDown();
                InputStream in=s.getInputStream();OutputStream out=s.getOutputStream();
                if(mode.startsWith("tunnel-")) {
                    try(SSLSocket secure=(SSLSocket)tls.getSocketFactory().createSocket(s,"web.telegram.org",443,true)) {
                        secure.setUseClientMode(false);secure.setSoTimeout(10000);secure.startHandshake();
                        BufferedReader r=new BufferedReader(new InputStreamReader(secure.getInputStream()));
                        String first=r.readLine(), requested="";
                        for(String row;(row=r.readLine())!=null&&!row.isEmpty();) if(row.startsWith("Host: "))requested=row.substring(6);
                        int status=mode.equals("tunnel-denied")?403:200;
                        if(mode.equals("tunnel-youtube")&&!requested.equals("www.youtube.com"))status=403;
                        if(mode.equals("tunnel-facebook")&&!requested.equals("www.facebook.com"))status=403;
                        secure.getOutputStream().write(("HTTP/1.1 "+status+" Reply\r\nContent-Length: 0\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                        secure.getOutputStream().flush();
                    }
                    return;
                }
                bytes(in,3);
                if(mode.equals("stall")) { while(in.read()!=-1){} return; }
                out.write(new byte[]{5,(byte)(mode.equals("auth")?255:0)});out.flush();
                if(mode.equals("auth"))return;
                int version=in.read(), command=in.read(), reserved=in.read(), type=in.read();
                String host="";
                if(type==3) { int n=in.read();host=new String(in.readNBytes(n),java.nio.charset.StandardCharsets.US_ASCII); }
                else if(type==1)bytes(in,4);else return;
                bytes(in,2);
                if(!host.isEmpty()) seen.add(host);
                boolean reject=mode.equals("reject") || (mode.equals("fallback") && host.equals("web.telegram.org"));
                out.write(new byte[]{5,(byte)(reject?5:0),0,1,127,0,0,1,(byte)(echo.getLocalPort()>>8),(byte)echo.getLocalPort()});out.flush();
                if(reject||mode.equals("ack-only"))return;
                if(command==3) { while(in.read()!=-1) {} return; }
                if(host.equals("www.youtube.com"))fallbackHits.incrementAndGet();
                try(SSLSocket secure=(SSLSocket)tls.getSocketFactory().createSocket(s,host,443,true)) {
                    secure.setUseClientMode(false);secure.setSoTimeout(10000);secure.startHandshake();
                    BufferedReader r=new BufferedReader(new InputStreamReader(secure.getInputStream()));
                    String first=r.readLine();if(first==null||!first.startsWith("GET "))return;
                    for(String row;(row=r.readLine())!=null&&!row.isEmpty();){}
                    int status=mode.equals("bad-http")?503:mode.equals("forbidden")?403:200;
                    if(mode.equals("only-telegram") && !host.equals("web.telegram.org"))status=403;
                    if(mode.equals("only-youtube") && !host.equals("www.youtube.com"))status=429;
                    if(mode.equals("only-instagram") && !host.equals("www.instagram.com"))status=403;
                    if(mode.equals("only-facebook") && !host.equals("www.facebook.com"))status=403;
                    String extra=mode.equals("long-header")?"X-Policy: "+"a".repeat(5000)+"\r\n":"";
                    secure.getOutputStream().write(("HTTP/1.1 "+status+" Reply\r\n"+extra+"Content-Length: 0\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    secure.getOutputStream().flush();
                }
            }catch(Exception ignored){}
        }
        public void close()throws Exception {listener.close();echo.close();pool.shutdownNow();}
    }
    static Socks5Probe.Result probe(String mode)throws Exception {
        try(Mock m=new Mock(mode)) {return Socks5Probe.inspect(m.endpoint(),false,new AtomicBoolean(false),null);}
    }
    static void init(String path)throws Exception {
        KeyStore key=KeyStore.getInstance("PKCS12");try(InputStream in=new FileInputStream(path)){key.load(in,"test-only-password".toCharArray());}
        KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(key,"test-only-password".toCharArray());
        TrustManagerFactory tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(key);
        tls=SSLContext.getInstance("TLS");tls.init(km.getKeyManagers(),tm.getTrustManagers(),new SecureRandom());SSLContext.setDefault(tls);
    }
    public static void main(String[]args)throws Exception {
        init(args[0]);
        verify(ProxyScanner.parseList("socks5://8.8.8.8:1080\n1.1.1.1:1080\n8.8.8.8:1080\n192.168.1.1:80\n256.1.1.1:80",true).size()==2,"parse formats, reject invalid/private, deduplicate");
        List<ProxyEndpoint> large=new ArrayList<>();
        for(int i=1;i<=1000;i++)large.add(new ProxyEndpoint("8.8.8.8",i,-1,false));
        ProxyEndpoint small=new ProxyEndpoint("1.1.1.1",1080,-1,false);
        List<ProxyEndpoint> merged=ProxyScanner.mergeCandidates(Collections.emptyList(),Arrays.asList(large,Arrays.asList(small)),Collections.emptyList(),500);
        verify(merged.size()==500&&merged.get(1).address().equals(small.address()),"balanced 500 candidate selection, no old 80-item cutoff");
        verify(new HashSet<>(Arrays.asList(merged.get(0).address(),merged.get(1).address())).size()==2,"different source endpoints preserved");
        verify(ProxyScanner.mergeCandidates(Arrays.asList(small),Arrays.asList(Arrays.asList(small)),Arrays.asList(small),10).size()==1,"deduplicate manual, live and embedded addresses");
        verify(new ProxyScanner.Options(200000,false).maxTests==100000,"enforce configurable 100,000 candidate bound");
        verify(SourceCatalog.DEFAULT.length==24,"24 source definitions including filtered HProxy");
        ServiceTargets.Target facebook=ServiceTargets.custom("فیسبوک","www.facebook.com");
        ServiceTargets.configure(Arrays.asList(facebook));
        try(Mock m=new Mock("only-facebook")) {
            ProxyEndpoint customOnly=Socks5Probe.inspect(m.endpoint(),false,new AtomicBoolean(false),null).endpoint;
            verify(customOnly!=null&&customOnly.serviceMask==0&&customOnly.passed(facebook)&&customOnly.hasAnyService(),
                "A proxy that only supports a custom domain is accepted after valid HTTPS");
            verify(customOnly.checkedMask==7&&customOnly.extraChecked.contains(facebook.id)&&m.seen.size()==4,
                "All built-in and custom domains are tested and stored separately");
            ProxyEndpoint loaded=ProxyEndpoint.fromJson(customOnly.json());
            verify(loaded.passed(facebook)&&ProxyResults.accepts(loaded,facebook.id),"Custom service result survives save/load and filtering");
            verify(!ProxyResults.accepts(loaded,"FULL"),"Custom-only success does not falsely satisfy Full");
            try(Mock direct=new Mock("tunnel-valid")) {
                TunnelVerifier.Result proof=TunnelVerifier.check(new TunnelVerifier.Transport(){
                    public Socket create(){return new Socket(){
                        @Override public void connect(SocketAddress address,int timeout)throws IOException{
                            super.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(),direct.listener.getLocalPort()),timeout);
                        }
                    };}
                    public InetAddress[] resolve(String host){return new InetAddress[]{InetAddress.getLoopbackAddress()};}
                },new AtomicBoolean(false),ConcurrentHashMap.newKeySet(),new ServiceTargets.Target[]{facebook},true);
                verify(proof.accepts(facebook.id)&&!proof.accepts("FULL"),"VPN-bound custom-domain health check verifies the selected filter");
            }
            try(Mock direct=new Mock("tunnel-facebook")) {
                TunnelVerifier.Result proof=TunnelVerifier.check(new TunnelVerifier.Transport(){
                    public Socket create(){return new Socket(){
                        @Override public void connect(SocketAddress address,int timeout)throws IOException{
                            super.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(),direct.listener.getLocalPort()),timeout);
                        }
                    };}
                    public InetAddress[] resolve(String host){return new InetAddress[]{InetAddress.getLoopbackAddress()};}
                },new AtomicBoolean(false),ConcurrentHashMap.newKeySet(),ServiceTargets.current(),true,true);
                verify(proof.passed==0&&proof.accepts("ALL")&&proof.accepts(facebook.id),
                    "All health check accepts a working custom service when built-in services return 403");
            }
        }
        ServiceTargets.configure(Collections.emptyList());
        try(Mock first=new Mock("valid");Mock backup=new Mock("valid")) {
            ProxyEndpoint one=Socks5Probe.check(first.endpoint()),two=Socks5Probe.check(backup.endpoint());
            verify(one!=null&&two!=null,"Both failover candidates first pass real local SOCKS/TLS tests");
            first.close();
            boolean failed=Socks5Probe.check(one)==null;
            FailoverPolicy plan=new FailoverPolicy();plan.reset(one.address());
            verify(failed&&!plan.health(false)&&plan.health(false),"A stopped proxy is detected and two samples trigger recovery");
            plan.failed(one.address(),System.currentTimeMillis());
            ProxyEndpoint next=plan.next(Arrays.asList(one,two),one.address(),"FULL",ProxyResults.Sort.FASTEST,System.currentTimeMillis());
            verify(next!=null&&next.address().equals(two.address())&&Socks5Probe.check(next).full(),
                "The next compatible proxy is actually reverified over SOCKS/TLS after the first closes");
        }
        verify(probe("valid").endpoint!=null,"authenticated TLS + HTTP proxy accepted");
        verify(probe("ack-only").endpoint==null,"SOCKS ACK without HTTPS rejected");
        verify(probe("reject").failure==Socks5Probe.Failure.DESTINATION_REJECTED,"destination rejection diagnosis");
        verify(probe("auth").failure==Socks5Probe.Failure.SOCKS_AUTH,"authentication requirement diagnosis");
        verify(probe("bad-http").endpoint==null,"503 on all destinations not counted healthy");
        verify(probe("forbidden").endpoint==null,"403 on every service is never healthy");
        try(Mock m=new Mock("fallback")) {
            Socks5Probe.Result r=Socks5Probe.inspect(m.endpoint(),false,new AtomicBoolean(false),null);
            verify(r.endpoint!=null&&r.endpoint.serviceMask==6&&r.endpoint.checkedMask==7&&m.fallbackHits.get()==1,"YouTube and Instagram still tested when Telegram is rejected");
        }
        for(String mode:Arrays.asList("only-telegram","only-youtube","only-instagram")) {
            int expected=mode.equals("only-telegram")?1:mode.equals("only-youtube")?2:4;
            ProxyEndpoint e=probe(mode).endpoint;
            verify(e!=null&&e.serviceMask==expected&&e.checkedMask==7,"at least one service sufficient: "+mode);
            ProxyEndpoint saved=ProxyEndpoint.fromJson(e.json());
            verify(saved.serviceMask==expected&&saved.checkedMask==7&&saved.testedAt>0,"service labels survive save/load: "+mode);
        }
        verify(ProxyEndpoint.fromJson(new org.json.JSONObject("{\"host\":\"1.1.1.1\",\"port\":1080}")).serviceMask==0,"legacy entries not silently promoted to service-verified results");
        try(Mock m=new Mock("valid")) {
            ProxyEndpoint e=Socks5Probe.check(m.endpoint());
            verify(e!=null&&e.serviceMask==7&&m.seen.size()==3,"all three services tested after first success");
            AtomicInteger tcpProtected=new AtomicInteger(),udpProtected=new AtomicInteger();
            try(ProtectedSocksGateway gate=new ProtectedSocksGateway(m.endpoint(),new ProtectedSocksGateway.Protector(){
                public boolean tcp(Socket s){if(!s.isBound())throw new AssertionError("Socket FD must exist before protection");tcpProtected.incrementAndGet();return true;}
                public boolean udp(DatagramSocket s){udpProtected.incrementAndGet();return true;}
                public InetAddress resolve(String host)throws Exception{return InetAddress.getByName(host);}
            })) {
                ProxyEndpoint result=GatewayTestSupport.check(gate);
                verify(result!=null&&result.serviceMask==7,"TLS/HTTPS for all services through protected SOCKS gateway");
                verify(tcpProtected.get()>=4,"every upstream TCP socket goes through protection hook");
                try(Socket control=new Socket("127.0.0.1",gate.port());DatagramSocket client=new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(),0))) {
                    control.setSoTimeout(3000);client.setSoTimeout(3000);
                    OutputStream out=control.getOutputStream();InputStream in=control.getInputStream();
                    GatewayTestSupport.authenticate(control,gate);
                    out.write(new byte[]{5,3,0,1,0,0,0,0,0,0});out.flush();
                    byte[] reply=in.readNBytes(10);verify(reply.length==10&&reply[1]==0,"gateway rewrites UDP association to loopback");
                    int port=((reply[8]&255)<<8)|(reply[9]&255);
                    byte[] packet=new byte[]{0,0,0,1,8,8,8,8,0,53,1,2,3,4};
                    client.send(new DatagramPacket(packet,packet.length,InetAddress.getLoopbackAddress(),port));
                    DatagramPacket returned=new DatagramPacket(new byte[200],200);client.receive(returned);
                    verify(Arrays.equals(packet,Arrays.copyOf(returned.getData(),returned.getLength())),"two-way UDP relay preserves SOCKS datagram and payload");
                    verify(udpProtected.get()>0,"physical UDP socket uses protection hook");
                }
            }
            try(ProtectedSocksGateway denied=new ProtectedSocksGateway(m.endpoint(),new ProtectedSocksGateway.Protector(){
                public boolean tcp(Socket s){return false;}public boolean udp(DatagramSocket s){return false;}
                public InetAddress resolve(String h)throws Exception{return InetAddress.getByName(h);}
            })) {verify(GatewayTestSupport.check(denied)==null,"failed protection cannot yield a healthy proxy");}
        }
        verify(probe("long-header").endpoint!=null,"large valid service headers accepted within bounded limits");
        SSLContext defaultTrust=SSLContext.getInstance("TLS");defaultTrust.init(null,null,null);SSLContext.setDefault(defaultTrust);
        verify(probe("valid").endpoint==null,"untrusted TLS certificate rejected for all targets");
        SSLContext.setDefault(tls);
        try(Mock m=new Mock("stall")) {
            AtomicBoolean cancelled=new AtomicBoolean(false);Set<Socket> sockets=ConcurrentHashMap.newKeySet();
            ExecutorService e=Executors.newSingleThreadExecutor();
            Future<Socks5Probe.Result> f=e.submit(()->Socks5Probe.inspect(m.endpoint(),true,cancelled,sockets));
            verify(m.connected.await(2,TimeUnit.SECONDS),"stalled probe actually connects");
            long start=System.nanoTime();cancelled.set(true);for(Socket s:sockets)s.close();
            verify(f.get(1,TimeUnit.SECONDS).failure==Socks5Probe.Failure.CANCELLED,"cancellation closes a stalled socket promptly");
            e.shutdownNow();
        }
        HttpServer http=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
        http.createContext("/bad",x->{x.sendResponseHeaders(404,-1);x.close();});
        http.createContext("/mirror",x->{byte[]b="8.8.8.8:1080\n8.8.8.8:1080\n".getBytes();x.sendResponseHeaders(200,b.length);x.getResponseBody().write(b);x.close();});
        http.start();
        try {
            ProxyScanner scanner=new ProxyScanner();CountDownLatch collected=new CountDownLatch(1);AtomicReference<String> report=new AtomicReference<>("");
            String base="http://127.0.0.1:"+http.getAddress().getPort();
            scanner.start(Collections.emptyList(),Collections.emptyList(),Arrays.asList(new ProxyScanner.Source("fixture",base+"/bad",base+"/mirror")),new ProxyScanner.Options(100,false),new ProxyScanner.Listener(){
                public void onFound(ProxyEndpoint p){}
                public void onProgress(int d,int t,int h){collected.countDown();}
                public void onFinished(List<ProxyEndpoint> p,String s){}
                public void onDiagnostics(String r){report.set(r);}
            });
            verify(collected.await(3,TimeUnit.SECONDS),"source downloader reaches test stage");
            scanner.stop();
            verify(report.get().contains("مسیر جایگزین")&&report.get().contains("fixture: 1"),"failed primary uses mirror and reports deduplicated source count");
            try(Mock m=new Mock("valid")) {
                CountDownLatch finished=new CountDownLatch(1);AtomicInteger healthy=new AtomicInteger();
                scanner.start(Collections.emptyList(),Arrays.asList(m.endpoint()),Collections.emptyList(),new ProxyScanner.Options(100,false),new ProxyScanner.Listener(){
                    public void onProgress(int d,int t,int h){}
                    public void onFound(ProxyEndpoint p){healthy.incrementAndGet();}
                    public void onFinished(List<ProxyEndpoint> p,String s){finished.countDown();}
                });
                verify(finished.await(4,TimeUnit.SECONDS)&&healthy.get()==1,"restart uses embedded fallback when live sources are absent");
                scanner.stop();
            }
        }finally{http.stop(0);}
        for(String mode:Arrays.asList("tunnel-valid","tunnel-youtube","tunnel-denied")) {
            try(Mock m=new Mock(mode)) {
                AtomicInteger created=new AtomicInteger(),resolved=new AtomicInteger();
                TunnelVerifier.Result proof=TunnelVerifier.check(new TunnelVerifier.Transport(){
                    public Socket create(){created.incrementAndGet();return new Socket(){
                        @Override public void connect(SocketAddress target,int timeout)throws IOException {
                            if(((InetSocketAddress)target).getPort()!=443)throw new AssertionError("HTTPS port");
                            super.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(),m.listener.getLocalPort()),timeout);
                        }
                    };}
                    public InetAddress[] resolve(String host){resolved.incrementAndGet();return new InetAddress[]{InetAddress.getLoopbackAddress()};}
                },new AtomicBoolean(false),ConcurrentHashMap.newKeySet());
                int expected=mode.equals("tunnel-valid")?7:mode.equals("tunnel-youtube")?2:0;
                verify(proof.passed==expected&&proof.checked==7&&created.get()==3&&resolved.get()==3,"VPN-bound transport tests every service and enforces status: "+mode);
            }
        }
        AtomicInteger opened=new AtomicInteger();
        try(Mock m=new Mock("valid")){
            List<ProxyEndpoint> candidates=new ArrayList<>();
            for(int i=1;i<=72;i++)candidates.add(new ProxyEndpoint("127.0.0."+i,m.listener.getLocalPort(),-1,false));
            ProxyScanner scanner=new ProxyScanner();
            CountDownLatch finished=new CountDownLatch(1);
            AtomicInteger done=new AtomicInteger(),total=new AtomicInteger(),good=new AtomicInteger();
            scanner.start(Collections.emptyList(),candidates,Collections.emptyList(),new ProxyScanner.Options(5000,false,true),
                new ProxyScanner.Listener(){
                    public void onFound(ProxyEndpoint p){good.incrementAndGet();}
                    public void onProgress(int d,int t,int h){done.set(d);total.set(t);}
                    public void onFinished(List<ProxyEndpoint> p,String message){finished.countDown();}
                });
            verify(finished.await(8,TimeUnit.SECONDS)&&done.get()==72&&total.get()==72&&good.get()==1,
                "Max scan refills its bounded submission window and completes every candidate");
            scanner.stop();
        }
        TunnelVerifier.Result dnsFailure=TunnelVerifier.check(new TunnelVerifier.Transport(){
            public Socket create(){opened.incrementAndGet();return new Socket();}
            public InetAddress[] resolve(String host)throws Exception{throw new UnknownHostException("VPN resolver failed");}
        },new AtomicBoolean(false),ConcurrentHashMap.newKeySet());
        verify(dnsFailure.passed==0&&opened.get()==0,"VPN DNS failure cannot silently fall back to physical routing");
        System.out.println("RESULT: "+passed+" checks passed");
    }
}
