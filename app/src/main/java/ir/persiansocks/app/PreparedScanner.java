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
        final ExecutorService workers = Executors.newFixedThreadPool(60);
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
    private volatile Run current;
    public void requestStop(){Run run=current;if(run!=null){run.cancelled.set(true);run.workers.shutdownNow();new Thread(run::close,"stop-proxy-sockets").start();}}
    public synchronized void start(ScanCheckpoint state, Persistence persistence, Socks5Probe.SocketMaker maker,
            Socks5Probe.Resolver resolver, Listener listener) throws Exception {
        start(state, persistence, (p, slow, stop, sockets) -> Socks5Probe.inspect(p, slow, stop, sockets, maker, resolver), listener);
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
        ScanCheckpoint state = run.state;
        try {
            List<Integer> pending = state.pending();
            if(current!=run)return;
            run.persistence.save(state);
            if(current!=run||run.cancelled.get())return;
            listener.onProgress(state.done(), state.total(), state.goodCount());
            CompletionService<Completed> tasks = new ExecutorCompletionService<>(run.workers);
            int submitted = 0, received = 0; int batchEnd=Math.min(ScanCheckpoint.BATCH_SIZE,pending.size()); long progressAt = 0, savedAt = System.nanoTime();
            while (submitted < Math.min(120, batchEnd)) submit(tasks, run, probe, pending.get(submitted++));
            while (received < pending.size() && !run.cancelled.get()) {
                Future<Completed> task = tasks.poll(250, TimeUnit.MILLISECONDS); if (task == null) continue;
                Completed completed = task.get(); received++;
                synchronized (this) {
                    if (current != run || run.cancelled.get()) return;
                    if (state.record(completed.index, completed.result)) listener.onResult(completed.index, completed.result);
                    long now = System.nanoTime();
                    if (now - progressAt > 400_000_000L || received == pending.size()) {
                        listener.onProgress(state.done(), state.total(), state.goodCount()); progressAt = now;
                    }
                }
                long now=System.nanoTime();
                if(now-savedAt>5_000_000_000L){run.persistence.save(state);savedAt=System.nanoTime();}
                if(received==batchEnd){run.persistence.save(state);savedAt=System.nanoTime();batchEnd=Math.min(pending.size(),batchEnd+ScanCheckpoint.BATCH_SIZE);while(submitted<Math.min(batchEnd,received+120)&&!run.cancelled.get())submit(tasks,run,probe,pending.get(submitted++));}
                else if (submitted < batchEnd && !run.cancelled.get()) submit(tasks, run, probe, pending.get(submitted++));
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
        } finally { run.close(); }
    }
    private static void submit(CompletionService<Completed> tasks, Run run, Probe probe, int index) {
        tasks.submit(() -> new Completed(index, probe.inspect(run.state.candidate(index), run.state.slow, run.cancelled, run.sockets)));
    }
}
