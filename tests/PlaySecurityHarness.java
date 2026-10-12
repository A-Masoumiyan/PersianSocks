import ir.persiansocks.app.*;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Real loopback TLS and a transparent wire recorder; no Android/TUN device is claimed. */
public final class PlaySecurityHarness {
    static int passed;
    static void check(boolean ok,String name){if(!ok)throw new AssertionError(name);passed++;System.out.println("PASS: "+name);}
    static final class EchoTlsProxy implements AutoCloseable {
        final ServerSocket listener=new ServerSocket(0,10,InetAddress.getLoopbackAddress());
        final ExecutorService pool=Executors.newCachedThreadPool(r->{Thread t=new Thread(r);t.setDaemon(true);return t;});
        final AtomicReference<String> request=new AtomicReference<>(""),payload=new AtomicReference<>(""),protocol=new AtomicReference<>("");
        final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
        EchoTlsProxy(String version)throws Exception {
            pool.submit(()->{while(!listener.isClosed())try{Socket raw=listener.accept();sockets.add(raw);pool.submit(()->{
                try(Socket owner=raw;SSLSocket tls=(SSLSocket)ScanHarness.tls.getSocketFactory().createSocket(raw,"127.0.0.1",listener.getLocalPort(),true)){
                    tls.setUseClientMode(false);tls.setEnabledProtocols(new String[]{version});tls.setSoTimeout(4000);tls.startHandshake();protocol.set(tls.getSession().getProtocol());
                    String h=header(tls.getInputStream());request.set(h);
                    tls.getOutputStream().write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));tls.getOutputStream().flush();
                    String body=header(tls.getInputStream());payload.set(body);
                    tls.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK".getBytes(StandardCharsets.US_ASCII));tls.getOutputStream().flush();
                }catch(Exception ignored){}finally{sockets.remove(raw);}
            });}catch(Exception ex){break;}});
        }
        public void close()throws Exception{listener.close();for(Socket s:sockets)s.close();pool.shutdownNow();}
    }
    static final class WireTap implements AutoCloseable {
        final ServerSocket listener=new ServerSocket(0,10,InetAddress.getLoopbackAddress());
        final ExecutorService pool=Executors.newCachedThreadPool(r->{Thread t=new Thread(r);t.setDaemon(true);return t;});
        final ByteArrayOutputStream outbound=new ByteArrayOutputStream(),inbound=new ByteArrayOutputStream();
        final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
        WireTap(int target)throws Exception{
            pool.submit(()->{while(!listener.isClosed())try{Socket client=listener.accept(),remote=new Socket("127.0.0.1",target);sockets.add(client);sockets.add(remote);
                pool.submit(()->copy(client,remote,outbound));pool.submit(()->copy(remote,client,inbound));
            }catch(Exception ex){break;}});
        }
        void copy(Socket from,Socket to,ByteArrayOutputStream recorded){try{
            byte[] block=new byte[2048];int n;while((n=from.getInputStream().read(block))!=-1){synchronized(recorded){recorded.write(block,0,n);}to.getOutputStream().write(block,0,n);to.getOutputStream().flush();}
            try{to.shutdownOutput();}catch(Exception ignored){}
        }catch(Exception ignored){} }
        byte[] bytes(ByteArrayOutputStream recorded){synchronized(recorded){return recorded.toByteArray();}}
        ProxyEndpoint endpoint(){return new ProxyEndpoint("127.0.0.1",listener.getLocalPort(),-1,false).withType(ProxyEndpoint.Type.HTTPS);}
        public void close()throws Exception{listener.close();for(Socket s:sockets)s.close();pool.shutdownNow();}
    }
    static String header(InputStream in)throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();int tail=0;
        while(bytes.size()<16384){int c=in.read();if(c<0)throw new EOFException();bytes.write(c);tail=(tail<<8)|c;if(tail==0x0d0a0d0a)return bytes.toString("US-ASCII");}
        throw new IOException("Header too long");
    }
    static void nativeConnect(Socket client,int gateway,String host,int port)throws Exception {
        client.connect(new InetSocketAddress("127.0.0.1",gateway),3000);client.setSoTimeout(5000);
        OutputStream out=client.getOutputStream();InputStream in=client.getInputStream();out.write(new byte[]{5,1,0});out.flush();
        if(in.read()!=5||in.read()!=0)throw new IOException("Local negotiation rejected");
        byte[] name=host.getBytes(StandardCharsets.US_ASCII);out.write(new byte[]{5,1,0,3,(byte)name.length});out.write(name);out.write(new byte[]{(byte)(port>>8),(byte)port});out.flush();
        byte[] reply=in.readNBytes(10);if(reply.length!=10||reply[1]!=0)throw new IOException("Local connect rejected");
    }
    static ProtectedSocksGateway.Protector protector(AtomicInteger calls){return new ProtectedSocksGateway.Protector(){
        public boolean tcp(Socket socket){if(!socket.isBound())throw new AssertionError("Protection needs a socket FD");calls.incrementAndGet();return true;}
        public boolean udp(DatagramSocket socket){throw new AssertionError("Play transport cannot use raw UDP");}
        public InetAddress resolve(String host)throws Exception{return InetAddress.getByName(host);}
    };}
    public static void main(String[] args)throws Exception {
        check(DistributionPolicy.play(),"Harness uses the immutable Play build flag");
        ScanHarness.init(args[0]);ServiceTargets.configure(Collections.emptyList());
        ProxyEndpoint plain=ProxyEndpoint.parse("8.8.8.8:1080"),http=ProxyEndpoint.parse("http://8.8.8.8:8080"),secure=ProxyEndpoint.parse("https://proxy.example.org:443");
        AtomicInteger sockets=new AtomicInteger();Socks5Probe.setSocketMaker(()->{sockets.incrementAndGet();return new Socket();});
        for(ProxyEndpoint endpoint:Arrays.asList(plain,http)){
            Socks5Probe.Result rejected=Socks5Probe.inspect(endpoint,true,new AtomicBoolean(false),null);
            check(rejected.endpoint==null&&rejected.failure==Socks5Probe.Failure.INSECURE_PROXY&&sockets.get()==0,"No physical socket is created for insecure candidate: "+endpoint.type);
            try(Socket raw=new Socket()){
                try{ProxyTransport.connect(raw,endpoint,"www.youtube.com",443,1000);throw new AssertionError("Accepted insecure transport");}
                catch(IOException expected){check(!raw.isConnected(),"Direct transport policy cannot be bypassed: "+endpoint.type);}
            }
            try{new ProtectedSocksGateway(endpoint,protector(new AtomicInteger()));throw new AssertionError("Accepted insecure gateway");}
            catch(IOException expected){check(true,"Native gateway rejects insecure upstream before listening: "+endpoint.type);}
        }
        Socks5Probe.setSocketMaker(null);
        for(int stale:new int[]{0,1,2,3,6,7})check(new ProxyScanner.Options(100,false,false,stale).types==4,"Stored scan choice cannot enable plaintext: "+stale);
        List<ProxyEndpoint> cached=new ArrayList<>();for(ProxyEndpoint p:Arrays.asList(plain,http,secure))cached.add(new ProxyEndpoint(p.host,p.port,10,false,7,7,1).withType(p.type));
        check(ProxyResults.visible(cached,"FULL",ProxyResults.Sort.FASTEST).size()==1&&ProxyResults.visible(cached,"FULL",ProxyResults.Sort.FASTEST).get(0).type==ProxyEndpoint.Type.HTTPS,"Legacy plaintext results cannot become Play connection candidates");
        FailoverPolicy policy=new FailoverPolicy();policy.reset(cached.get(2).key());policy.failed(cached.get(2).key(),0);
        check(policy.next(cached,cached.get(2).key(),"ALL",ProxyResults.Sort.FASTEST,1)==null,"Failed TLS proxy cannot fall back to cached HTTP/SOCKS");
        AutoScanSelection auto=new AutoScanSelection("FULL",ProxyResults.Sort.FASTEST);for(ProxyEndpoint p:cached)auto.add(p);
        check(auto.best().type==ProxyEndpoint.Type.HTTPS,"Widget auto-connect respects the encryption gate");
        try(SSLSocket socket=(SSLSocket)ScanHarness.tls.getSocketFactory().createSocket()){
            DistributionPolicy.configureTls(socket);
            check(Arrays.stream(socket.getEnabledProtocols()).allMatch(v->v.equals("TLSv1.2")||v.equals("TLSv1.3"))&&socket.getEnabledProtocols().length>0,"Only TLS 1.2/1.3 are enabled");
            check("HTTPS".equals(socket.getSSLParameters().getEndpointIdentificationAlgorithm()),"Certificate hostname checking is enabled");
        }
        for(String version:new String[]{"TLSv1.2","TLSv1.3"})try(EchoTlsProxy server=new EchoTlsProxy(version);WireTap tap=new WireTap(server.listener.getLocalPort());ProtectedSocksGateway gateway=new ProtectedSocksGateway(tap.endpoint(),protector(new AtomicInteger()));Socket client=new Socket()){
            nativeConnect(client,gateway.port(),"example.com",80);
            String marker="SECRET-"+UUID.randomUUID();String request="GET /"+marker+" HTTP/1.1\r\nHost: example.com\r\n\r\n";
            client.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));client.getOutputStream().flush();
            check(header(client.getInputStream()).startsWith("HTTP/1.1 200")&&client.getInputStream().readNBytes(2).length==2,"Plain HTTP destination is carried successfully inside "+version);
            check(server.request.get().startsWith("CONNECT example.com:80")&&server.payload.get().contains(marker),"TLS endpoint decrypts and receives the intended application payload: "+version);
            byte[] wire=tap.bytes(tap.outbound);String recorded=new String(wire,StandardCharsets.ISO_8859_1);
            check(wire.length>5&&wire[0]==22&&!recorded.contains("CONNECT example.com")&&!recorded.contains(marker)&&!recorded.contains("GET /"),"Physical wire contains TLS records, not plaintext CONNECT or HTTP payload: "+version);
            check(server.protocol.get().equals(version),"Expected TLS version was negotiated: "+version);
        }
        try(ProtocolTrafficHarness.HttpProxy server=new ProtocolTrafficHarness.HttpProxy(true,"valid")){
            ProxyEndpoint e=Socks5Probe.check(server.endpoint());check(e!=null&&e.full()&&e.type==ProxyEndpoint.Type.HTTPS,"TLS proxy passes all three service tests in Play mode");
            try(ProtectedSocksGateway gateway=new ProtectedSocksGateway(server.endpoint(),protector(new AtomicInteger()))){
                TunnelVerifier.Result result=TunnelVerifier.check(new TunnelVerifier.Transport(){
                    public Socket create(){return new Socket(){public void connect(SocketAddress endpoint,int timeout)throws IOException{
                        try{InetSocketAddress address=(InetSocketAddress)endpoint;int index=(address.getAddress().getAddress()[3]&255)-1;
                            super.connect(new InetSocketAddress("127.0.0.1",gateway.port()),timeout);setSoTimeout(5000);
                            OutputStream out=getOutputStream();InputStream in=getInputStream();out.write(new byte[]{5,1,0});out.flush();if(in.read()!=5||in.read()!=0)throw new IOException();
                            byte[] host=ServiceTargets.ALL[index].host.getBytes(StandardCharsets.US_ASCII);out.write(new byte[]{5,1,0,3,(byte)host.length});out.write(host);out.write(new byte[]{1,(byte)0xbb});out.flush();
                            byte[] reply=in.readNBytes(10);if(reply.length!=10||reply[1]!=0)throw new IOException();
                        }catch(Exception ex){throw new IOException(ex);}
                    }};}
                    public InetAddress[] resolve(String host)throws Exception{for(int i=0;i<3;i++)if(ServiceTargets.ALL[i].host.equals(host))return new InetAddress[]{InetAddress.getByName("203.0.113."+(i+1))};throw new UnknownHostException();}
                },new AtomicBoolean(false),ConcurrentHashMap.newKeySet());
                check(result.accepts("FULL")&&result.profile(e).type==ProxyEndpoint.Type.HTTPS,"VPN-path service verification preserves TLS type and accepts complete service results");
                try(Socket client=new Socket("127.0.0.1",gateway.port())){
                    client.setSoTimeout(2000);client.getOutputStream().write(new byte[]{5,1,0});client.getInputStream().readNBytes(2);
                    client.getOutputStream().write(new byte[]{5,3,0,1,0,0,0,0,0,0});byte[] reply=client.getInputStream().readNBytes(10);
                    check(reply.length==10&&reply[1]==7,"Raw UDP cannot leave the Play gateway");
                }
            }
            SSLContext system=SSLContext.getInstance("TLS");system.init(null,null,null);SSLContext.setDefault(system);
            check(Socks5Probe.check(server.endpoint())==null,"Untrusted server certificate stops the Play connection");SSLContext.setDefault(ScanHarness.tls);
            Socks5Probe.setResolver(host->InetAddress.getLoopbackAddress());
            check(Socks5Probe.check(ProxyEndpoint.parse("https://wrong.example.org:"+server.listener.getLocalPort()))==null,"Wrong TLS certificate hostname stops the Play connection");Socks5Probe.setResolver(null);
        }
        try(ProtocolTrafficHarness.HttpProxy server=new ProtocolTrafficHarness.HttpProxy(false,"valid")){
            check(Socks5Probe.check(server.endpoint().withType(ProxyEndpoint.Type.HTTPS))==null,"Plain HTTP server relabeled as HTTPS cannot pass the encryption test");
        }
        try(ProtocolTrafficHarness.HttpProxy tls=new ProtocolTrafficHarness.HttpProxy(true,"valid");ProtocolTrafficHarness.HttpProxy httpServer=new ProtocolTrafficHarness.HttpProxy(false,"valid")){
            ProxyScanner scanner=new ProxyScanner();CountDownLatch done=new CountDownLatch(1);AtomicReference<List<ProxyEndpoint>> found=new AtomicReference<>();
            scanner.start(Arrays.asList(tls.endpoint(),httpServer.endpoint()),Collections.emptyList(),Collections.emptyList(),new ProxyScanner.Options(100,false,false,1),new ProxyScanner.Listener(){
                public void onFound(ProxyEndpoint p){}public void onProgress(int d,int t,int c){}public void onFinished(List<ProxyEndpoint> p,String message){found.set(p);done.countDown();}
            });
            check(done.await(8,TimeUnit.SECONDS)&&found.get().size()==1&&found.get().get(0).type==ProxyEndpoint.Type.HTTPS&&httpServer.connects.get()==0,"Actual Play scan ignores stale SOCKS preference and never dials plaintext candidate");scanner.stop();
        }
        System.out.println("RESULT: "+passed+" checks passed");
    }
}
