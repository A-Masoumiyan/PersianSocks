package ir.persiansocks.app;
import java.net.*;import java.io.*;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
public final class UdpQualityHarness {
    static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
    static boolean udp(String mode)throws Exception{
        try(ServerSocket tcp=new ServerSocket(0,10,InetAddress.getLoopbackAddress());DatagramSocket relay=new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(),0))){
            ExecutorService service=Executors.newFixedThreadPool(2);
            Future<?> control=service.submit(()->{try(Socket s=tcp.accept()){DataInputStream in=new DataInputStream(s.getInputStream());OutputStream out=s.getOutputStream();in.readNBytes(3);out.write(new byte[]{5,0});out.flush();in.readNBytes(10);int port=relay.getLocalPort();out.write(new byte[]{5,0,0,1,127,0,0,1,(byte)(port>>8),(byte)port});out.flush();in.read();}catch(Exception ignored){}});
            service.submit(()->{try{relay.setSoTimeout(2000);DatagramPacket p=new DatagramPacket(new byte[2048],2048);relay.receive(p);if(mode.equals("ack"))return;byte[] data=Arrays.copyOf(p.getData(),p.getLength()+16);int qlen=p.getLength();data[12]=(byte)0x81;data[13]=(byte)0x80;data[17]=1;data[qlen]=(byte)0xc0;data[qlen+1]=12;data[qlen+3]=1;data[qlen+5]=1;data[qlen+11]=4;data[qlen+12]=93;data[qlen+13]=(byte)184;data[qlen+14]=(byte)216;data[qlen+15]=34;if(mode.equals("transaction"))data[10]^=1;if(mode.equals("fragment"))data[2]=1;if(mode.equals("destination"))data[4]=8;relay.send(new DatagramPacket(data,data.length,p.getSocketAddress()));}catch(Exception ignored){}});
            Socks5Probe.SocketMaker maker=new Socks5Probe.SocketMaker(){public Socket create(){return new Socket();}public DatagramSocket datagram()throws Exception{return new DatagramSocket();}};
            try{return Socks5Probe.checkUdp(new ProxyEndpoint("127.0.0.1",tcp.getLocalPort(),-1,false),new AtomicBoolean(),ConcurrentHashMap.newKeySet(),maker,InetAddress::getByName);}finally{service.shutdownNow();}
        }
    }
    public static void main(String[] args)throws Exception{
        check(udp("valid"),"Actual UDP reply works");for(String mode:new String[]{"ack","transaction","fragment","destination"})check(!udp(mode),"Reject "+mode);
        long now=System.currentTimeMillis();QualityStats once=new QualityStats(),stable=new QualityStats();once.probe(true,100,now);for(int i=0;i<20;i++)stable.probe(true,200+i%3,now);check(stable.score(now)>once.score(now)+30,"Repeated evidence beats one low ping");
        double before=stable.score(now);for(int i=0;i<20;i++)stable.probe(false,-1,now);check(stable.score(now)<before,"Failure lowers confidence");check(stable.score(now+8L*86400000)<stable.score(now),"Old evidence decays");
        QualityStats saved=QualityStats.fromJson(stable.json());check(saved.samples()==40&&saved.median()==201&&saved.jitter()==1,"Quality data round trip");
        AdaptiveScanPolicy policy=new AdaptiveScanPolicy();for(int i=0;i<12;i++)policy.observe(new Socks5Probe.Result(new ProxyEndpoint("1.1.1.1",1080,400,false),Socks5Probe.Failure.NONE,true,true));check(policy.limit(24)==15,"Healthy path raises concurrency");for(int i=0;i<12;i++)policy.observe(new Socks5Probe.Result(null,Socks5Probe.Failure.HTTPS_TIMEOUT,true,true));check(policy.limit(24)==7&&policy.limit(4)==4,"Congestion lowers within caps");
        check(policy.budget(true).connectMs==6000,"Slow mode retains budget");
        ProxyEndpoint old=ProxyEndpoint.fromJson(new org.json.JSONObject("{\"host\":\"1.1.1.1\",\"port\":1080,\"udp\":true}"));check(!old.udpAdvertised,"Old association-only UDP is not evidence");
        System.out.println("PASS: actual UDP relay, ACK/spoof/fragment rejection, local quality confidence, decay and adaptive bounds");
    }
}
