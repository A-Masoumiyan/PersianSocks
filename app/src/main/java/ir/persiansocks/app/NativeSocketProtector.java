package ir.persiansocks.app;

import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.StructStat;
import java.io.*;
import java.util.*;

/** OpenVPN can pass the same socket before and after connect; network binding is done once. */
final class NativeSocketProtector {
 private final VpnService owner;private final PhysicalTestNetwork path;
 private final Set<String> bound=new HashSet<>();
 NativeSocketProtector(VpnService owner,PhysicalTestNetwork path){this.owner=owner;this.path=path;}
 synchronized boolean protect(FileDescriptor fd)throws Exception{
  if(!path.valid())return false;
  StructStat stat=Os.fstat(fd);String identity=stat.st_dev+":"+stat.st_ino;
  try(ParcelFileDescriptor copy=ParcelFileDescriptor.dup(fd)){if(!owner.protect(copy.getFd()))return false;}
  if(!bound.contains(identity)){
   if(bound.size()>=64)throw new IOException("Too many native connection sockets");
   // A new, already-connected socket is refused rather than accepted without a physical binding.
   path.network().bindSocket(fd);bound.add(identity);
  }
  return path.valid();
 }
}
