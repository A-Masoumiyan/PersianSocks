import ir.persiansocks.app.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MaxScanHarness {
    static int passed;
    static void check(boolean ok,String name){
        if(!ok)throw new AssertionError(name);
        passed++;System.out.println("PASS: "+name);
    }
    static Reader chunks(String value,int size){
        return new Reader(){
            int index;
            public int read(char[] out,int off,int length){
                if(index>=value.length())return -1;
                int count=Math.min(Math.min(length,size),value.length()-index);
                value.getChars(index,index+count,out,off);index+=count;return count;
            }
            public void close(){}
        };
    }
    static List<String> addresses(List<ProxyEndpoint> list){
        List<String> out=new ArrayList<>();for(ProxyEndpoint p:list)out.add(p.address());return out;
    }
    public static void main(String[] args)throws Exception{
        ProxyScanner.Options all=new ProxyScanner.Options(5000,false,true);
        check(all.scanAll&&all.maxTests==Integer.MAX_VALUE,"Max has no fixed candidate-count cutoff");
        check(!new ProxyScanner.Options(50000,false).scanAll&&new ProxyScanner.Options(50000,false).maxTests==20000,
            "Numeric scan budgets retain the existing upper bound");
        check(new ProxyScanner.Options(0,false).maxTests==100,"An invalid numeric count does not accidentally enable Max");
        StringBuilder large=new StringBuilder();
        for(int i=1;i<=24000;i++)large.append("8.8.8.8:").append(i).append('\n');
        List<ProxyEndpoint> parsed=ProxyScanner.parseStream(new StringReader(large.toString()),true,Integer.MAX_VALUE,new AtomicBoolean(false));
        check(parsed.size()==24000&&parsed.get(23999).port==24000,"Max parses all 24,000 unique endpoints beyond the former limit");
        check(ProxyScanner.parseList(large.toString(),true).size()==20000,"The default parser retains numeric-mode limits");
        List<ProxyEndpoint> merged=ProxyScanner.mergeCandidates(Collections.emptyList(),
            Arrays.asList(parsed,parsed),Arrays.asList(parsed.get(0)),all.maxTests);
        check(merged.size()==24000,"Max candidate selection retains every unique endpoint across overlapping sources");
        check(ProxyScanner.mergeCandidates(Collections.emptyList(),Arrays.asList(parsed),Collections.emptyList(),5000).size()==5000,
            "A selected numeric budget still stops candidate selection at that budget");
        String sample="socks5://8.8.8.8:1080\n1.1.1.1:65535\n192.168.1.1:80\n8.8.8.8:1080\n"+
            "256.1.1.1:80 10.8.8.8.8:1080 8.8.8.8:123456 / 9.9.9.9:9050";
        List<String> expected=addresses(ProxyScanner.parseList(sample,true));
        for(int size:new int[]{1,7,13,31,32,63,64,65,128})
            if(!addresses(ProxyScanner.parseStream(chunks(sample,size),true,Integer.MAX_VALUE,new AtomicBoolean(false))).equals(expected))
                throw new AssertionError("Chunk boundary "+size);
        check(true,"Stream parsing preserves complete tokens, validation and deduplication across nine chunk sizes");
        String beyondOldByteLimit="x".repeat(1_600_000)+"\n1.1.1.1:1080\n8.8.8.8:9050";
        check(ProxyScanner.parseStream(new StringReader(beyondOldByteLimit),true,Integer.MAX_VALUE,new AtomicBoolean(false)).size()==2,
            "Max reads valid results after the former 1.5 MB download cutoff");
        Reader forbidden=new Reader(){
            public int read(char[] b,int o,int n){throw new AssertionError("Cancelled parser read input");}
            public void close(){}
        };
        check(ProxyScanner.parseStream(forbidden,true,Integer.MAX_VALUE,new AtomicBoolean(true)).isEmpty(),
            "A cancelled Max download does not keep reading");
        check(ProxyScanner.parseStream(new StringReader(""),true,Integer.MAX_VALUE,new AtomicBoolean(false)).isEmpty(),
            "Empty Max sources are supported");
        Random random=new Random(42);
        for(int i=0;i<120;i++){
            String fixture="x".repeat(random.nextInt(180))+";8.8.8.8:"+(i+1)+"!"+
                "a".repeat(random.nextInt(120))+" 1.1.1.1:1080\n";
            List<String> want=addresses(ProxyScanner.parseList(fixture,true));
            List<String> got=addresses(ProxyScanner.parseStream(chunks(fixture,1+random.nextInt(90)),true,
                Integer.MAX_VALUE,new AtomicBoolean(false)));
            if(!want.equals(got))throw new AssertionError("Random stream fixture "+i);
        }
        check(true,"120 boundary fixtures match whole-input parsing");
        System.out.println("RESULT: "+passed+" checks passed");
    }
}
