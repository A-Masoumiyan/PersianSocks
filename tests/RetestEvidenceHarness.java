import ir.persiansocks.app.*;
import java.util.*;
import java.nio.file.*;
public final class RetestEvidenceHarness {
 static int checks;static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;System.out.println("PASS: "+message);}
 static ProxyEndpoint good(){return new ProxyEndpoint("8.8.8.8",1080,25,false,7,7,1);}
 public static void main(String[] args)throws Exception {
  for(Socks5Probe.Failure failure:new Socks5Probe.Failure[]{Socks5Probe.Failure.TCP_TIMEOUT,Socks5Probe.Failure.TCP_REFUSED,Socks5Probe.Failure.HTTPS_TIMEOUT,Socks5Probe.Failure.HTTP_FAILED,Socks5Probe.Failure.IO_ERROR,Socks5Probe.Failure.TLS_FAILED,Socks5Probe.Failure.SOCKS_AUTH,Socks5Probe.Failure.SOCKS_PROTOCOL}){
   ScanCheckpoint state=new ScanCheckpoint(Collections.singletonList(good()),new ProxyScanner.Options(100,false),"RECHECK");
   state.record(0,new Socks5Probe.Result(null,failure,false,false));
   ScanFiles files=new ScanFiles(Files.createTempDirectory("retest-evidence").toFile());files.activate(state);files.save(state);
   ScanCheckpoint disk=files.checkpoint();check(disk.failure(0)==failure,"Failure reason survives disk restart: "+failure);
   ProxyIndex index=new ProxyIndex();index.accept(good());index.apply(disk);
   check((index.find(good().key())==null)==ProbeFailurePolicy.invalidates(failure),"Transient failures preserve prior verification; protocol/TLS failures remove it: "+failure);
   check((disk.merge(Collections.singletonList(good())).isEmpty())==ProbeFailurePolicy.invalidates(failure),"Merged checkpoint obeys failure policy: "+failure);
  }
  check(PublicIpEvidence.parse(" 1.1.1.1\n").equals("1.1.1.1"),"Parse actual public IPv4 response");
  for(String bad:new String[]{"192.168.1.1","256.1.1.1","<html>1.1.1.1</html>","1.1.1.1:443","","1.1.1.1\n8.8.8.8"})check(PublicIpEvidence.parse(bad).isEmpty(),"Reject invalid or private IP evidence: "+bad);
  check(PublicIpEvidence.matches("1.1.1.1","1.1.1.1"),"Verified exit may match even when ingress server address differs (NAT)");
  check(!PublicIpEvidence.matches("1.1.1.1","8.8.8.8")&&!PublicIpEvidence.matches("1.1.1.1",""),"Mismatch or missing proxy measurement cannot certify a route");
  FilteredSpeedBatch saved=new FilteredSpeedBatch(Arrays.asList(good(),new ProxyEndpoint("1.1.1.1",80,-1,false)));
  check(saved.total()==2&&saved.next().key().equals(good().key()),"Saved speed queue includes unverified saved entries without applying active filters");
  saved.cancel();check(saved.next()==null,"Saved batch cancellation prevents further probes");
  System.out.println("RESULT: "+checks+" checks passed");
 }
}
