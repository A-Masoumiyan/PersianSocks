package ir.persiansocks.app;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.*;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Short maintenance jobs; never owns the VPN, a foreground service or the paused scan. */
public final class ProxyMaintenance extends Worker {
    private static final String NAME="proxy-short-maintenance-v1";
    private final AtomicBoolean cancelled=new AtomicBoolean();private final Set<Socket> sockets=ConcurrentHashMap.newKeySet();private final ProxyScanner collector=new ProxyScanner();
    public ProxyMaintenance(@NonNull Context c,@NonNull WorkerParameters p){super(c,p);}
    public static void schedule(Context context){
        WorkManager manager=WorkManager.getInstance(context);
        if(!context.getSharedPreferences("persiansocks",0).getBoolean("background-maintenance",true)){manager.cancelUniqueWork(NAME);return;}
        Constraints limits=new Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresBatteryNotLow(true).build();
        PeriodicWorkRequest request=new PeriodicWorkRequest.Builder(ProxyMaintenance.class,6,TimeUnit.HOURS,30,TimeUnit.MINUTES).setInitialDelay(6,TimeUnit.HOURS).setConstraints(limits).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.MINUTES).build();
        manager.enqueueUniquePeriodicWork(NAME,ExistingPeriodicWorkPolicy.KEEP,request);
    }
    @NonNull @Override public Result doWork(){
        Context c=getApplicationContext();if(!c.getSharedPreferences("persiansocks",0).getBoolean("background-maintenance",true)||busy())return Result.success();
        long end=System.nanoTime()+90_000_000_000L;
        try{
            ScanInputs.initializePolicy(c);ServiceTargets.configure(ServiceTargets.decode(c.getSharedPreferences("persiansocks",0).getString("test-domains","[]")));
            ProxyStore.load(c);PhysicalTestNetwork physical=PhysicalTestNetwork.capture(c);ScanFiles files=new ScanFiles(c.getFilesDir());
            CountDownLatch fetched=new CountDownLatch(1);java.util.concurrent.atomic.AtomicReference<List<ProxyEndpoint>> received=new java.util.concurrent.atomic.AtomicReference<>();
            collector.collect(Collections.emptyList(),Collections.emptyList(),ScanInputs.sources(c),new ProxyScanner.CollectionListener(){public void onProgress(String message){}public void onFinished(List<ProxyEndpoint> rows,String report){received.set(rows);fetched.countDown();}public void onFailed(String message){fetched.countDown();}});
            while(!cancelled.get()&&!isStopped()&&!busy()&&System.nanoTime()<end&&fetched.getCount()!=0)fetched.await(250,TimeUnit.MILLISECONDS);
            collector.stop();if(cancelled.get()||isStopped()||busy()||!physical.valid())return Result.success();
            if(received.get()!=null)files.mergeCatalog(received.get());
            List<ProxyEndpoint> candidates=ProxyResults.visible(ProxyStore.index().snapshot(),ScanInputs.filter(c),ProxyResults.Sort.QUALITY,ScanInputs.protocol(c),ScanInputs.pingLimit(c));
            // Recheck at most six existing results; bounded to one HTTPS destination each.
            for(int i=0;i<Math.min(6,candidates.size())&&System.nanoTime()<end-12_000_000_000L;i++){
                if(cancelled.get()||isStopped()||busy()||!physical.valid())break;ProxyEndpoint p=candidates.get(i);
                ServiceTargets.Target target=SingleProxyTest.latencyTarget(p,ScanInputs.filter(c));
                Socks5Probe.Result sample=Socks5Probe.inspectTarget(p,target,false,cancelled,sockets,physical,physical);
                if(cancelled.get()||isStopped()||busy()||!physical.valid())break;
                QualityHistory.probe(physical,p,sample);
                if(sample.endpoint!=null){Map<String,Long> timings=new HashMap<>(p.serviceLatencies);timings.put(target.url(),sample.endpoint.latencyMs);ProxyStore.index().accept(p.withLatency(sample.endpoint.latencyMs,target.url()).withServiceLatencies(timings));}
                else if(ProbeFailurePolicy.invalidates(sample.failure))ProxyStore.index().reject(p.key(),System.currentTimeMillis());
            }
            ProxyStore.flush(c);return Result.success();
        }catch(Exception e){android.util.Log.i("PersianSocks","Short maintenance deferred",e);return getRunAttemptCount()<2?Result.retry():Result.success();}
        finally{onStopped();}
    }
    private boolean busy(){return MainActivity.backgroundBusy()||PreparedScanner.running()||ProxyVpnService.phase()!=ProxyVpnService.DISCONNECTED||ProxyVpnService.autoScanning();}
    @Override public void onStopped(){cancelled.set(true);collector.stop();for(Socket s:sockets)try{s.close();}catch(Exception ignored){}}
}
