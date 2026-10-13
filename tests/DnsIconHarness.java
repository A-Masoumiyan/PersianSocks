import ir.persiansocks.app.*;
import java.util.*;
import java.io.*;
public final class DnsIconHarness {
    static int count;
    static void check(boolean value,String why){count++;if(!value)throw new AssertionError(why);}
    static void rejected(byte[] data,String host,int id)throws Exception {try{DnsMessage.answers(data,host,id);throw new AssertionError("bad DNS accepted");}catch(IOException expected){count++;}}
    public static void main(String[] args)throws Exception {
        byte[] q=DnsMessage.query("www.example.com",123);ByteArrayOutputStream bytes=new ByteArrayOutputStream();bytes.write(q);byte[] reply=bytes.toByteArray();reply[2]=(byte)0x81;reply[3]=(byte)0x80;reply[7]=1;bytes.reset();bytes.write(reply);bytes.write(new byte[]{(byte)0xc0,12,0,1,0,1,0,0,0,60,0,4,(byte)198,19,0,1});reply=bytes.toByteArray();
        check(DnsMessage.answers(reply,"www.example.com",123)[0].getHostAddress().equals("198.19.0.1"),"mapped VPN DNS resolves domain-shaped CONNECT destination");
        rejected(reply,"other.example.com",123);rejected(reply,"www.example.com",124);rejected(Arrays.copyOf(reply,reply.length-1),"www.example.com",123);
        byte[] truncated=reply.clone();truncated[2]|=2;rejected(truncated,"www.example.com",123);
        List<String> icons=IconPolicy.icons("<script>const x='<link rel=icon href=https://wrong.example/icon.png>'</script><!--<link rel=icon href=https://wrong.example/t.png>--><link data-href='https://wrong.example/d.png' rel='shortcut icon' href='/real.png'><link rel='apple-touch-icon' href='https://cdn.example/icon.png'><link rel=icon type='image/svg+xml' href='/skip.svg'>","https://www.example.com/");
        check(icons.get(0).equals("https://www.example.com/real.png"),"icon uses actual href, ignores comments/scripts/data-href");check(icons.contains("https://cdn.example/icon.png"),"site-declared CDN icon accepted without name guessing");
        check(!icons.contains("https://www.example.com/skip.svg"),"unsupported SVG not displayed as another logo");
        for(String url:new String[]{"http://example.com/icon.png","https://user:pass@example.com/i","https://192.168.1.1/i","file:///icon.png","https://thing.local/i"})check(!IconPolicy.https(url),"reject invalid icon URL");
        check(IconPolicy.sameSite("https://facebook.com/","https://www.facebook.com/"),"canonical www redirect");check(!IconPolicy.sameSite("https://facebook.com/","https://facebook.com.evil.test/"),"reject lookalike page redirect");
        System.out.println("PASS: "+count+" VPN DNS wire and favicon origin/parser checks");
    }
}
