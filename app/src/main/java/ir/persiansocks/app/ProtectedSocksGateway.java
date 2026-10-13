package ir.persiansocks.app;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Loopback SOCKS gateway. Every physical upstream socket is protected from the VPN. */
public final class ProtectedSocksGateway implements AutoCloseable {
    public interface Protector {
        boolean tcp(Socket socket);
        boolean udp(DatagramSocket socket);
        InetAddress resolve(String host) throws Exception;
    }
    private final ProxyEndpoint proxy;
    private final Protector protector;
    private final ServerSocket listener;
    private final String username = token(), password = token();
    private static String token() {
        byte[] bytes = new byte[32];new java.security.SecureRandom().nextBytes(bytes);
        StringBuilder value = new StringBuilder(64);
        for(byte b:bytes)value.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        return value.toString();
    }
    public String username(){return username;}
    public String password(){return password;}
    private volatile String lastError="";
    public String lastError(){return lastError;}
    private final AtomicBoolean stopped=new AtomicBoolean(false);
    private final Semaphore slots=new Semaphore(64);
    private final Set<Connection> connections=ConcurrentHashMap.newKeySet();
    private final ExecutorService pool=new ThreadPoolExecutor(0,200,30,TimeUnit.SECONDS,
        new SynchronousQueue<>(),r->{Thread t=new Thread(null,r,"protected-socks-relay",256*1024);t.setDaemon(true);return t;});
    private final Thread acceptThread;

