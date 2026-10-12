package ir.persiansocks.app;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.*;

/** Negotiates a TCP tunnel on an already connected, protected physical socket. */
public final class ProxyTransport {
    private ProxyTransport() {}
    public static final class Rejected extends IOException {
        public final boolean auth, protocol;
        Rejected(String message, boolean auth, boolean protocol) { super(message);this.auth=auth;this.protocol=protocol; }
    }
    public static Socket connect(Socket raw, ProxyEndpoint proxy, String host, int port, int timeout) throws IOException {
        DistributionPolicy.require(proxy);
        if(port<1||port>65535||host.isEmpty()||host.length()>255||!host.matches("[a-zA-Z0-9._:-]+"))
            throw new IOException("Invalid tunnel destination");
        raw.setSoTimeout(timeout);
        if(proxy.type==ProxyEndpoint.Type.SOCKS5) {
            InputStream in=raw.getInputStream();OutputStream out=raw.getOutputStream();
            out.write(new byte[]{5,1,0});out.flush();
            if(in.read()!=5)throw new Rejected("Invalid SOCKS protocol",false,true);
            if(in.read()!=0)throw new Rejected("SOCKS authentication required",true,false);
            byte[] name=host.getBytes(StandardCharsets.US_ASCII);
            out.write(new byte[]{5,1,0,3,(byte)name.length});out.write(name);
            out.write(new byte[]{(byte)(port>>8),(byte)port});out.flush();
            int v=in.read(),reply=in.read(),rsv=in.read(),type=in.read();
            if(v!=5||rsv!=0)throw new Rejected("Invalid SOCKS reply",false,true);
            if(reply!=0)throw new Rejected("Destination rejected",false,false);
            int n=type==1?4:type==4?16:type==3?in.read():-1;
            if(n<1)throw new Rejected("Invalid SOCKS address",false,true);
            for(int i=0;i<n+2;i++)if(in.read()<0)throw new EOFException();
            return raw;
        }
        Socket tunnel=raw;
        try {
            if(proxy.type==ProxyEndpoint.Type.HTTPS) {
                SSLSocket tls=(SSLSocket)((SSLSocketFactory)SSLSocketFactory.getDefault()).createSocket(raw,proxy.host,proxy.port,true);
                tunnel=tls;
                DistributionPolicy.configureTls(tls);tls.setSoTimeout(timeout);tls.startHandshake();
            }
            String authority=(host.contains(":")?"["+host+"]":host)+":"+port;
            OutputStream out=tunnel.getOutputStream();
            out.write(("CONNECT "+authority+" HTTP/1.1\r\nHost: "+authority+
                "\r\nProxy-Connection: Keep-Alive\r\nUser-Agent: PersianSocks/0.10.0\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.flush();
            InputStream in=tunnel.getInputStream();ByteArrayOutputStream headers=new ByteArrayOutputStream();
            int tail=0;
            while(headers.size()<16384) {
                int c=in.read();if(c<0)throw new EOFException("Incomplete CONNECT response");
                headers.write(c);tail=(tail<<8)|c;
                if(tail==0x0d0a0d0a)break;
            }
            if(tail!=0x0d0a0d0a)throw new Rejected("CONNECT headers too long",false,true);
            String first=new String(headers.toByteArray(),StandardCharsets.US_ASCII).split("\r\n",2)[0];
            if(!first.matches("HTTP/1\\.[01] [0-9]{3}( .*)?"))throw new Rejected("Invalid CONNECT response",false,true);
            int status=Integer.parseInt(first.substring(9,12));
            if(status<200||status>=300)throw new Rejected("HTTP CONNECT "+status,status==407,false);
            return tunnel;
        }catch(IOException|RuntimeException ex){if(tunnel!=raw)try{tunnel.close();}catch(IOException ignored){}throw ex;}
    }
}
