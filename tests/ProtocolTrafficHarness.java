import ir.persiansocks.app.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.*;

public final class ProtocolTrafficHarness {
    static int passed;
    static void check(boolean ok,String name){if(!ok)throw new AssertionError(name);passed++;System.out.println("PASS: "+name);}
    static final class HttpProxy implements AutoCloseable {
        final ServerSocket listener=new ServerSocket(0,64,InetAddress.getLoopbackAddress());
        final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
        final ExecutorService pool=Executors.newCachedThreadPool(r->{Thread t=new Thread(r);t.setDaemon(true);return t;});
        final AtomicInteger connects=new AtomicInteger();
        final CountDownLatch received=new CountDownLatch(1);
        final boolean tlsProxy;final String mode;
        HttpProxy(boolean tls,String mode)throws Exception {
            this.tlsProxy=tls;this.mode=mode;
            pool.submit(()->{while(!listener.isClosed())try{Socket s=listener.accept();sockets.add(s);pool.submit(()->serve(s));}catch(Exception ex){break;}});
        }
        ProxyEndpoint endpoint(){return new ProxyEndpoint("127.0.0.1",listener.getLocalPort(),-1,false).withType(tlsProxy?ProxyEndpoint.Type.HTTPS:ProxyEndpoint.Type.HTTP);}
        void serve(Socket raw){
            try(Socket owner=raw){
                Socket s=raw;s.setSoTimeout(12000);
                if(tlsProxy){SSLSocket t=(SSLSocket)ScanHarness.tls.getSocketFactory().createSocket(raw,"127.0.0.1",listener.getLocalPort(),true);t.setUseClientMode(false);t.startHandshake();s=t;}
                InputStream in=s.getInputStream();ByteArrayOutputStream h=new ByteArrayOutputStream();int tail=0;
                while(h.size()<16384){int c=in.read();if(c<0)return;h.write(c);tail=(tail<<8)|c;if(tail==0x0d0a0d0a)break;}
                String first=h.toString("US-ASCII").split("\r\n")[0];
                if(!first.startsWith("CONNECT "))throw new IOException("Expected CONNECT");
                connects.incrementAndGet();received.countDown();
                if(mode.equals("stall")){while(in.read()!=-1){}return;}
                String reply=mode.equals("auth")?"HTTP/1.1 407 Auth\r\n\r\n":mode.equals("denied")?"HTTP/1.1 403 Denied\r\n\r\n":
                    mode.equals("redirect")?"HTTP/1.1 302 Found\r\n\r\n":mode.equals("malformed")?"garbage 200\r\n\r\n":
                    mode.equals("long")?"HTTP/1.1 200 OK\r\nX: "+"a".repeat(17000):"HTTP/1.1 200 Connection established\r\n\r\n";
                s.getOutputStream().write(reply.getBytes("US-ASCII"));s.getOutputStream().flush();
                if(!mode.equals("valid")&&!mode.equals("target-denied"))return;
                String authority=first.split(" ")[1];String host=authority.substring(0,authority.lastIndexOf(':'));
                try(SSLSocket target=(SSLSocket)ScanHarness.tls.getSocketFactory().createSocket(s,host,443,true)){
                    target.setUseClientMode(false);target.startHandshake();
                    BufferedReader r=new BufferedReader(new InputStreamReader(target.getInputStream()));
                    for(String line;(line=r.readLine())!=null&&!line.isEmpty();){}
                    target.getOutputStream().write(("HTTP/1.1 "+(mode.equals("target-denied")?403:200)+" Reply\r\nContent-Length: 0\r\n\r\n").getBytes("US-ASCII"));target.getOutputStream().flush();
                }
            }catch(Exception ignored){}finally{sockets.remove(raw);}
        }
        public void close()throws Exception {listener.close();for(Socket s:sockets)s.close();pool.shutdownNow();}
    }
    static Socks5Probe.Result inspect(HttpProxy server){return Socks5Probe.inspect(server.endpoint(),false,new AtomicBoolean(false),null);}
    static final class DownloadConnection extends HttpURLConnection {
        final long size;long read;volatile boolean closed;final String mode;
        DownloadConnection(long size,String mode)throws Exception {super(new URL("https://fixture.example.org"));this.size=size;this.mode=mode;}
        public void connect(){} public void disconnect(){closed=true;}public boolean usingProxy(){return false;}
        public int getResponseCode(){return mode.equals("redirect")?302:200;}
        public String getContentEncoding(){return mode.equals("gzip")?"gzip":null;}
        public InputStream getInputStream(){return new InputStream(){
            public int read()throws IOException{byte[] b=new byte[1];return read(b,0,1)<0?-1:0;}
            public int read(byte[] b,int off,int count)throws IOException {
                if(mode.equals("stall")){while(!closed)try{Thread.sleep(10);}catch(InterruptedException ex){throw new IOException(ex);}throw new IOException("closed");}
                if(closed)throw new IOException("closed");if(read>=size)return -1;
                int n=(int)Math.min(count,size-read);read+=n;return n;
            }
        };}
    }
    static String runDownload(DownloadConnection c,boolean valid,AtomicLong bytes,AtomicReference<Double> rate){
        AtomicReference<String> error=new AtomicReference<>();
        new DownloadSpeedTest().run(new DownloadSpeedTest.Transport(){public HttpURLConnection open(URL url){return c;}public boolean valid(){return valid;}},
            new DownloadSpeedTest.Listener(){public void progress(long b,double m){}public void finished(long b,double m,String e){bytes.set(b);rate.set(m);error.set(e);}});
        return error.get();
    }
    public static void main(String[] args)throws Exception {
        ScanHarness.init(args[0]);ServiceTargets.configure(Collections.emptyList());
        ProxyEndpoint socks=ProxyEndpoint.parse("8.8.8.8:1080"),http=ProxyEndpoint.parse("http://8.8.8.8:1080"),https=ProxyEndpoint.parse("https://8.8.8.8:1080");
        check(socks.type==ProxyEndpoint.Type.SOCKS5&&socks.key().equals("8.8.8.8:1080"),"Legacy endpoint identity stays compatible");
        check(http.type==ProxyEndpoint.Type.HTTP&&https.type==ProxyEndpoint.Type.HTTPS&&!http.key().equals(https.key()),"HTTP and TLS-proxy identities are distinct at the same address");
        check(ProxyEndpoint.parse("https://proxy.example.org:443")!=null&&ProxyEndpoint.parse("https://user:pass@proxy.example.org:443")==null,"TLS proxy hostname support rejects embedded credentials");
        check(ProxyEndpoint.fromJson(http.json()).type==ProxyEndpoint.Type.HTTP&&ProxyEndpoint.fromJson(https.json()).type==ProxyEndpoint.Type.HTTPS,"Stored protocol survives restart");
        check(ProxyScanner.mergeCandidates(Arrays.asList(socks,http,https),Collections.emptyList(),Collections.emptyList(),10).size()==3,"Candidate dedup preserves different protocols on one address");
        check(ProxyScanner.parseStream(new StringReader("8.8.8.8:80\n"),true,100,new AtomicBoolean(false),ProxyEndpoint.Type.HTTP).get(0).type==ProxyEndpoint.Type.HTTP,"Max streaming parser retains source protocol");
        check(SourceCatalog.custom("HTTP https://example.org/list.txt",1).type==ProxyEndpoint.Type.HTTP&&SourceCatalog.custom("HTTPS https://example.org/tls.txt",1).type==ProxyEndpoint.Type.HTTPS,"Custom list type is independent from HTTPS download transport");
        check(SourceCatalog.custom("https://example.org/list.txt",1).type==ProxyEndpoint.Type.SOCKS5&&SourceCatalog.custom("HTTP http://example.org/list.txt",1)==null,"Legacy custom sources and secure downloads retain their meaning");
        List<ProxyEndpoint> all=new ArrayList<>();for(ProxyEndpoint p:Arrays.asList(socks,http,https))all.add(new ProxyEndpoint(p.host,p.port,10,false,7,7,1).withType(p.type));
        check(ProxyResults.visible(all,"FULL",ProxyResults.Sort.FASTEST,ProxyResults.Protocol.HTTP).size()==2&&ProxyResults.visible(all,"FULL",ProxyResults.Sort.FASTEST,ProxyResults.Protocol.SOCKS).size()==1,"Service and protocol filters intersect correctly");
        FailoverPolicy policy=new FailoverPolicy();policy.reset(all.get(1).key());policy.failed(all.get(1).key(),1);
        check(policy.next(ProxyResults.byProtocol(all,ProxyResults.Protocol.HTTP),all.get(1).key(),"FULL",ProxyResults.Sort.FASTEST,1)==all.get(2),"Failover preserves type and skips only the failed protocol key");
        AutoScanSelection selection=new AutoScanSelection("FULL",ProxyResults.Sort.FASTEST,ProxyResults.Protocol.HTTP);selection.add(all.get(0));selection.add(all.get(1));
        check(selection.best()==all.get(1),"Widget candidate selection respects protocol filter");
        check(new ProxyScanner.Options(100,false,false,6).types==6,"HTTP/HTTPS can be selected without SOCKS for scans");
        for(boolean tls:new boolean[]{false,true})try(HttpProxy server=new HttpProxy(tls,"valid")){
            ProxyEndpoint e=inspect(server).endpoint;
            check(e!=null&&e.full()&&e.type==server.endpoint().type&&!e.udpAdvertised&&server.connects.get()==3,"All service TLS checks succeed through "+server.endpoint().type+" without fake UDP support");
            AtomicInteger protections=new AtomicInteger();
            try(ProtectedSocksGateway gate=new ProtectedSocksGateway(server.endpoint(),new ProtectedSocksGateway.Protector(){
                public boolean tcp(Socket s){if(!s.isBound())throw new AssertionError("Unbound FD");protections.incrementAndGet();return true;}
                public boolean udp(DatagramSocket s){throw new AssertionError("HTTP has no UDP");}
                public InetAddress resolve(String h)throws Exception{return InetAddress.getByName(h);}
            })){
                ProxyEndpoint tunneled=GatewayTestSupport.check(gate);
                check(tunneled!=null&&tunneled.full()&&!tunneled.udpAdvertised&&protections.get()==3,"Native-style loopback SOCKS is relayed through protected "+server.endpoint().type);
                try(Socket client=new Socket("127.0.0.1",gate.port())){
                    client.setSoTimeout(2000);GatewayTestSupport.authenticate(client,gate);
                    client.getOutputStream().write(new byte[]{5,3,0,1,0,0,0,0,0,0});
                    byte[] reply=client.getInputStream().readNBytes(10);
                    check(reply.length==10&&reply[1]==7,"HTTP-family UDP association is explicitly rejected");
                }
            }
        }
        for(String mode:new String[]{"auth","denied","redirect","malformed","long","target-denied"})try(HttpProxy server=new HttpProxy(false,mode)){
            check(inspect(server).endpoint==null,"Reject CONNECT or target response: "+mode);
        }
        try(HttpProxy server=new HttpProxy(true,"valid")){
            Socks5Probe.setResolver(h->InetAddress.getLoopbackAddress());
            ProxyEndpoint wrong=ProxyEndpoint.parse("https://wrong.example.org:"+server.listener.getLocalPort());
            check(Socks5Probe.inspect(wrong,false,new AtomicBoolean(false),null).endpoint==null&&server.connects.get()==0,"TLS proxy certificate name is checked before CONNECT");
            Socks5Probe.setResolver(null);
        }
        try(HttpProxy server=new HttpProxy(false,"stall")){
            AtomicBoolean cancelled=new AtomicBoolean();Set<Socket> active=ConcurrentHashMap.newKeySet();ExecutorService worker=Executors.newSingleThreadExecutor();
            Future<Socks5Probe.Result> f=worker.submit(()->Socks5Probe.inspect(server.endpoint(),true,cancelled,active));
            check(server.received.await(2,TimeUnit.SECONDS),"CONNECT cancellation fixture reaches server");
            cancelled.set(true);for(Socket socket:active)socket.close();
            check(f.get(1,TimeUnit.SECONDS).failure==Socks5Probe.Failure.CANCELLED,"Cancellation closes stalled CONNECT promptly");worker.shutdownNow();
        }
        try(HttpProxy server=new HttpProxy(true,"valid")){
            SSLContext trust=SSLContext.getInstance("TLS");trust.init(null,null,null);SSLContext.setDefault(trust);
            check(inspect(server).endpoint==null&&server.connects.get()==0,"Untrusted TLS-proxy issuer is rejected before CONNECT");
            SSLContext.setDefault(ScanHarness.tls);
        }
        for(int types:new int[]{1,6})try(HttpProxy h=new HttpProxy(false,"valid");ScanHarness.Mock so=new ScanHarness.Mock("valid")){
            ProxyScanner scanner=new ProxyScanner();CountDownLatch done=new CountDownLatch(1);AtomicReference<List<ProxyEndpoint>> found=new AtomicReference<>();
            scanner.start(Arrays.asList(h.endpoint(),so.endpoint()),Collections.emptyList(),Collections.emptyList(),new ProxyScanner.Options(100,false,false,types),new ProxyScanner.Listener(){
                public void onFound(ProxyEndpoint p){}public void onProgress(int d,int t,int c){}
                public void onFinished(List<ProxyEndpoint> results,String message){found.set(results);done.countDown();}
            });
            check(done.await(8,TimeUnit.SECONDS)&&found.get().size()==1&&
                (types==1?found.get().get(0).type==ProxyEndpoint.Type.SOCKS5&&h.connects.get()==0:
                    found.get().get(0).type==ProxyEndpoint.Type.HTTP&&so.seen.isEmpty()),"Actual scanner dials only selected protocol group: "+types);
            scanner.stop();
        }
        TrafficMeter meter=new TrafficMeter(0,0);meter.beginEngine(1_000_000_000L);
        TrafficMeter.Snapshot t=meter.sample(2_000_000,500_000,3_000_000_000L);
        check(t.downloaded==2_000_000&&t.uploaded==500_000&&t.downPerSecond==1_000_000&&t.upPerSecond==250_000,"Download and upload counters use monotonic time and byte deltas");
        t=meter.sample(2_000_000,500_000,4_000_000_000L);check(t.downPerSecond==0&&t.upPerSecond==0,"Idle traffic speeds decay to zero");
        meter.beginEngine(5_000_000_000L);t=meter.sample(1000,500,6_000_000_000L);
        check(t.downloaded==2_001_000&&t.uploaded==500_500,"Engine replacement keeps totals and counts the new proxy traffic once");
        check(meter.stopped().downloaded==2_001_000&&meter.stopped().downPerSecond==0,"Disconnect retains totals and clears speeds");
        meter.newSession();check(meter.stopped().downloaded==0&&meter.stopped().uploaded==0,"New user connection starts a new session");
        check(TrafficMeter.bytes(2_000_000).equals("2.00 MB")&&Math.abs(DownloadSpeedTest.mbps(1_000_000,1_000_000_000L)-8)<.001,"Displayed volume and Mbps units are correct");
        AtomicLong bytes=new AtomicLong();AtomicReference<Double> rate=new AtomicReference<>();
        DownloadConnection c=new DownloadConnection(40_000_000,"valid");String error=runDownload(c,true,bytes,rate);
        check(error.isEmpty()&&bytes.get()==DownloadSpeedTest.MAX_BYTES&&c.read==DownloadSpeedTest.MAX_BYTES&&c.closed,"Download test stops exactly at its 20 MB budget and closes connection");
        check(rate.get()>0&&!c.getUseCaches()&&!c.getInstanceFollowRedirects()&&"identity".equals(c.getRequestProperty("Accept-Encoding")),"Test throughput is measured with cache, compression and redirects disabled");
        for(String mode:new String[]{"redirect","gzip"}){c=new DownloadConnection(1000,mode);check(!runDownload(c,true,bytes,rate).isEmpty()&&bytes.get()==0,"Invalid speed-test response is rejected: "+mode);}
        c=new DownloadConnection(1000,"valid");check(!runDownload(c,false,bytes,rate).isEmpty()&&c.read==0,"Speed test cannot download when VPN transport is unavailable");
        DownloadConnection stalled=new DownloadConnection(1000,"stall");DownloadSpeedTest test=new DownloadSpeedTest();CountDownLatch done=new CountDownLatch(1);AtomicReference<String> cancelledError=new AtomicReference<>();
        new Thread(()->test.run(new DownloadSpeedTest.Transport(){public HttpURLConnection open(URL u){return stalled;}public boolean valid(){return true;}},new DownloadSpeedTest.Listener(){public void progress(long b,double m){}public void finished(long b,double m,String e){cancelledError.set(e);done.countDown();}})).start();
        Thread.sleep(100);test.cancel();check(done.await(1,TimeUnit.SECONDS)&&cancelledError.get().equals("تست متوقف شد"),"Stopping a blocked download cancels promptly");
        System.out.println("RESULT: "+passed+" checks passed");
    }
}
