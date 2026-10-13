import ir.persiansocks.app.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** Native RFC1929 handshake and a test-only adapter for the public proxy probe. */
public final class GatewayTestSupport {
    static void authenticate(Socket client,ProtectedSocksGateway gateway)throws IOException {
        authenticate(client,gateway.username(),gateway.password());
    }
    static void authenticate(Socket client,String username,String password)throws IOException {
        OutputStream out=client.getOutputStream();InputStream in=client.getInputStream();
        out.write(new byte[]{5,1,2});out.flush();
        if(in.read()!=5||in.read()!=2)throw new IOException("Local auth method rejected");
        byte[] user=username.getBytes(StandardCharsets.US_ASCII),pass=password.getBytes(StandardCharsets.US_ASCII);
        out.write(1);out.write(user.length);out.write(user);out.write(pass.length);out.write(pass);out.flush();
        if(in.read()!=1||in.read()!=0)throw new IOException("Local credentials rejected");
    }
    static ProxyEndpoint check(ProtectedSocksGateway gateway)throws Exception {
        ExecutorService pool=Executors.newCachedThreadPool(r->{Thread t=new Thread(r);t.setDaemon(true);return t;});
        try(ServerSocket listener=new ServerSocket(0,16,InetAddress.getByName("127.0.0.1"))) {
            pool.submit(()->{while(!listener.isClosed())try{
                Socket client=listener.accept();pool.submit(()->{
                    try(Socket owned=client;Socket upstream=new Socket("127.0.0.1",gateway.port())) {
                        client.setSoTimeout(5000);upstream.setSoTimeout(5000);
                        InputStream in=client.getInputStream();OutputStream out=client.getOutputStream();
                        if(in.read()!=5)return;int n=in.read();if(n<1)return;
                        if(in.readNBytes(n).length!=n)return;
                        authenticate(upstream,gateway);out.write(new byte[]{5,0});out.flush();
                        Future<?> reverse=pool.submit(()->copy(upstream,client));
                        copy(client,upstream);reverse.get(6,TimeUnit.SECONDS);
                    }catch(Exception ignored){}
                });
            }catch(IOException ex){break;}});
            return Socks5Probe.check(new ProxyEndpoint("127.0.0.1",listener.getLocalPort(),-1,false));
        } finally {pool.shutdownNow();}
    }
    private static void copy(Socket from,Socket to) {
        try {from.getInputStream().transferTo(to.getOutputStream());to.shutdownOutput();}catch(IOException ignored){}
    }
}
