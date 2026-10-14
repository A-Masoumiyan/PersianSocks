package ir.persiansocks.app;

import java.io.*;
import java.net.*;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/** RFC 1928 UDP relay, with an actual, transaction-matched DNS response. */
public final class UdpRoundTrip {
    private UdpRoundTrip(){}
    private static final SecureRandom RANDOM=new SecureRandom();
    static InetSocketAddress relay(InputStream raw,InetAddress proxy,Socks5Probe.Resolver dns)throws Exception {
        DataInputStream in=new DataInputStream(raw);
        if(in.readUnsignedByte()!=5||in.readUnsignedByte()!=0||in.readUnsignedByte()!=0)throw new IOException("UDP associate rejected");
        int type=in.readUnsignedByte();InetAddress address;
        if(type==1||type==4){byte[] bytes=new byte[type==1?4:16];in.readFully(bytes);address=InetAddress.getByAddress(bytes);if(address.isAnyLocalAddress())address=proxy;}
        else if(type==3){int n=in.readUnsignedByte();if(n==0)throw new IOException("Empty UDP relay");byte[] name=new byte[n];in.readFully(name);address=dns.resolve(new String(name,java.nio.charset.StandardCharsets.US_ASCII));}
        else throw new IOException("UDP address type");
        int port=in.readUnsignedShort();if(port==0||address.isMulticastAddress())throw new IOException("Invalid UDP relay");
        // Public proxy metadata must never steer a test into private phone/LAN services.
        if(!proxy.isLoopbackAddress()&&(address.isLoopbackAddress()||address.isSiteLocalAddress()||address.isLinkLocalAddress()))throw new IOException("Private UDP relay");
        return new InetSocketAddress(address,port);
    }
    static byte[] query(int id){byte[] question={7,'e','x','a','m','p','l','e',3,'c','o','m',0,0,1,0,1};byte[] q=new byte[12+question.length];q[0]=(byte)(id>>8);q[1]=(byte)id;q[2]=1;q[5]=1;System.arraycopy(question,0,q,12,question.length);return q;}
    static byte[] wrap(byte[] q){byte[] out=new byte[10+q.length];out[3]=1;out[4]=1;out[5]=1;out[6]=1;out[7]=1;out[9]=53;System.arraycopy(q,0,out,10,q.length);return out;}
    public static boolean valid(byte[] packet,int length,byte[] q){
        if(length<10+q.length||length>packet.length||packet[0]!=0||packet[1]!=0||packet[2]!=0||packet[3]!=1)return false;
        if(packet[4]!=1||packet[5]!=1||packet[6]!=1||packet[7]!=1||packet[8]!=0||packet[9]!=53)return false;
        int o=10;
        if(packet[o]!=q[0]||packet[o+1]!=q[1]||(packet[o+2]&0xf8)!=0x80||(packet[o+3]&15)!=0||packet[o+4]!=0||packet[o+5]!=1||(packet[o+6]==0&&packet[o+7]==0))return false;
        for(int i=12;i<q.length;i++)if(packet[o+i]!=q[i])return false;
        // At least a complete answer header and address must follow the question.
        int a=o+q.length;if(length<a+16)return false;
        if((packet[a]&0xc0)!=0xc0||packet[a+2]!=0||packet[a+3]!=1||packet[a+4]!=0||packet[a+5]!=1||packet[a+10]!=0||packet[a+11]!=4)return false;
        return true;
    }
    static boolean exchange(DatagramSocket socket,InetSocketAddress relay,AtomicBoolean cancelled)throws Exception{
        socket.connect(relay);socket.setSoTimeout(250);byte[] q=query(RANDOM.nextInt(65536)),wire=wrap(q);socket.send(new DatagramPacket(wire,wire.length));
        long end=System.nanoTime()+1_500_000_000L;
        while(!cancelled.get()&&!Thread.currentThread().isInterrupted()&&System.nanoTime()<end){byte[] bytes=new byte[2048];DatagramPacket response=new DatagramPacket(bytes,bytes.length);try{socket.receive(response);if(valid(bytes,response.getLength(),q))return true;}catch(SocketTimeoutException ignored){}}
        return false;
    }
}
