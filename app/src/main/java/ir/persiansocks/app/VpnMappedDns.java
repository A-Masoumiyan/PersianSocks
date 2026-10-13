package ir.persiansocks.app;
import android.net.Network;
import java.net.*;
import java.util.*;
/** DNS goes through the captured VPN to its native mapdns, without physical-network fallback. */
public final class VpnMappedDns {
    private VpnMappedDns(){}
    public static InetAddress[] resolve(Network vpn,String host)throws Exception {
        int id=new java.security.SecureRandom().nextInt(65536);byte[] query=DnsMessage.query(host,id);
        try(DatagramSocket socket=new DatagramSocket(null)){
            socket.bind(new InetSocketAddress(0));vpn.bindSocket(socket);socket.setSoTimeout(5000);socket.connect(InetAddress.getByName("198.18.0.2"),53);
            socket.send(new DatagramPacket(query,query.length));byte[] bytes=new byte[4096];DatagramPacket response=new DatagramPacket(bytes,bytes.length);socket.receive(response);
            return DnsMessage.answers(Arrays.copyOf(bytes,response.getLength()),host,id);
        }
    }
}
