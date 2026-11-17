package ir.pathfinder.b;

import android.net.Network;
import android.net.VpnService;
import java.io.*;
import java.net.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Loopback-only authenticated SOCKS5 gateway. Direct-mode test; NOT censorship circumvention. */
final class LocalDirectGateway implements Closeable {
    private final VpnService vpn;
    private final Network physical;
    private final ServerSocket listener;
    private final ExecutorService workers=Executors.newFixedThreadPool(60);
    private final AtomicBoolean closed=new AtomicBoolean();
    private final Semaphore capacity=new Semaphore(24);
    final String user,password;
    LocalDirectGateway(VpnService vpn,Network physical) throws IOException {
        this.vpn=vpn;this.physical=physical;
        byte[] random=new byte[20];new SecureRandom().nextBytes(random);
        StringBuilder b=new StringBuilder();
        for(byte n:random)b.append(String.format(Locale.ROOT,"%02x",n&255));
        user="pathfinder";password=b.toString();
        listener=new ServerSocket();
        listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),24);
        Thread t=new Thread(this::accept,"pathfinder-local-socks");
        t.setDaemon(true);t.start();
    }
    int port(){return listener.getLocalPort();}
    private void accept(){
        while(!closed.get())try {
            Socket peer=listener.accept();
            if(!capacity.tryAcquire()){peer.close();continue;}
            try {workers.submit(()->{
                try{handle(peer);}finally {capacity.release();try{peer.close();}catch(Exception ignored){}}
            });}catch(RejectedExecutionException e){capacity.release();peer.close();}
        }catch(IOException e){if(closed.get())break;}
    }
    private static byte[] read(InputStream s,int length)throws IOException{
        byte[] r=new byte[length];int pos=0;
        while(pos<length){int got=s.read(r,pos,length-pos);if(got<0)throw new EOFException();pos+=got;}
        return r;
    }
    private void handle(Socket client){
        Socket remote=null;
        try {
            client.setSoTimeout(45000);
            InputStream in=client.getInputStream();OutputStream out=client.getOutputStream();
            if(in.read()!=5)return;
            int methodCount=in.read();if(methodCount<=0||methodCount>10)return;
            byte[] methods=read(in,methodCount);
            boolean auth=false;for(byte m:methods)if(m==2)auth=true;
            out.write(new byte[]{5,(byte)(auth?2:255)});out.flush();if(!auth)return;
            if(in.read()!=1)return;
            int ulen=in.read();if(ulen<0)return;
            String suppliedUser=new String(read(in,ulen),"UTF-8");
            int plen=in.read();if(plen<0)return;
            String suppliedPass=new String(read(in,plen),"UTF-8");
            boolean valid=user.equals(suppliedUser)&&password.equals(suppliedPass);
            out.write(new byte[]{1,(byte)(valid?0:1)});out.flush();if(!valid)return;
            byte[] header=read(in,4);
            if(header[0]!=5 || header[1]!=1 || header[2]!=0)return; // TCP CONNECT only
            String host;
            int atyp=header[3]&255;
            if(atyp==1){byte[] a=read(in,4);host=InetAddress.getByAddress(a).getHostAddress();}
            else if(atyp==4){byte[] a=read(in,16);host=InetAddress.getByAddress(a).getHostAddress();}
            else if(atyp==3){
                int size=in.read();if(size<1||size>253)return;
                host=new String(read(in,size),"US-ASCII");
            }else return;
            byte[] port=read(in,2);
            int destination=((port[0]&255)<<8)|(port[1]&255);
            if(destination<1)return;
            InetAddress address=null;
            InetAddress[] addresses=physical==null?InetAddress.getAllByName(host):physical.getAllByName(host);
            if(addresses.length==0)return;address=addresses[0];
            remote=new Socket();
            if(!vpn.protect(remote))throw new IOException("Cannot protect underlying socket");
            if(physical!=null)physical.bindSocket(remote);
            remote.connect(new InetSocketAddress(address,destination),6500);
            remote.setSoTimeout(90000);
            out.write(new byte[]{5,0,0,1,0,0,0,0,0,0});out.flush();
            final Socket up=remote;
            Future<?> upstream=workers.submit(()->pump(client,up));
            pump(up,client);
            upstream.cancel(true);
        }catch(Exception ignored){
        }finally {
            if(remote!=null)try{remote.close();}catch(Exception ignored){}
        }
    }
    private static void pump(Socket from,Socket to){
        try{
            InputStream in=from.getInputStream();OutputStream out=to.getOutputStream();
            byte[] buf=new byte[16*1024];int n;
            while((n=in.read(buf))>=0){out.write(buf,0,n);out.flush();}
        }catch(Exception ignored){}
        try{to.shutdownOutput();}catch(Exception ignored){}
    }
    @Override public void close(){
        if(!closed.compareAndSet(false,true))return;
        try{listener.close();}catch(Exception ignored){}
        workers.shutdownNow();
    }
}