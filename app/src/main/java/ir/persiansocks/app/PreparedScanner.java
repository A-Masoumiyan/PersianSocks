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
    }
    public interface Probe { Socks5Probe.Result inspect(ProxyEndpoint p, boolean slow, AtomicBoolean cancelled, Set<Socket> sockets); }
    private static final class Run {
        final ScanCheckpoint state; final Persistence persistence;
        final AtomicBoolean cancelled = new AtomicBoolean(); final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        final ExecutorService workers = Executors.newFixedThreadPool(24,r->{Thread t=new Thread(r,"proxy-scan-worker");t.setPriority(Thread.MIN_PRIORITY);return t;});
        final AdaptiveScanPolicy adaptive=new AdaptiveScanPolicy();
        Thread coordinator;
        Run(ScanCheckpoint state, Persistence persistence) { this.state = state; this.persistence = persistence; }
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
    private static volatile boolean connecting,foreground;
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
        start(state, persistence, (p, slow, stop, sockets) -> profile.inspect(p,slow,stop,sockets,maker,resolver), listener);
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
            while (submitted < Math.min(48, batchEnd)) submit(tasks, run, probe, pending[submitted++]);
            while (received < pending.length && !run.cancelled.get()) {
                Future<Completed> task = tasks.poll(250, TimeUnit.MILLISECONDS); if (task == null) continue;
                Completed completed = task.get(); received++;
                synchronized (this) {
                    if (current != run || run.cancelled.get()) return;
                    if (state.record(completed.index, completed.result)) {run.adaptive.observe(completed.result);listener.onResult(completed.index, completed.result);}
                    long now = System.nanoTime();
                    if (now - progressAt > 400_000_000L || received == pending.length) {
                        listener.onProgress(state.done(), state.total(), state.goodCount()); progressAt = now;
                    }
                }
                long now=System.nanoTime();
                if(now-savedAt>15_000_000_000L){run.persistence.save(state);savedAt=System.nanoTime();}
                if(received==batchEnd){run.persistence.save(state);savedAt=System.nanoTime();batchEnd=Math.min(pending.length,batchEnd+ScanCheckpoint.BATCH_SIZE);while(submitted<Math.min(batchEnd,received+48)&&!run.cancelled.get())submit(tasks,run,probe,pending[submitted++]);}
                else if (submitted < batchEnd && !run.cancelled.get()) submit(tasks, run, probe, pending[submitted++]);
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
            try{while(!run.cancelled.get()) {int n=active.get();if(n<run.adaptive.limit(connecting?4:foreground?24:8)&&active.compareAndSet(n,n+1)){entered=true;break;}Thread.sleep(50);}
                AdaptiveScanPolicy.enter(run.adaptive.budget(run.state.slow));
                return new Completed(index,entered?probe.inspect(run.state.candidate(index),run.state.slow,run.cancelled,run.sockets):new Socks5Probe.Result(null,Socks5Probe.Failure.CANCELLED,false,false));
            }finally{AdaptiveScanPolicy.leave();if(entered)active.decrementAndGet();}
        });
    }
}
