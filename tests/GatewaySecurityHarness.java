import ir.persiansocks.app.*;
import java.io.*;
import java.net.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class GatewaySecurityHarness {
    static int checks;
    static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    static Socket client(ProtectedSocksGateway gateway)throws Exception {
        Socket client=new Socket("127.0.0.1",gateway.port());client.setSoTimeout(2000);return client;
    }
    public static void main(String[]args)throws Exception {
        AtomicInteger protectedSockets=new AtomicInteger();
        ProtectedSocksGateway.Protector protector=new ProtectedSocksGateway.Protector(){
            public boolean tcp(Socket s){protectedSockets.incrementAndGet();return false;}
            public boolean udp(DatagramSocket s){throw new AssertionError();}
            public InetAddress resolve(String h)throws Exception{throw new AssertionError();}
        };
        ProxyEndpoint proxy=ProxyEndpoint.parse("https://example.com:443");
        try(ProtectedSocksGateway first=new ProtectedSocksGateway(proxy,protector);
            ProtectedSocksGateway next=new ProtectedSocksGateway(proxy,protector)) {
            check(first.password().length()==64&&first.username().length()==64,"256-bit session credentials");
            check(!first.password().equals(next.password()),"credentials rotate on reconnect");
            try(Socket s=client(first)) {
                s.getOutputStream().write(new byte[]{5,1,0});
                check(s.getInputStream().read()==5&&s.getInputStream().read()==255,"anonymous local app refused");
                check(s.getInputStream().read()==-1,"anonymous socket closed");
            }
            for(String password:new String[]{"wrong",next.password()})try(Socket s=client(first)) {
                boolean rejected=false;
                try{GatewayTestSupport.authenticate(s,first.username(),password);}catch(IOException expected){rejected=true;}
                check(rejected,"wrong or other-session credential refused");
                check(s.getInputStream().read()==-1,"bad credential socket closed");
            }
            try(Socket s=client(first)) {
                GatewayTestSupport.authenticate(s,first);
                s.getOutputStream().write(new byte[]{5,2,0,1,0,0,0,0,0,0});
                byte[] reply=s.getInputStream().readNBytes(10);
                check(reply.length==10&&reply[1]==7,"authenticated unsupported command refused");
            }
            check(protectedSockets.get()==0,"rejected clients create no physical upstream sockets");
        }
        System.out.println("GatewaySecurityHarness: "+checks+" checks passed");
    }
}
