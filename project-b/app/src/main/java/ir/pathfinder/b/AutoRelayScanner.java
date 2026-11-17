package ir.pathfinder.b;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

final class AutoRelayScanner {
    interface Callback { void progress(int done,int total); }
    private static final int MAX_CANDIDATES=240, PARALLEL=20;
    static final class Result {
        final RelayProbe.Entry candidate;final int checked;
        Result(RelayProbe.Entry candidate,int checked){this.candidate=candidate;this.checked=checked;}
    }
    static Result search(Context ctx, Callback cb, AtomicBoolean cancelled) throws Exception {
        ArrayList<RelayProbe.Entry> entries=new ArrayList<>();
        HashSet<String> unique=new HashSet<>();
        try(BufferedReader in=new BufferedReader(new InputStreamReader(
                ctx.getAssets().open("offline-socks5.txt"),"UTF-8"))) {
            for(String line;(line=in.readLine())!=null;){
                try{
                    RelayProbe.Entry e=RelayProbe.Entry.parse(line);
                    if(e!=null && unique.add(e.label()) && e.user.isEmpty())entries.add(e);
                }catch(Exception ignored){}
            }
        }
        if(entries.isEmpty())throw new IOException("No offline relay candidates in the APK");
        Collections.shuffle(entries,new Random(System.nanoTime()));
        entries=new ArrayList<>(entries.subList(0,Math.min(entries.size(),MAX_CANDIDATES)));
        final int total=entries.size();
        ConnectivityManager cm=(ConnectivityManager)ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network physical=null;
        if(cm!=null)for(Network n:cm.getAllNetworks()) {
            NetworkCapabilities cap=cm.getNetworkCapabilities(n);
            if(cap!=null && !cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                && cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                physical=n;break;
            }
        }
        final Network network=physical;
        ExecutorService pool=Executors.newFixedThreadPool(PARALLEL);
        CompletionService<RelayProbe.Entry> finished=new ExecutorCompletionService<>(pool);
        AtomicInteger completed=new AtomicInteger();
        for(RelayProbe.Entry candidate:entries) {
            finished.submit(()->{
                if(cancelled.get() || Thread.currentThread().isInterrupted())return null;
                try {
                    RelayProbe.verify(network,candidate);
                    return candidate;
                }catch(Exception ignored){return null;}
            });
        }
        try {
            for(int i=0;i<total&&!cancelled.get();i++) {
                Future<RelayProbe.Entry> result=finished.poll(13,TimeUnit.SECONDS);
                if(result==null)continue; // bounded by request timeout, not indefinite waiting
                RelayProbe.Entry good=null;
                try {good=result.get();}catch(Exception ignored){}
                int done=completed.incrementAndGet();
                if(done%6==0||done==total||good!=null)cb.progress(done,total);
                if(good!=null) {
                    cancelled.set(true);
                    return new Result(good,done);
                }
            }
        }finally {
            pool.shutdownNow();
        }
        if(cancelled.get())throw new InterruptedException("Cancelled");
        return new Result(null,completed.get());
    }
}