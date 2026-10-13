import ir.persiansocks.app.*;
import java.nio.file.*;
import java.util.*;
public final class ScanJournalHarness {
    public static void main(String[] args)throws Exception {
        Path dir=Files.createTempDirectory("scan-journal");ScanFiles files=new ScanFiles(dir.toFile());List<ProxyEndpoint> rows=Arrays.asList(ProxyEndpoint.parse("8.8.8.8:1080"),ProxyEndpoint.parse("8.8.4.4:1080"));files.saveCatalog(rows);
        ScanCheckpoint old=new ScanCheckpoint(rows,new ProxyScanner.Options(100,false,true),"HEALTHY");files.activate(old);files.save(old);old.record(0,new Socks5Probe.Result(null,Socks5Probe.Failure.TCP_TIMEOUT,false,false));files.save(old);byte[] leftover=Files.readAllBytes(dir.resolve("scan-events.jsonl"));
        ScanCheckpoint next=new ScanCheckpoint(rows,new ProxyScanner.Options(100,false,true),"HEALTHY");files.activate(next);files.save(next);Files.write(dir.resolve("scan-events.jsonl"),leftover);
        if(files.checkpoint().done()!=0)throw new AssertionError("Stale generation replayed");System.out.println("PASS: old deltas cannot mark a new scan complete after snapshot replacement/power loss");
        next.record(1,new Socks5Probe.Result(null,Socks5Probe.Failure.CANCELLED,false,false));files.save(next);if(files.checkpoint().done()!=0)throw new AssertionError("Canceled scan completed");System.out.println("PASS: cancellation never becomes a persisted completed result");
    }
}
