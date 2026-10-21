package ir.persiansocks.app;
import android.os.ParcelFileDescriptor;
import java.io.*;
import java.nio.charset.StandardCharsets;
/** Read the Linux interface name for our own TUN descriptor without elevated permissions. */
final class TunLinkInfo {
 static String name(ParcelFileDescriptor tun){try(FileInputStream in=new FileInputStream("/proc/self/fdinfo/"+tun.getFd())){byte[] bytes=new byte[4096];int n=in.read(bytes);if(n>0)for(String line:new String(bytes,0,n,StandardCharsets.US_ASCII).split("\n"))if(line.startsWith("iff:"))return line.substring(4).trim();}catch(Exception ignored){}return "";}
}