    public ProtectedSocksGateway(ProxyEndpoint proxy,Protector protector)throws IOException {
        DistributionPolicy.require(proxy);
        this.proxy=proxy;this.protector=protector;
        listener=new ServerSocket(0,64,InetAddress.getByName("127.0.0.1"));
        acceptThread=new Thread(()-> {
            while(!stopped.get()) try {
                Socket s=listener.accept();
                if(!slots.tryAcquire()) { closeSocket(s); continue; }
                Connection c=new Connection(s); connections.add(c);
                try { pool.execute(()->handle(c)); }catch(RejectedExecutionException ex){c.close();}
            }catch(IOException ex){if(stopped.get())break;}
        },"protected-socks-accept");
        acceptThread.setDaemon(true);acceptThread.start();
    }
    public int port(){return listener.getLocalPort();}
    private final class Connection implements AutoCloseable {
        final Socket client;
        final Set<Socket> tcp=ConcurrentHashMap.newKeySet();
        final Set<DatagramSocket> udp=ConcurrentHashMap.newKeySet();
        final AtomicBoolean ended=new AtomicBoolean(false);
        Connection(Socket s){client=s;tcp.add(s);}
        void add(Socket s){tcp.add(s);if(ended.get())closeSocket(s);}
        void add(DatagramSocket s){udp.add(s);if(ended.get())s.close();}
        public void close(){
            if(!ended.compareAndSet(false,true))return;
            for(Socket s:tcp)closeSocket(s);
            for(DatagramSocket s:udp)s.close();
            connections.remove(this);slots.release();
        }
    }
    private void handle(Connection c) {
        boolean handedOff=false;
        try {
            Socket client=c.client;client.setSoTimeout(10000);client.setTcpNoDelay(true);
            InputStream ci=client.getInputStream();OutputStream co=client.getOutputStream();
            if(ci.read()!=5)return;
            int n=ci.read();if(n<1||n>255)return;
            byte[]methods=read(ci,n);boolean authenticated=false;
            for(byte b:methods)if(b==2)authenticated=true;
            if(!authenticated){co.write(new byte[]{5,(byte)255});co.flush();return;}
            co.write(new byte[]{5,2});co.flush();
            if(ci.read()!=1)return;
            int userLength=ci.read();if(userLength<1)return;
            byte[] user=read(ci,userLength);
            int passLength=ci.read();if(passLength<1)return;
            byte[] pass=read(ci,passLength);
            boolean valid=java.security.MessageDigest.isEqual(user,username.getBytes(java.nio.charset.StandardCharsets.US_ASCII)) &
                java.security.MessageDigest.isEqual(pass,password.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            co.write(new byte[]{1,(byte)(valid?0:1)});co.flush();
            if(!valid)return;
            Packet request=packet(ci);
            if(request.code!=1&&request.code!=3 || request.code==3&&proxy.type!=ProxyEndpoint.Type.SOCKS5){
                co.write(new byte[]{5,7,0,1,0,0,0,0,0,0});co.flush();return;
            }
            Socket raw=new Socket();c.add(raw);
            raw.bind(new InetSocketAddress(0));
            if(c.ended.get()||stopped.get()||!protector.tcp(raw))throw new IOException("Physical TCP protection failed");
            raw.connect(new InetSocketAddress(protector.resolve(proxy.host),proxy.port),10000);
            raw.setSoTimeout(10000);raw.setTcpNoDelay(true);
            final Socket remote;
            if(request.code==3){
                InputStream ri=raw.getInputStream();OutputStream ro=raw.getOutputStream();
                ro.write(new byte[]{5,1,0});ro.flush();
                if(ri.read()!=5||ri.read()!=0)throw new IOException("SOCKS negotiation failed");
                ro.write(new byte[]{5,3,0,1,0,0,0,0,0,0});ro.flush();
                Packet reply=packet(ri);
                if(reply.code!=0){co.write(reply.raw);co.flush();return;}
                relayUdp(c,raw,reply,co);return;
            }
            String destination=request.type==3?request.domain:request.address().getHostAddress();
            remote=ProxyTransport.connect(raw,proxy,destination,request.port,10000);c.add(remote);
            co.write(new byte[]{5,0,0,1,0,0,0,0,0,0});co.flush();
            client.setSoTimeout(180000);remote.setSoTimeout(180000);
            AtomicInteger remaining=new AtomicInteger(2);
            pool.execute(()->pump(remote,client,c,remaining));
            handedOff=true;pump(client,remote,c,remaining);
        }catch(Exception error){lastError=error.getClass().getSimpleName()+": "+String.valueOf(error.getMessage());}
        finally{if(!handedOff)c.close();}
    }
    private static void pump(Socket from,Socket to,Connection c,AtomicInteger remaining) {
        try {
            InputStream in=from.getInputStream();OutputStream out=to.getOutputStream();
            byte[]buffer=new byte[8192];int n;
            while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
            try{to.shutdownOutput();}catch(Exception ignored){}
        }catch(Exception ex){c.close();}
        finally{if(remaining.decrementAndGet()==0)c.close();}
    }
    private void relayUdp(Connection c,Socket control,Packet reply,OutputStream clientReply)throws Exception {
        InetAddress address=reply.address();
        if(address==null)address=protector.resolve(reply.domain);
        if(address.isAnyLocalAddress()||address.isLoopbackAddress())address=InetAddress.getByName(proxy.host);
        if(reply.port<1)throw new IOException("Invalid UDP relay port");
        DatagramSocket local=new DatagramSocket(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0));c.add(local);
        DatagramSocket physical=new DatagramSocket(null);c.add(physical);
        physical.bind(new InetSocketAddress(0));
        if(c.ended.get()||stopped.get()||!protector.udp(physical))throw new IOException("Physical UDP protection failed");
        physical.connect(address,reply.port);physical.setSoTimeout(1500);local.setSoTimeout(1500);
        int port=local.getLocalPort();
        clientReply.write(new byte[]{5,0,0,1,127,0,0,1,(byte)(port>>8),(byte)port});clientReply.flush();
        AtomicReference<SocketAddress> peer=new AtomicReference<>();
        AtomicLong activity=new AtomicLong(System.nanoTime());
        pool.execute(()-> {
            byte[]bytes=new byte[65535];
            while(!c.ended.get())try {
                DatagramPacket p=new DatagramPacket(bytes,bytes.length);local.receive(p);
                if(!p.getAddress().isLoopbackAddress())continue;
                peer.compareAndSet(null,p.getSocketAddress());
                if(!p.getSocketAddress().equals(peer.get()))continue;
                activity.set(System.nanoTime());physical.send(new DatagramPacket(p.getData(),p.getLength()));
            }catch(SocketTimeoutException ex){if(System.nanoTime()-activity.get()>TimeUnit.SECONDS.toNanos(90))c.close();}
            catch(Exception ex){c.close();}
        });
        pool.execute(()-> {
            byte[]bytes=new byte[65535];
            while(!c.ended.get())try {
                DatagramPacket p=new DatagramPacket(bytes,bytes.length);physical.receive(p);
                SocketAddress recipient=peer.get();if(recipient==null)continue;
                activity.set(System.nanoTime());local.send(new DatagramPacket(p.getData(),p.getLength(),recipient));
            }catch(SocketTimeoutException ex){if(System.nanoTime()-activity.get()>TimeUnit.SECONDS.toNanos(90))c.close();}
            catch(Exception ex){c.close();}
        });
        c.client.setSoTimeout(0);
        // The SOCKS association exists only while the native client's control connection exists.
        while(c.client.getInputStream().read()!=-1){}
    }
    private static final class Packet {
        final byte[]raw,host;
        final int code,type,port;
        final String domain;
        Packet(byte[]raw,int code,int type,byte[]host,int port){
            this.raw=raw;this.code=code;this.type=type;this.host=host;this.port=port;
            domain=type==3?new String(host,java.nio.charset.StandardCharsets.US_ASCII):"";
        }
        InetAddress address()throws Exception{return type==1||type==4?InetAddress.getByAddress(host):null;}
    }
    private static Packet packet(InputStream in)throws Exception {
        int ver=in.read(),code=in.read(),rsv=in.read(),type=in.read();
        if(ver!=5||code<0||rsv!=0)throw new IOException("Invalid SOCKS reply");
        int len=type==1?4:type==4?16:type==3?in.read():-1;
        if(len<1||len>255)throw new IOException("Invalid SOCKS address");
        byte[]host=read(in,len),port=read(in,2);
        ByteArrayOutputStream raw=new ByteArrayOutputStream();
        raw.write(new byte[]{5,(byte)code,0,(byte)type});if(type==3)raw.write(len);
        raw.write(host);raw.write(port);
        return new Packet(raw.toByteArray(),code,type,host,((port[0]&255)<<8)|(port[1]&255));
    }
    private static byte[]read(InputStream in,int n)throws Exception {
        byte[]out=new byte[n];int pos=0,count;
        while(pos<n&&(count=in.read(out,pos,n-pos))!=-1)pos+=count;
        if(pos!=n)throw new EOFException();return out;
    }
    private static void closeSocket(Socket s){try{s.close();}catch(Exception ignored){}}
    public void close(){
        if(!stopped.compareAndSet(false,true))return;
        try{listener.close();}catch(Exception ignored){}
        for(Connection c:connections)c.close();pool.shutdownNow();acceptThread.interrupt();
    }
}
