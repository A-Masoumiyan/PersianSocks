import ir.persiansocks.app.*;
import java.nio.file.*;
import java.util.*;
public final class ScanPressureHarness {
    static void check(boolean value,String why){if(!value)throw new AssertionError(why);System.out.println("PASS: "+why);}
    public static void main(String[] args)throws Exception {
        Path dir=Files.createTempDirectory("scan-pressure");ScanFiles files=new ScanFiles(dir.toFile());List<ProxyEndpoint> input=new ArrayList<>();
        for(int i=0;i<96000;i++)input.add(ProxyEndpoint.parse("socks5://8."+(i>>16)+"."+((i>>8)&255)+"."+(i&255)+":1080"));
        files.saveCatalog(input);ScanCheckpoint state=new ScanCheckpoint(input,new ProxyScanner.Options(100000,false,true),"CATALOG");input=null;files.activate(state);files.save(state);
        byte[] original=java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(dir.resolve("scan-checkpoint.json")));long start=System.nanoTime();
        Socks5Probe.Result bad=new Socks5Probe.Result(null,Socks5Probe.Failure.TCP_TIMEOUT,false,false);
        for(int i=0;i<state.total();i++){if(i%100==0){ProxyEndpoint p=state.candidate(i);state.record(i,new Socks5Probe.Result(new ProxyEndpoint(p.host,p.port,100,false,7,7,System.currentTimeMillis(),Collections.emptySet(),Collections.emptySet(),"",p.type),Socks5Probe.Failure.NONE,true,true));}else state.record(i,bad);if((i+1)%500==0)files.save(state);}
        check(Arrays.equals(original,java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(dir.resolve("scan-checkpoint.json")))),"96k scan never rewrites full checkpoint after initial snapshot");
        check(state.goodCount()==960&&files.catalogCount()==960,"95,040 failed candidates removed, 960 successes retained");
        check(state.candidate(1)==null&&state.candidate(95999)==null,"early and late failed endpoint objects freed");
        ScanCheckpoint restored=files.checkpoint();check(restored.complete()&&restored.goodCount()==960,"all delta batches recover correct outcomes and completion");
        long bytes;try(java.util.stream.Stream<Path> paths=Files.list(dir)){bytes=paths.mapToLong(p->{try{return Files.size(p);}catch(Exception e){return 0;}}).sum();}
        check(bytes<15_000_000,"total scan state/catalog writes bounded below 15MB for 96k inputs");
        Files.writeString(dir.resolve("scan-events.jsonl"),"{\"base\":",StandardOpenOption.APPEND);check(files.checkpoint().complete(),"power-loss truncated tail preserves prior completed batches");
        files.discardComplete();check(files.catalog().size()==960&&files.checkpoint()==null,"final disk compaction preserves successes and discards completed state");
        System.out.println("BENCHMARK: 96000 rows, "+bytes+" persisted bytes, "+((System.nanoTime()-start)/1_000_000)+"ms, max heap 128MiB");
    }
}
