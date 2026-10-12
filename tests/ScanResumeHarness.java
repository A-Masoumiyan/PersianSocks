import ir.persiansocks.app.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.sun.net.httpserver.HttpServer;

public final class ScanResumeHarness {
    static int passed;
    static void check(boolean ok, String name) { if (!ok) throw new AssertionError(name); passed++; System.out.println("PASS: " + name); }
    static ProxyEndpoint verified(ProxyEndpoint p, long latency, long time) {
        return new ProxyEndpoint(p.host, p.port, latency, false, 7, 7, time).withType(p.type);
    }
    static Socks5Probe.Result good(ProxyEndpoint p) { return new Socks5Probe.Result(verified(p, 25, System.currentTimeMillis()), Socks5Probe.Failure.NONE, true, true); }
    static Socks5Probe.Result failed() { return new Socks5Probe.Result(null, Socks5Probe.Failure.TCP_REFUSED, false, false); }
    static final class Listen implements PreparedScanner.Listener {
        final CountDownLatch done = new CountDownLatch(1); final AtomicInteger results = new AtomicInteger(); volatile String message;
        public void onResult(int i, Socks5Probe.Result r) { results.incrementAndGet(); }
        public void onProgress(int d, int t, int g) {}
        public void onFinished(ScanCheckpoint s, String message) { this.message = message; done.countDown(); }
        void await() throws Exception { if (!done.await(8, TimeUnit.SECONDS)) throw new AssertionError("Batch did not finish"); }
    }
    static List<ProxyEndpoint> list(int count) { List<ProxyEndpoint> out = new ArrayList<>(); for (int i = 1; i <= count; i++) out.add(new ProxyEndpoint("8.8.8.8", i, -1, false)); return out; }
    public static void main(String[] args) throws Exception {
        ScanHarness.init(args[0]); ServiceTargets.configure(Collections.emptyList());
        File directory = Files.createTempDirectory("scan-checkpoint-test-").toFile(); ScanFiles files = new ScanFiles(directory);
        AtomicInteger downloads = new AtomicInteger(), accidentalProbes = new AtomicInteger();
        HttpServer source = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        StringBuilder published = new StringBuilder(); for (int i = 1; i <= 24000; i++) published.append("8.8.8.8:").append(i).append('\n');
        published.append("8.8.8.8:1\n192.168.1.1:99\n"); byte[] response = published.toString().getBytes("UTF-8");
        source.createContext("/list", exchange -> { downloads.incrementAndGet(); exchange.sendResponseHeaders(200, response.length); try (OutputStream out = exchange.getResponseBody()) { out.write(response); } }); source.start();
        Socks5Probe.setSocketMaker(() -> { accidentalProbes.incrementAndGet(); throw new IOException("Collection must not probe"); });
        ProxyScanner collector = new ProxyScanner(); CountDownLatch collected = new CountDownLatch(1); AtomicReference<List<ProxyEndpoint>> received = new AtomicReference<>();
        collector.collect(Collections.emptyList(), Collections.emptyList(), Collections.singletonList(new ProxyScanner.Source("fixture", "http://127.0.0.1:" + source.getAddress().getPort() + "/list", "")), new ProxyScanner.CollectionListener() {
            public void onProgress(String m) {}
            public void onFinished(List<ProxyEndpoint> p, String report) { received.set(p); collected.countDown(); }
            public void onFailed(String error) { throw new AssertionError(error); }
        });
        check(collected.await(8, TimeUnit.SECONDS), "List retrieval can complete independently of a scan");
        check(downloads.get() == 1 && accidentalProbes.get() == 0, "Receiving a published list opens no proxy-test socket");
        check(received.get().size() == 24000, "Retrieval preserves all unique public candidates beyond the old 20,000 limit");
        files.saveCatalog(received.get()); check(new ScanFiles(directory).catalog().size() == 24000, "The raw catalog survives reopening without tests");
        collector.stop(); source.stop(0); Socks5Probe.setSocketMaker(null);
        List<ProxyEndpoint> input = list(6); files.saveCatalog(input);
        ScanCheckpoint state = new ScanCheckpoint(input, new ProxyScanner.Options(100, true), "CATALOG");
        PreparedScanner runner = new PreparedScanner(); CountDownLatch twoDone = new CountDownLatch(2), blocked = new CountDownLatch(1);
        Listen pausedListener = new Listen();
        runner.start(state, files::save, (p, slow, cancel, sockets) -> {
            if (p.port == 2) return good(p); if (p.port == 3) return failed();
            blocked.countDown(); while (!cancel.get()) try { Thread.sleep(10); } catch (InterruptedException ignored) {}
            return new Socks5Probe.Result(null, Socks5Probe.Failure.CANCELLED, false, false);
        }, new PreparedScanner.Listener() {
            public void onResult(int i, Socks5Probe.Result r) { twoDone.countDown(); }
            public void onProgress(int d, int t, int g) {}
            public void onFinished(ScanCheckpoint c, String m) { pausedListener.onFinished(c, m); }
        });
        check(blocked.await(2, TimeUnit.SECONDS) && twoDone.await(2, TimeUnit.SECONDS), "Parallel results can finish after an earlier index is still running");
        ScanCheckpoint paused = runner.pause();
        check(paused.done() == 2 && !paused.complete() && paused.goodCount() == 1, "Pause records both completed success and failure, never cancelled work");
        check(paused.pending().equals(Arrays.asList(0, 3, 4, 5)), "Continuation retries unfinished indices and skips every completed out-of-order index");
        ScanCheckpoint restored = new ScanFiles(directory).checkpoint();
        check(restored.done() == 2 && restored.pending().equals(paused.pending()) && restored.slow, "Restart restores exact progress and the original timeout mode");
        List<ProxyEndpoint> previous = new ArrayList<>(); for (ProxyEndpoint p : input) previous.add(verified(p, 999, 1));
        ProxyEndpoint other = verified(new ProxyEndpoint("1.1.1.1", 80, -1, false), 800, 1); previous.add(other);
        List<ProxyEndpoint> partial = restored.merge(previous);
        check(partial.size() == 6 && partial.stream().noneMatch(p -> p.port == 3 && p.host.equals("8.8.8.8")), "Failed retests are removed while untested and unrelated healthy profiles stay available");
        check(partial.stream().anyMatch(p -> p.port == 2 && p.latencyMs == 25), "A completed retest replaces its cached ping");
        Map<Integer, AtomicInteger> resumedCalls = new ConcurrentHashMap<>(); Listen resumed = new Listen();
        runner.start(restored, files::save, (p, slow, cancel, sockets) -> { resumedCalls.computeIfAbsent(p.port, k -> new AtomicInteger()).incrementAndGet(); return good(p); }, resumed);
        resumed.await();
        check(restored.done() == 6 && restored.goodCount() == 5 && resumedCalls.size() == 4 && !resumedCalls.containsKey(2) && !resumedCalls.containsKey(3),
            "Resume tests only the remaining endpoints and keeps previous completed outcomes");
        check(downloads.get() == 1, "Resume performs no additional source download");
        check(new ScanFiles(directory).checkpoint().complete(), "Completed progress is durably saved");
        ScanCheckpoint fresh = new ScanCheckpoint(input, new ProxyScanner.Options(100, false), "CATALOG");
        check(fresh.done() == 0 && fresh.pending().size() == input.size(), "Starting from the beginning deliberately resets the whole batch");
        Listen restart = new Listen(); AtomicInteger calls = new AtomicInteger();
        runner.start(fresh, files::save, (p, slow, cancel, sockets) -> { calls.incrementAndGet(); return good(p); }, restart); restart.await();
        check(calls.get() == 6 && downloads.get() == 1, "Restart checks the full stored list without receiving it again");
        files.saveCatalog(Collections.singletonList(new ProxyEndpoint("9.9.9.9", 8080, -1, false)));
        check(files.catalog().size() == 1 && files.checkpoint().total() == 6, "Refreshing the catalog never changes a saved scan's frozen input list");
        Files.write(new File(directory, "scan-checkpoint.json.tmp").toPath(), "partial".getBytes("UTF-8"));
        check(files.checkpoint().complete(), "An interrupted temporary write cannot corrupt the last committed checkpoint");
        ProxyEndpoint later = verified(input.get(0), 7, System.currentTimeMillis() + 100000);
        check(fresh.merge(Collections.singletonList(later)).stream().anyMatch(p -> p.key().equals(later.key()) && p.latencyMs == 7),
            "Reopening an old checkpoint cannot overwrite a newer individual measurement");
        ProxyEndpoint equalStamp = fresh.good().get(0).withLatency(3);
        check(fresh.merge(Collections.singletonList(equalStamp)).stream().anyMatch(p -> p.key().equals(equalStamp.key()) && p.latencyMs == 3),
            "A ping-only retest is retained even though service verification time is unchanged");
        ProxyEndpoint recovered = verified(input.get(2), 9, System.currentTimeMillis() + 100000);
        check(paused.merge(Collections.singletonList(recovered)).stream().anyMatch(p -> p.key().equals(recovered.key())),
            "An earlier failed scan cannot remove a profile verified successfully afterward");
        List<ProxyEndpoint> healthy = list(135); ScanCheckpoint recheck = new ScanCheckpoint(healthy, new ProxyScanner.Options(100, false, true, 7), "HEALTHY");
        check(recheck.total() == 135, "Retesting all healthy profiles ignores a smaller numeric scan budget");
        Listen allHealthy = new Listen();
        runner.start(recheck, files::save, (p, slow, cancel, sockets) -> p.port > 133 ? failed() : good(p), allHealthy); allHealthy.await();
        check(recheck.done() == 135 && recheck.goodCount() == 133, "Every healthy profile gets a new test and two newly failed profiles are excluded");
        List<ProxyEndpoint> oldHealthy = new ArrayList<>(); for (ProxyEndpoint p : healthy) oldHealthy.add(verified(p, 2000, 1));
        List<ProxyEndpoint> updated = recheck.merge(oldHealthy);
        check(updated.size() == 133 && updated.stream().allMatch(p -> p.latencyMs == 25), "The refreshed healthy list consists of new ping values, not stale cache entries");
        List<ProxyEndpoint> protocols = Arrays.asList(input.get(0), input.get(0).withType(ProxyEndpoint.Type.HTTP), input.get(0).withType(ProxyEndpoint.Type.HTTPS));
        check(new ScanCheckpoint(protocols, new ProxyScanner.Options(100, false, false, 6), "CATALOG").total() == 2,
            "New scans honor selected proxy types while retaining protocol-specific identities");
        ServiceTargets.configure(Collections.singletonList(ServiceTargets.custom("فیسبوک", "www.facebook.com")));
        check(!fresh.compatible(), "Changing test domains invalidates continuation of an older target set"); ServiceTargets.configure(Collections.emptyList());
        DistributionPolicy.setUserTlsOnly(true); check(!fresh.compatible(), "Enabling TLS-only invalidates a prior unrestricted checkpoint");
        check(new ScanCheckpoint(protocols, new ProxyScanner.Options(100, false, true, 7), "CATALOG").total() == 1, "TLS-only new scans retain only encrypted proxy candidates");
        DistributionPolicy.setUserTlsOnly(false);
        ScanCheckpoint canceled = new ScanCheckpoint(input, new ProxyScanner.Options(100, false), "CATALOG");
        check(!canceled.record(0, new Socks5Probe.Result(null, Socks5Probe.Failure.CANCELLED, false, false)) && canceled.done() == 0,
            "A cancelled probe can never advance the saved completion mask");
        Listen storageFailure = new Listen(); AtomicInteger unsafeCalls = new AtomicInteger();
        runner.start(canceled, c -> { throw new IOException("storage fixture"); }, (p, slow, cancel, sockets) -> { unsafeCalls.incrementAndGet(); return good(p); }, storageFailure);
        storageFailure.await(); check(unsafeCalls.get() == 0 && storageFailure.message.contains("storage fixture"), "A checkpoint storage failure is visible and cannot silently start an unsaved batch");
        try (ScanHarness.Mock slow = new ScanHarness.Mock("stall")) {
            ScanCheckpoint real = new ScanCheckpoint(Collections.singletonList(slow.endpoint()), new ProxyScanner.Options(100, true), "CATALOG");
            runner.start(real, files::save, Socket::new, InetAddress::getByName, new Listen());
            check(slow.connected.await(2, TimeUnit.SECONDS), "A prepared scan uses a real independent proxy socket");
            runner.pause(); check(real.done() == 0 && real.pending().size() == 1, "Closing a real blocked proxy handshake leaves it pending for continuation");
        }
        runner.pause();
        System.out.println("RESULT: " + passed + " checks passed");
    }
}
