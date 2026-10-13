import ir.persiansocks.app.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;
public final class BatchCompactionHarness {
 static void check(boolean ok,String s){if(!ok)throw new AssertionError(s);System.out.println("PASS: "+s);}
 public static void main(String[] args)throws Exception {
  List<ProxyEndpoint> rows=new ArrayList<>();for(int i=1;i<=1100;i++)rows.add(ProxyEndpoint.parse("socks5://8.8.8.8:"+i));
  ScanFiles f=new ScanFiles(Files.createTempDirectory("batch").toFile());f.saveCatalog(rows);
  f.mergeCatalog(rows.subList(0,5));check(f.catalogCount()==1100,"Refresh never loses prior catalog on partial download");
  ScanCheckpoint s=new ScanCheckpoint(rows,new ProxyScanner.Options(20000,false,true),"CATALOG");f.activate(s);
  Socks5Probe.Result bad=new Socks5Probe.Result(null,Socks5Probe.Failure.TCP_REFUSED,false,false);
  for(int i=0;i<499;i++)s.record(i,bad);f.save(s);check(f.catalogCount()==1100,"Incomplete chunk is not pruned");
  ProxyEndpoint good=rows.get(499);s.record(499,new Socks5Probe.Result(good,Socks5Probe.Failure.NONE,true,true));f.save(s);
  check(f.catalogCount()==601&&s.candidate(0)==null,"Completed chunk discards failed endpoints from disk and heap");
  check(s.candidate(499)!=null&&s.candidate(500)!=null,"Healthy and untested endpoints are retained");
  ScanCheckpoint saved=f.checkpoint();check(saved.total()==1100&&saved.done()==500&&saved.pending().size()==600,"Compacted checkpoint keeps stable progress and resumes only pending endpoints");
  ScanCheckpoint json=ScanCheckpoint.fromJson(s.json());check(json.done()==500&&json.pending().size()==600,"JSON checkpoint preserves tombstones");
  s.record(500,new Socks5Probe.Result(null,Socks5Probe.Failure.CANCELLED,false,false));f.save(s);check(f.catalogCount()==601&&s.pending().contains(500),"Canceled test never deletes untested endpoint");
  for(int i=500;i<1100;i++)s.record(i,bad);f.save(s);check(f.catalogCount()==1&&f.catalog().get(0).key().equals(good.key()),"Final partial chunk also prunes failures and retains healthy result");
  check(f.checkpoint().complete(),"Final checkpoint is complete after compaction");
  f.discardComplete();check(f.checkpoint()==null,"Completed format 3 checkpoint can be removed");
 }
}
