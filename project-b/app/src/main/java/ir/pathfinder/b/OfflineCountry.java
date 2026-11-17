package ir.pathfinder.b;

import java.io.*;
import java.nio.*;
import java.util.*;

/** Read-only offline IPv4 to country mapping. Boundaries are approximate. */
final class OfflineCountry {
    private ByteBuffer data;
    private int count;
    OfflineCountry(InputStream input)throws IOException {
        try(InputStream in=input;ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[8192];int n;
            while((n=in.read(b))!=-1){
                out.write(b,0,n);
                if(out.size()>12_000_000)throw new IOException("Country data too large");
            }
            byte[] raw=out.toByteArray();
            ByteBuffer buf=ByteBuffer.wrap(raw).asReadOnlyBuffer().order(ByteOrder.BIG_ENDIAN);
            if(raw.length<8||buf.getInt(0)!=0x50534343)throw new IOException("Country data invalid");
            int size=buf.getInt(4);
            if(size<0||raw.length!=8L+10L*size)throw new IOException("Country index invalid");
            data=buf;count=size;
        }
    }
    String code(String ipv4){
        if(data==null)return "";
        String[] split=ipv4.split("\\.",-1);
        if(split.length!=4)return "";
        long ip=0;
        try {
            for(String item:split){
                if(item.isEmpty()||item.length()>3)return "";
                int v=Integer.parseInt(item);
                if(v<0||v>255)return "";
                ip=(ip<<8)|v;
            }
        }catch(NumberFormatException ex){return "";}
        int lo=0,hi=count-1;
        while(lo<=hi){
            int m=(lo+hi)>>>1,at=8+m*10;
            long begin=Integer.toUnsignedLong(data.getInt(at));
            long end=Integer.toUnsignedLong(data.getInt(at+4));
            if(ip<begin)hi=m-1;else if(ip>end)lo=m+1;
            else return ""+(char)data.get(at+8)+(char)data.get(at+9);
        }
        return "";
    }
}