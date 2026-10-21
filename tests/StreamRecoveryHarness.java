import ir.persiansocks.app.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.*;

/** Real sockets exercise one-way activity, half-close, bounded idle and overload recovery. */
public final class StreamRecoveryHarness {
    static int checks;
    static synchronized void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static final class Upstream implements AutoCloseable {
        final ProxyEndpoint.Type type;
        final long duration;
        final ServerSocket listener=new ServerSocket(0,64,InetAddress.getLoopbackAddress());
        final ExecutorService workers=Executors.newCachedThreadPool(r->{Thread t=new Thread(r);t.setDaemon(true);return t;});
        final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
        final AtomicLong uploaded=new AtomicLong();
        Upstream(ProxyEndpoint.Type type,long duration)throws Exception{
            this.type=type;this.duration=duration;
            workers.execute(()->{while(!listener.isClosed())try{Socket s=listener.accept();sockets.add(s);workers.execute(()->serve(s));}catch(IOException e){break;}});
        }
        ProxyEndpoint endpoint(){return new ProxyEndpoint("127.0.0.1",listener.getLocalPort(),1,false).withType(type);}
        void serve(Socket raw){
            try(Socket owned=raw){
                Socket s=raw;s.setSoTimeout(5000);
                if(type==ProxyEndpoint.Type.HTTPS){SSLSocket tls=(SSLSocket)ScanHarness.tls.getSocketFactory().createSocket(raw,"127.0.0.1",listener.getLocalPort(),true);tls.setUseClientMode(false);tls.startHandshake();s=tls;}
                InputStream in=s.getInputStream();OutputStream out=s.getOutputStream();String target;
                if(type==ProxyEndpoint.Type.SOCKS5){
                    if(in.read()!=5)throw new IOException();int n=in.read();if(in.readNBytes(n).length!=n)throw new EOFException();out.write(new byte[]{5,0});out.flush();
                    byte[] head=in.readNBytes(5);if(head.length!=5||head[3]!=3)throw new IOException();target=new String(in.readNBytes(head[4]&255),StandardCharsets.US_ASCII);in.readNBytes(2);out.write(new byte[]{5,0,0,1,0,0,0,0,0,0});out.flush();
                }else{
                    ByteArrayOutputStream h=new ByteArrayOutputStream();int tail=0;
                    while(h.size()<16384){int b=in.read();if(b<0)throw new EOFException();h.write(b);tail=(tail<<8)|b;if(tail==0x0d0a0d0a)break;}
                    target=h.toString("US-ASCII").split(" ")[1];out.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));out.flush();
                }
                s.setSoTimeout(0);
                if(target.startsWith("download")){
                    long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(duration);byte[] block=new byte[256];Arrays.fill(block,(byte)42);
                    while(System.nanoTime()<end){out.write(block);out.flush();Thread.sleep(50);}
                }else if(target.startsWith("upload")){
                    byte[] b=new byte[4096];int n;long total=0;while((n=in.read(b))!=-1)total+=n;uploaded.addAndGet(total);
                    new DataOutputStream(out).writeLong(total);out.flush();
                }else{while(in.read()!=-1){}}
            }catch(Exception ignored){}finally{sockets.remove(raw);}
        }
        public void close()throws Exception{listener.close();for(Socket s:sockets)s.close();workers.shutdownNow();}
    }
    static final ProtectedSocksGateway.Protector PROTECTOR=new ProtectedSocksGateway.Protector(){
        public boolean tcp(Socket s){return true;}public boolean udp(DatagramSocket s){return true;}
        public InetAddress resolve(String h)throws Exception{return InetAddress.getByName(h);}
    };
    static Socket connect(ProtectedSocksGateway gate,String host)throws Exception{
        Socket s=new Socket("127.0.0.1",gate.port());s.setSoTimeout(3000);
        try{GatewayTestSupport.authenticate(s,gate);byte[] name=host.getBytes(StandardCharsets.US_ASCII);
            OutputStream out=s.getOutputStream();out.write(new byte[]{5,1,0,3,(byte)name.length});out.write(name);out.write(new byte[]{1,(byte)187});out.flush();
            byte[] reply=s.getInputStream().readNBytes(10);if(reply.length!=10||reply[1]!=0)throw new IOException("CONNECT failed");return s;
        }catch(Exception e){s.close();throw e;}
    }
    static void waitFree(ProtectedSocksGateway gate,int expected)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(gate.activeConnections()!=expected&&System.nanoTime()<end)Thread.sleep(10);
        check(gate.activeConnections()==expected,"session permit returned exactly once: "+expected);
    }
    static void streams(ProxyEndpoint.Type type,long duration,int idle)throws Exception{
        try(Upstream upstream=new Upstream(type,duration);ProtectedSocksGateway gate=new ProtectedSocksGateway(upstream.endpoint(),PROTECTOR,idle)){
            ExecutorService jobs=Executors.newFixedThreadPool(2);
            try{
                Future<Long> download=jobs.submit(()->{long start=System.nanoTime(),total=0;try(Socket s=connect(gate,"download.local")){byte[] b=new byte[4096];int n;while((n=s.getInputStream().read(b))!=-1){total+=n;for(int i=0;i<n;i++)if(b[i]!=42)throw new AssertionError("Payload corrupt");}}check(System.nanoTime()-start>=TimeUnit.MILLISECONDS.toNanos(duration-100),"silent upload direction preserves full download: "+type);check(total>=duration/50*128,"complete one-way download bytes: "+type);return total;});
                Future<Long> upload=jobs.submit(()->{long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(duration),total=0;try(Socket s=connect(gate,"upload.local")){byte[] b=new byte[256];while(System.nanoTime()<end){s.getOutputStream().write(b);s.getOutputStream().flush();total+=b.length;Thread.sleep(50);}s.shutdownOutput();long received=new DataInputStream(s.getInputStream()).readLong();check(received==total&&received>=duration/50*128,"silent download direction and half-close preserve upload/response: "+type);}return total;});
                long down=download.get(duration+6000,TimeUnit.MILLISECONDS),up=upload.get(6000,TimeUnit.MILLISECONDS);
                waitFree(gate,0);System.out.println("STREAM "+type+" duration="+duration+"ms idle="+idle+"ms down="+down+" up="+up);
            }finally{jobs.shutdownNow();}
        }
    }
    static void capacity()throws Exception{
        try(Upstream upstream=new Upstream(ProxyEndpoint.Type.HTTP,1000);ProtectedSocksGateway gate=new ProtectedSocksGateway(upstream.endpoint(),PROTECTOR)){
            ArrayList<Socket> clients=new ArrayList<>();
            try{
                for(int i=0;i<ProtectedSocksGateway.MAX_CONNECTIONS;i++){Socket s=new Socket("127.0.0.1",gate.port());s.setSoTimeout(2000);clients.add(s);GatewayTestSupport.authenticate(s,gate);}
                check(gate.nearCapacity()&&gate.activeConnections()==64,"64 bounded sessions accepted");
                try(Socket overflow=new Socket("127.0.0.1",gate.port())){overflow.setSoTimeout(2000);check(overflow.getInputStream().read()==-1,"65th session refused locally");}
                check(gate.capacityRejections()==1&&gate.lastError().startsWith("LOCAL_CAPACITY"),"overload recorded as local pressure");
                clients.remove(0).close();waitFree(gate,63);
                try(Socket fresh=new Socket("127.0.0.1",gate.port())){fresh.setSoTimeout(2000);GatewayTestSupport.authenticate(fresh,gate);check(gate.activeConnections()==64,"capacity recovers immediately after release");}
            }finally{for(Socket s:clients)s.close();}
            waitFree(gate,0);gate.close();gate.close();check(gate.activeConnections()==0,"repeated close cannot over-release");
        }
    }
    static void routes(){
        PhysicalRouteTracker<String> r=new PhysicalRouteTracker<>();
        r.update("wifi",true,"wlan0:ip1","dns1");r.update("mobile",true,"rmnet:ip2","dns2");r.activate("wifi");
        check(r.available()&&!r.activeBroken(),"captured physical route ready");r.lost("mobile");check(!r.activeBroken(),"unrelated route loss ignored");
        check(r.update("wifi",true,null,"dns3")&&!r.activeBroken(),"DNS-only change invalidates cache without breaking TCP");
        r.update("wifi",true,null,null);check(!r.activeBroken(),"capability update keeps active route");r.update("wifi",true,"wlan0:ip3",null);check(r.activeBroken(),"IP change requires new path");
        r.activate("wifi");r.lost("wifi");check(r.activeBroken()&&!r.available(),"lost active route enters waiting state");
        r.clearActive();r.update("mobile",true,null,null);check(!r.available(),"wait for link properties after capabilities");r.update("mobile",true,"rmnet:ip4","dns4");check(r.available(),"new route ready when callback snapshots complete");
        r.activate("mobile");r.lost("wifi");check(!r.activeBroken(),"late old loss cannot destroy replacement route");r.update("mobile",false,null,null);check(r.activeBroken(),"INTERNET removal breaks captured route");
        r.clearActive();check(!r.activeBroken(),"disconnect clears active recovery state");
        r.update("wifi",true,"wlan0\nip1",null);r.activate("wifi");r.update("wifi",true,"wlan0\nip1\nip2",null);check(!r.activeBroken(),"new privacy address does not kill retained source IP");
        r.update("wifi",true,"wlan0\nip2",null);check(r.activeBroken(),"removing captured source address requires reconnect");
        r.activate("wifi");r.update("wifi",true,"wlan1\nip2",null);check(r.activeBroken(),"interface replacement requires reconnect even with same IP");
    }
    public static void main(String[] args)throws Exception{
        ScanHarness.init(args[0]);
        if(args.length>1&&args[1].equals("--long")){streams(ProxyEndpoint.Type.HTTP,310_000,ProtectedSocksGateway.TCP_IDLE_MILLIS);}
        else{
            routes();for(ProxyEndpoint.Type type:new ProxyEndpoint.Type[]{ProxyEndpoint.Type.SOCKS5,ProxyEndpoint.Type.HTTP,ProxyEndpoint.Type.HTTPS})streams(type,1600,300);
            try(Upstream upstream=new Upstream(ProxyEndpoint.Type.HTTP,1000);ProtectedSocksGateway gate=new ProtectedSocksGateway(upstream.endpoint(),PROTECTOR,250);Socket s=connect(gate,"idle.local")){
                long begin=System.nanoTime();check(s.getInputStream().read()==-1,"both-idle flow closed by shared watchdog");long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-begin);check(elapsed>=180&&elapsed<2000,"idle cleanup bounded: "+elapsed);waitFree(gate,0);
            }
            try(Upstream upstream=new Upstream(ProxyEndpoint.Type.HTTP,1000);ProtectedSocksGateway gate=new ProtectedSocksGateway(upstream.endpoint(),PROTECTOR);Socket s=connect(gate,"idle.local")){
                gate.close();check(s.getInputStream().read()==-1,"explicit cancellation closes blocking reads");waitFree(gate,0);
            }
            capacity();
        }
        System.out.println("PASS: "+checks+" stream, idle, capacity and physical-route checks");
    }
}
