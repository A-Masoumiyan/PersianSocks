package ir.persiansocks.app;

import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Tests a frozen list only. Pause never marks queued or cancelled work as completed. */
public final class PreparedScanner {
    public interface Persistence { void save(ScanCheckpoint state) throws Exception; }
    public interface Listener {
        void onResult(int index, Socks5Probe.Result result);
        void onProgress(int done, int total, int good);
        void onFinished(ScanCheckpoint state, String message);
        default void onPerformance(int capacity,double testsPerSecond) {}
    }
    public interface Probe { Socks5Probe.Result inspect(ProxyEndpoint p, boolean slow, AtomicBoolean cancelled, Set<Socket> sockets); }
    private static final class Run {
        final ScanCheckpoint state; final Persistence persistence;
        final AtomicBoolean cancelled = new AtomicBoolean(); final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        final ThreadPoolExecutor workers;
        final ScanCapacityGovernor governor;
        volatile int capacity;
        final AdaptiveScanPolicy adaptive;
        Thread coordinator;
        Run(ScanCheckpoint state, Persistence persistence) {
            this.state=state;this.persistence=persistence;adaptive=new AdaptiveScanPolicy(state.slow);
            governor=new ScanCapacityGovernor(state.slow,System.nanoTime()/1000000);
            capacity=state.slow?12:72;
            workers=new ThreadPoolExecutor(capacity,AdaptiveScanPolicy.hardCap(state.slow,true,false),5,TimeUnit.SECONDS,new LinkedBlockingQueue<>(),r->{Thread t=new Thread(r,"proxy-scan-worker");t.setPriority(Thread.MIN_PRIORITY);return t;});
            workers.allowCoreThreadTimeOut(true);
        }
        void close() {
            cancelled.set(true); workers.shutdownNow();
            for (Socket socket : sockets) try { socket.close(); } catch (Exception ignored) {}
            if (coordinator != null) coordinator.interrupt();
        }
    }
    private static final class Completed {
        final int index; final Socks5Probe.Result result;
        Completed(int index, Socks5Probe.Result result) { this.index = index; this.result = result; }
    }
    private static volatile boolean connecting,connected,foreground;
    private static final java.util.concurrent.atomic.AtomicInteger downloads=new java.util.concurrent.atomic.AtomicInteger();
    /** Reservations nest, and closing one twice cannot release another active measurement. */
    public static final class TrafficReservation implements AutoCloseable {
        private final AtomicBoolean closed=new AtomicBoolean();
        private TrafficReservation(){downloads.incrementAndGet();}
        @Override public void close(){if(closed.compareAndSet(false,true))downloads.decrementAndGet();}
    }
    public static TrafficReservation downloadMeasurement(){return new TrafficReservation();}
    public static void connectionActive(boolean value){connected=value;}
    public static int trafficCap(boolean deep){
        int cap=AdaptiveScanPolicy.hardCap(deep,foreground,connecting);
        if(connected)cap=Math.min(cap,deep?6:24);
        if(downloads.get()>0)cap=Math.min(cap,deep?2:4);
        return cap;
    }
    private static volatile ScanCapacityGovernor.Resources resources=deep->deep?24:216;
    public static void resources(ScanCapacityGovernor.Resources provider){resources=provider;}
    private static final java.util.concurrent.atomic.AtomicInteger running=new java.util.concurrent.atomic.AtomicInteger();
    public static boolean running(){return running.get()>0;}
    public static void foreground(boolean value){foreground=value;}
    private static final java.util.concurrent.atomic.AtomicInteger active=new java.util.concurrent.atomic.AtomicInteger();
    public static void connectionPending(boolean value){connecting=value;}
    private volatile Run current;
    public void requestStop(){Run run=current;if(run!=null){run.cancelled.set(true);run.workers.shutdownNow();new Thread(run::close,"stop-proxy-sockets").start();}}
    public synchronized void start(ScanCheckpoint state, Persistence persistence, Socks5Probe.SocketMaker maker,
            Socks5Probe.Resolver resolver, Listener listener) throws Exception {
        ScanProbePolicy.Profile profile=ScanProbePolicy.profile();
        start(state, persistence, (p, slow, stop, sockets) -> ProbeFailurePolicy.observed(profile.inspect(p,slow,stop,sockets,maker,resolver),maker.valid()), listener);
    }
    public synchronized void start(ScanCheckpoint state, Persistence persistence, Probe probe, Listener listener) throws Exception {
        pause(); if (!state.compatible()) throw new IllegalArgumentException("تنظیمات تست تغییر کرده؛ اسکن را از ابتدا شروع کن");
        Run run = new Run(state, persistence); current = run;
        run.coordinator = new Thread(() -> execute(run, probe, listener), "prepared-proxy-scan"); run.coordinator.start();
    }
    public ScanCheckpoint pause() throws Exception {
        Run run;
        synchronized(this){run=current;if(run==null)return null;current=null;}
        run.close();run.persistence.save(run.state);return run.state;
    }
    private void execute(Run run, Probe probe, Listener listener) {
        running.incrementAndGet();
        ScanCheckpoint state = run.state;
        try {
            int[] pending = state.pendingIndices();
            if(current!=run)return;
            run.persistence.save(state);
            if(current!=run||run.cancelled.get())return;
            listener.onProgress(state.done(), state.total(), state.goodCount());
            CompletionService<Completed> tasks = new ExecutorCompletionService<>(run.workers);
            int submitted = 0, received = 0; int batchEnd=Math.min(ScanCheckpoint.BATCH_SIZE,pending.length); long progressAt = 0, savedAt = System.nanoTime();
            while (received < pending.length && !run.cancelled.get()) {
                // Dispatch in catalog order; waiting workers must not overtake higher-priority candidates.
                int capacity=run.governor.capacity(System.nanoTime()/1000000,Math.min(resources.cap(state.slow),trafficCap(state.slow)),submitted-received,submitted<pending.length);
                run.capacity=capacity;if(run.workers.getCorePoolSize()!=capacity)run.workers.setCorePoolSize(capacity);
                while(submitted<pending.length&&submitted-received<capacity&&!run.cancelled.get())submit(tasks,run,probe,pending[submitted++]);
                Future<Completed> task = tasks.poll(250, TimeUnit.MILLISECONDS); if (task == null) continue;
                Completed completed = task.get(); received++;
                synchronized (this) {
                    if (current != run || run.cancelled.get()) return;
                    if (state.record(completed.index, completed.result)) {run.adaptive.observe(completed.result);run.governor.completed(false);listener.onResult(completed.index, completed.result);}
                    long now = System.nanoTime();
                    if (now - progressAt > 400_000_000L || received == pending.length) {
                        listener.onPerformance(run.capacity,run.governor.rate(now/1000000));
                        listener.onProgress(state.done(), state.total(), state.goodCount()); progressAt = now;
                    }
                }
                long now=System.nanoTime();
                if(now-savedAt>15_000_000_000L){run.persistence.save(state);savedAt=System.nanoTime();}
                if(received>=batchEnd){run.persistence.save(state);savedAt=System.nanoTime();batchEnd=Math.min(pending.length,batchEnd+ScanCheckpoint.BATCH_SIZE);}
            }
            run.persistence.save(state);
            synchronized (this) {
                if (current != run) return;
                current = null;
                listener.onFinished(state, "اسکن " + (state.complete() ? "کامل" : "ناتمام") + ": " + state.done() + " از " + state.total() + " • سالم تازه: " + state.goodCount());
            }
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        catch (Exception ex) {
            synchronized (this) {
                if (current == run) { current = null; listener.onFinished(state, "اسکن متوقف شد: " + ex.getMessage()); }
            }
        } finally { run.close();running.decrementAndGet(); }
    }
    private static void submit(CompletionService<Completed> tasks, Run run, Probe probe, int index) {
        tasks.submit(() -> {
            boolean entered=false;
            try{while(!run.cancelled.get()) {int n=active.get();if(n<Math.min(run.capacity,trafficCap(run.state.slow))&&active.compareAndSet(n,n+1)){entered=true;break;}Thread.sleep(50);}
                AdaptiveScanPolicy.enter(run.adaptive.budget(run.state.slow));
                return new Completed(index,entered?probe.inspect(run.state.candidate(index),run.state.slow,run.cancelled,run.sockets):new Socks5Probe.Result(null,Socks5Probe.Failure.CANCELLED,false,false));
            }finally{AdaptiveScanPolicy.leave();if(entered)active.decrementAndGet();}
        });
    }
}
