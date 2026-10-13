package ir.persiansocks.app;
import java.io.*;
import java.net.*;
import java.util.*;
/** Bounded DNS A wire messages; verifies transaction, question and response rather than guessing an IP. */
public final class DnsMessage {
    private DnsMessage(){}
    public static byte[] query(String host,int id)throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        out.writeShort(id);out.writeShort(0x0100);out.writeShort(1);out.writeShort(0);out.writeShort(0);out.writeShort(0);
        for(String label:host.split("\\.")){byte[] b=label.getBytes(java.nio.charset.StandardCharsets.US_ASCII);if(b.length<1||b.length>63)throw new IOException("DNS label");out.writeByte(b.length);out.write(b);}out.writeByte(0);out.writeShort(1);out.writeShort(1);return bytes.toByteArray();
    }
    private static int word(byte[] b,int i)throws IOException {if(i<0||i+1>=b.length)throw new IOException("Truncated DNS");return (b[i]&255)*256+(b[i+1]&255);}
    private static String name(byte[] b,int[] cursor)throws IOException {
        StringBuilder name=new StringBuilder();int pos=cursor[0],next=-1;
        for(int hops=0;hops<128;hops++){
            if(pos>=b.length)throw new IOException("Truncated DNS name");int n=b[pos++]&255;
            if((n&192)==192){if(pos>=b.length)throw new IOException("DNS pointer");if(next<0)next=pos+1;pos=((n&63)<<8)|(b[pos]&255);continue;}
            if(n==0){cursor[0]=next<0?pos:next;return name.toString();}
            if(n>63||pos+n>b.length||name.length()+n>253)throw new IOException("DNS name");if(name.length()>0)name.append('.');for(int i=0;i<n;i++)name.append((char)(b[pos++]&255));
        }throw new IOException("DNS pointer loop");
    }
    public static InetAddress[] answers(byte[] b,String host,int id)throws IOException {
        if(b.length<12||word(b,0)!=id||(word(b,2)&0x8000)==0||(word(b,2)&0x020f)!=0||word(b,4)!=1)throw new IOException("Invalid DNS response");
        int[] cursor={12};if(!name(b,cursor).equalsIgnoreCase(host)||word(b,cursor[0])!=1||word(b,cursor[0]+2)!=1)throw new IOException("Wrong DNS question");cursor[0]+=4;
        List<InetAddress> result=new ArrayList<>();int records=word(b,6);if(records>64)throw new IOException("DNS record limit");
        for(int i=0;i<records;i++){String owner=name(b,cursor);int pos=cursor[0],type=word(b,pos),kind=word(b,pos+2),len=word(b,pos+8);pos+=10;if(pos+len>b.length)throw new IOException("Truncated DNS answer");
            if(type==1&&kind==1&&len==4&&owner.equalsIgnoreCase(host))result.add(InetAddress.getByAddress(Arrays.copyOfRange(b,pos,pos+4)));cursor[0]=pos+len;}
        if(result.isEmpty())throw new IOException("No mapped IPv4 answer");return result.toArray(new InetAddress[0]);
    }
}
