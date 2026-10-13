import ir.persiansocks.app.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.*;

/** Real local proxy sockets and TLS, not an in-memory fake download. */
public final class SingleProxyHarness {
    static int passed;
    static void check(boolean ok, String name) { if (!ok) throw new AssertionError(name); passed++; System.out.println("PASS: " + name); }
    static final class Proxy implements AutoCloseable {
        final ProxyEndpoint.Type type; final String mode; final int size;
        final ServerSocket server = new ServerSocket(0, 32, InetAddress.getLoopbackAddress());
        final ExecutorService pool = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r); t.setDaemon(true); return t; });
        final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        final AtomicInteger accepted = new AtomicInteger(), downloads = new AtomicInteger();
        final Set<String> destinations = ConcurrentHashMap.newKeySet();
        final List<String> requests = new CopyOnWriteArrayList<>();
        final CountDownLatch waiting = new CountDownLatch(1), gone = new CountDownLatch(1);
        Proxy(ProxyEndpoint.Type type, String mode, int size) throws Exception {
            this.type = type; this.mode = mode; this.size = size;
            pool.submit(() -> { while (!server.isClosed()) try {
                Socket raw = server.accept(); accepted.incrementAndGet(); sockets.add(raw); pool.submit(() -> serve(raw));
            } catch (Exception ex) { break; } });
        }
        ProxyEndpoint endpoint() { return new ProxyEndpoint("127.0.0.1", server.getLocalPort(), 500, false, 7, 7, 100).withType(type); }
        void serve(Socket raw) {
            try (Socket owner = raw) {
                raw.setSoTimeout(5000); Socket socket = raw;
                if (mode.equals("stall-proxy")) { waiting.countDown(); while (raw.getInputStream().read() != -1) {} return; }
                if (type == ProxyEndpoint.Type.HTTPS) {
                    SSLSocket proxyTls = (SSLSocket) ScanHarness.tls.getSocketFactory().createSocket(raw, "127.0.0.1", server.getLocalPort(), true);
                    proxyTls.setUseClientMode(false); proxyTls.startHandshake(); socket = proxyTls;
                }
                InputStream in = socket.getInputStream(); OutputStream out = socket.getOutputStream(); String host;
                if (type == ProxyEndpoint.Type.SOCKS5) {
                    if (in.readNBytes(3).length != 3) return;
                    out.write(new byte[]{5, 0}); out.flush();
                    if (in.read() != 5) return; int command = in.read(); in.read(); int addressType = in.read();
                    if (addressType != 3) return;
                    host = new String(in.readNBytes(in.read()), "US-ASCII"); in.readNBytes(2);
                    out.write(new byte[]{5, 0, 0, 1, 127, 0, 0, 1, 0, 80}); out.flush();
                    if (command == 3) return;
                } else {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(in, "US-ASCII"));
                    String first = reader.readLine(); if (first == null || !first.startsWith("CONNECT ")) return;
                    host = first.split(" ")[1].split(":")[0];
                    while (!reader.readLine().isEmpty()) {}
                    if (mode.equals("auth")) { out.write("HTTP/1.1 407 Auth\r\n\r\n".getBytes("US-ASCII")); out.flush(); return; }
                    out.write("HTTP/1.1 200 Established\r\n\r\n".getBytes("US-ASCII")); out.flush();
                }
                destinations.add(host);
                try (SSLSocket target = (SSLSocket) ScanHarness.tls.getSocketFactory().createSocket(socket, host, 443, true)) {
                    target.setUseClientMode(false); target.startHandshake();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(target.getInputStream(), "US-ASCII"));
                    String first = reader.readLine(); if (first == null) return; requests.add(first);
                    while (!reader.readLine().isEmpty()) {}
                    boolean speed = host.equals("speed.cloudflare.com") || first.startsWith("GET /fixture.bin"); if (speed) downloads.incrementAndGet();
                    int count = speed ? size : 0;
                    String status = mode.equals("redirect") ? "302 Found" : mode.equals("denied") ? "403 Denied" : "200 OK";
                    String framing = mode.equals("chunked") || mode.equals("bad-chunk") ? "Transfer-Encoding: chunked" :
                        "Content-Length: " + (mode.equals("truncated") ? count + 50 : count);
                    String extra = mode.equals("gzip") ? "Content-Encoding: gzip\r\n" : mode.equals("html") ? "Content-Type: text/html\r\n" : "";
                    if (mode.equals("redirect")) extra += "Location: https://www.youtube.com/\r\n";
                    if (mode.equals("conflict")) extra += "Content-Length: 99\r\n";
                    // Ordinary repeated response headers are legal and do not affect body framing.
                    extra += "Set-Cookie: first=1\r\nSet-Cookie: second=2\r\n";
                    OutputStream body = target.getOutputStream();
                    body.write(("HTTP/1.1 " + status + "\r\n" + framing + "\r\n" + extra + "\r\n").getBytes("US-ASCII")); body.flush();
                    if (mode.equals("stall-body")) { waiting.countDown(); while (target.getInputStream().read() != -1) {} return; }
                    if (mode.equals("bad-chunk")) { body.write("not-hex\r\n".getBytes("US-ASCII")); body.flush(); return; }
                    byte[] block = new byte[8192]; Arrays.fill(block, (byte) 65);
                    for (int done = 0; done < count;) {
                        int n = Math.min(block.length, count - done);
                        if (mode.equals("chunked")) body.write((Integer.toHexString(n) + ";x=1\r\n").getBytes("US-ASCII"));
                        body.write(block, 0, n);
                        if (mode.equals("chunked")) body.write("\r\n".getBytes("US-ASCII"));
                        done += n;
                    }
                    if (mode.equals("chunked")) body.write("0\r\nX-Test: done\r\n\r\n".getBytes("US-ASCII"));
                    body.flush();
                }
            } catch (Exception ignored) {} finally { sockets.remove(raw); gone.countDown(); }
        }
        @Override public void close() throws Exception { server.close(); for (Socket socket : sockets) socket.close(); pool.shutdownNow(); }
    }
    static final class Outcome { long bytes; double mbps; String error; }
    static Outcome download(ProxyEndpoint proxy, Socks5Probe.Resolver resolver) {
        Outcome result = new Outcome();
        new DownloadSpeedTest().run(new DownloadSpeedTest.Transport() {
            public HttpURLConnection open(URL url) { return new ProxyHttpConnection(url, proxy, Socket::new, resolver); }
            public boolean valid() { return true; }
        }, new DownloadSpeedTest.Listener() {
            public void progress(long bytes, double speed) {}
            public void finished(long bytes, double speed, String error) { result.bytes = bytes; result.mbps = speed; result.error = error; }
        });
        return result;
    }
    public static void main(String[] args) throws Exception {
        ScanHarness.init(args[0]); ServiceTargets.configure(Collections.emptyList());
        for (ProxyEndpoint.Type type : ProxyEndpoint.Type.values()) try (Proxy selected = new Proxy(type, "valid", 256000);
                Proxy unused = new Proxy(type, "valid", 999)) {
            Outcome result = download(selected.endpoint(), InetAddress::getByName);
            check(result.error.isEmpty() && result.bytes == 256000 && result.mbps > 0, "Real download succeeds over selected " + type + " with no VPN");
            check(selected.downloads.get() == 1 && unused.accepted.get() == 0, "Only the selected " + type + " is contacted, never another candidate");
            check(selected.destinations.equals(Collections.singleton("speed.cloudflare.com")) &&
                selected.requests.get(0).startsWith("GET /__down?bytes=20000000&ts="), "Download target and uncached byte budget traverse " + type);
            SingleProxyTest connection = new SingleProxyTest();
            Socks5Probe.setSocketMaker(() -> { throw new IOException("Global scanner socket must not be used"); });
            Socks5Probe.setResolver(h -> { throw new IOException("Global VPN resolver must not be used"); });
            Socks5Probe.Result fresh = connection.connection(selected.endpoint(), false, Socket::new, InetAddress::getByName);
            check(fresh.endpoint != null && fresh.endpoint.full() && fresh.endpoint.type == type && unused.accepted.get() == 0,
                "Independent connection test verifies only one " + type + " and does not mutate scanner routing");
            int before = selected.accepted.get();
            Socks5Probe.Result ping = new SingleProxyTest().latency(fresh.endpoint, ServiceTargets.ALL[1], false, Socket::new, InetAddress::getByName);
            check(ping.endpoint != null && ping.endpoint.latencyMs > 0 && selected.accepted.get() == before + 1,
                "Latency retest over " + type + " sends one HTTPS request, not an all-service or all-proxy scan");
            ProxyEndpoint updated = fresh.endpoint.withLatency(ping.endpoint.latencyMs);
            check(updated.full() && updated.checkedMask == 7 && updated.testedAt == fresh.endpoint.testedAt && updated.key().equals(fresh.endpoint.key()),
                "Latency refresh preserves service checks and profile identity for " + type);
            Socks5Probe.setSocketMaker(null); Socks5Probe.setResolver(null);
        }
        try (Proxy chunked = new Proxy(ProxyEndpoint.Type.HTTP, "chunked", 135791)) {
            Outcome result = download(chunked.endpoint(), InetAddress::getByName);
            check(result.error.isEmpty() && result.bytes == 135791, "Chunked TLS download counts payload only, excluding chunk metadata and trailers");
        }
        for (String mode : Arrays.asList("auth", "redirect", "denied", "gzip", "bad-chunk", "truncated", "conflict"))
            try (Proxy rejected = new Proxy(ProxyEndpoint.Type.HTTP, mode, 1000)) {
                Outcome result = download(rejected.endpoint(), InetAddress::getByName);
                check(!result.error.isEmpty(), "Reject invalid speed-test response: " + mode);
                if (mode.equals("redirect")) check(rejected.destinations.size() == 1, "A download redirect never creates a direct or second-proxy request");
            }
        try (Proxy wrong = new Proxy(ProxyEndpoint.Type.HTTPS, "valid", 1000)) {
            ProxyEndpoint badName = new ProxyEndpoint("wrong.example.org", wrong.server.getLocalPort(), -1, false).withType(ProxyEndpoint.Type.HTTPS);
            Outcome result = download(badName, h -> InetAddress.getLoopbackAddress());
            check(!result.error.isEmpty() && wrong.downloads.get() == 0, "TLS-proxy hostname failure prevents a download and has no fallback");
        }
        for (String mode : Arrays.asList("stall-proxy", "stall-body")) try (Proxy stalled = new Proxy(ProxyEndpoint.Type.SOCKS5, mode, 50000)) {
            DownloadSpeedTest test = new DownloadSpeedTest(); CountDownLatch done = new CountDownLatch(1); AtomicReference<String> error = new AtomicReference<>();
            new Thread(() -> test.run(new DownloadSpeedTest.Transport() {
                public HttpURLConnection open(URL url) { return new ProxyHttpConnection(url, stalled.endpoint(), Socket::new, InetAddress::getByName); }
                public boolean valid() { return true; }
            }, new DownloadSpeedTest.Listener() {
                public void progress(long b, double m) {}
                public void finished(long b, double m, String e) { error.set(e); done.countDown(); }
            })).start();
            check(stalled.waiting.await(3, TimeUnit.SECONDS), "Real speed test reaches " + mode);
            test.cancel();
            check(done.await(2, TimeUnit.SECONDS) && "تست متوقف شد".equals(error.get()), "Stop immediately closes blocked selected-proxy socket at " + mode);
        }
        try (Proxy stalled = new Proxy(ProxyEndpoint.Type.SOCKS5, "stall-proxy", 0)) {
            SingleProxyTest test = new SingleProxyTest(); ExecutorService worker = Executors.newSingleThreadExecutor();
            Future<Socks5Probe.Result> result = worker.submit(() -> test.connection(stalled.endpoint(), true, Socket::new, InetAddress::getByName));
            check(stalled.waiting.await(2, TimeUnit.SECONDS), "One-proxy connection test reaches a blocked handshake");
            test.cancel(); check(result.get(2, TimeUnit.SECONDS).failure == Socks5Probe.Failure.CANCELLED, "Stopping connection test closes only its own sockets");
            worker.shutdownNow();
        }
        ServiceTargets.Target custom = ServiceTargets.custom("فیسبوک", "www.facebook.com"); ServiceTargets.configure(Arrays.asList(custom));
        ProxyEndpoint onlyCustom = new ProxyEndpoint("8.8.8.8", 1080, 100, false, 0, 7, 20,
            Collections.singleton(custom.id), Collections.singleton(custom.id), "US");
        check(SingleProxyTest.latencyTarget(onlyCustom, custom.id) == custom && SingleProxyTest.latencyTarget(onlyCustom, "ALL") == custom,
            "Custom-only verified proxies retest their working service instead of an unrelated built-in");
        ServiceTargets.configure(Collections.emptyList());
        check(SingleProxyTest.latencyTarget(new ProxyEndpoint("8.8.8.8", 1080, 100, false, 7, 7, 20), "YOUTUBE") == ServiceTargets.ALL[1],
            "Latency measurement prefers the chosen service when it was verified");
        SSLContext defaultTrust = SSLContext.getInstance("TLS"); defaultTrust.init(null, null, null); SSLContext.setDefault(defaultTrust);
        try (Proxy untrusted = new Proxy(ProxyEndpoint.Type.HTTP, "valid", 1000)) {
            Outcome result = download(untrusted.endpoint(), InetAddress::getByName);
            check(!result.error.isEmpty() && untrusted.downloads.get() == 0, "Untrusted destination TLS is rejected even behind an HTTP proxy");
        }
        System.out.println("RESULT: " + passed + " checks passed");
    }
}
