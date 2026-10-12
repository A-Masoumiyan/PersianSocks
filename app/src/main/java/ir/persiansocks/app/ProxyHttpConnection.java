package ir.persiansocks.app;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.net.ssl.*;

/** An HTTPS download using exactly one SOCKS5/HTTP/HTTPS endpoint, with no direct fallback. */
public final class ProxyHttpConnection extends HttpURLConnection {
    private final ProxyEndpoint proxy;
    private final Socks5Probe.SocketMaker maker;
    private final Socks5Probe.Resolver resolver;
    private final Map<String, String> headers = new HashMap<>();
    private volatile Socket raw, tunnel;
    private volatile SSLSocket tls;
    private volatile boolean closed;
    private InputStream body;
    public ProxyHttpConnection(URL url, ProxyEndpoint proxy, Socks5Probe.SocketMaker maker, Socks5Probe.Resolver resolver) {
        super(url); this.proxy = proxy; this.maker = maker; this.resolver = resolver;
    }
    @Override public boolean usingProxy() { return true; }
    @Override public void disconnect() {
        closed = true;
        close(raw); close(tls); if (tunnel != raw) close(tunnel);
    }
    private static void close(Socket socket) { if (socket != null) try { socket.close(); } catch (Exception ignored) {} }
    private void ready() throws IOException { if (closed) throw new IOException("تست متوقف شد"); }
    @Override public void connect() throws IOException {
        if (connected) { ready(); return; }
        ready(); DistributionPolicy.require(proxy);
        if (!url.getProtocol().equals("https") || url.getUserInfo() != null ||
            (url.getPort() != -1 && url.getPort() != 443)) throw new IOException("مقصد تست باید HTTPS معتبر باشد");
        String host = url.getHost(), path = url.getFile().isEmpty() ? "/" : url.getFile();
        if (!host.matches("[A-Za-z0-9.-]+") || path.matches(".*[\\r\\n\\x00-\\x20].*"))
            throw new IOException("نشانی تست معتبر نیست");
        try {
            raw = maker.create(); ready();
            raw.connect(new InetSocketAddress(resolver.resolve(proxy.host), proxy.port), getConnectTimeout()); ready();
            tunnel = ProxyTransport.connect(raw, proxy, host, 443, getReadTimeout()); ready();
            tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(tunnel, host, 443, true);
            ready(); tls.setSoTimeout(getReadTimeout()); DistributionPolicy.configureTls(tls); tls.startHandshake();
            StringBuilder request = new StringBuilder("GET ").append(path).append(" HTTP/1.1\r\nHost: ").append(host).append("\r\n");
            for (String name : new String[]{"User-Agent", "Accept-Encoding", "Cache-Control"}) {
                String value = getRequestProperty(name);
                if (value != null) {
                    if (value.matches(".*[\\r\\n].*")) throw new IOException("Invalid request header");
                    request.append(name).append(": ").append(value).append("\r\n");
                }
            }
            request.append("Connection: close\r\n\r\n");
            tls.getOutputStream().write(request.toString().getBytes(StandardCharsets.US_ASCII)); tls.getOutputStream().flush();
            InputStream in = new BufferedInputStream(tls.getInputStream(), 16384);
            String status = line(in, 512);
            if (!status.matches("HTTP/1\\.[01] [0-9]{3}( .*)?")) throw new IOException("پاسخ HTTP معتبر نیست");
            responseCode = Integer.parseInt(status.substring(9, 12));
            int bytes = 0; boolean complete = false;
            for (int count = 0; count < 100; count++) {
                String value = line(in, 8192); bytes += value.length();
                if (bytes > 32768) throw new IOException("HTTP headers too large");
                if (value.isEmpty()) { complete = true; break; }
                int colon = value.indexOf(':'); if (colon <= 0) throw new IOException("Invalid response header");
                String name = value.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String content = value.substring(colon + 1).trim();
                if ((name.equals("content-length") || name.equals("transfer-encoding") || name.equals("content-encoding")) &&
                    headers.containsKey(name) && !headers.get(name).equals(content)) throw new IOException("Conflicting response headers");
                headers.put(name, content);
            }
            if (!complete) throw new IOException("Incomplete HTTP headers");
            String transfer = headers.get("transfer-encoding"), length = headers.get("content-length");
            if (transfer != null) {
                if (!transfer.equalsIgnoreCase("chunked") || length != null) throw new IOException("Invalid HTTP transfer framing");
                body = new Chunked(in);
            } else if (length != null) {
                long size;
                try { size = Long.parseLong(length); } catch (NumberFormatException ex) { throw new IOException("Invalid content length"); }
                if (size < 0) throw new IOException("Invalid content length");
                body = new Limited(in, size);
            } else body = in;
            ready(); connected = true;
        } catch (Exception ex) {
            disconnect();
            if (ex instanceof IOException) throw (IOException) ex;
            throw new IOException("خطای تست پروکسی: " + ex.getMessage(), ex);
        }
    }
    @Override public int getResponseCode() throws IOException { connect(); return responseCode; }
    @Override public String getContentEncoding() { return headers.get("content-encoding"); }
    @Override public String getHeaderField(String name) { return name == null ? null : headers.get(name.toLowerCase(Locale.ROOT)); }
    @Override public InputStream getInputStream() throws IOException { connect(); return body; }
    private static String line(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        while (bytes.size() < limit) {
            int c = in.read(); if (c < 0) throw new EOFException("Incomplete HTTP response");
            if (c == '\n') {
                byte[] data = bytes.toByteArray();
                if (data.length == 0 || data[data.length - 1] != '\r') throw new IOException("Invalid HTTP line ending");
                return new String(data, 0, data.length - 1, StandardCharsets.US_ASCII);
            }
            bytes.write(c);
        }
        throw new IOException("HTTP line too long");
    }
    private static final class Limited extends InputStream {
        private final InputStream in; private long remaining;
        Limited(InputStream in, long remaining) { this.in = in; this.remaining = remaining; }
        @Override public int read() throws IOException { byte[] b = new byte[1]; return read(b, 0, 1) < 0 ? -1 : b[0] & 255; }
        @Override public int read(byte[] b, int off, int count) throws IOException {
            if (count == 0) return 0; if (remaining == 0) return -1;
            int n = in.read(b, off, (int) Math.min(count, remaining));
            if (n < 0) throw new EOFException("بدنهٔ دانلود ناقص است");
            remaining -= n; return n;
        }
    }
    private static final class Chunked extends InputStream {
        private final InputStream in; private long remaining; private boolean delimiter, ended;
        Chunked(InputStream in) { this.in = in; }
        @Override public int read() throws IOException { byte[] b = new byte[1]; return read(b, 0, 1) < 0 ? -1 : b[0] & 255; }
        @Override public int read(byte[] b, int off, int count) throws IOException {
            if (count == 0) return 0; if (ended) return -1;
            if (remaining == 0) {
                if (delimiter && (in.read() != '\r' || in.read() != '\n')) throw new IOException("Invalid chunk delimiter");
                String size = line(in, 1024).split(";", 2)[0].trim();
                if (!size.matches("[0-9a-fA-F]+")) throw new IOException("Invalid chunk size");
                try { remaining = Long.parseLong(size, 16); } catch (NumberFormatException ex) { throw new IOException("Invalid chunk size"); }
                if (remaining == 0) {
                    int bytes = 0;
                    for (int i = 0; i < 100; i++) {
                        String trailer = line(in, 8192); bytes += trailer.length();
                        if (bytes > 32768) throw new IOException("Chunk trailers too long");
                        if (trailer.isEmpty()) { ended = true; return -1; }
                    }
                    throw new IOException("Incomplete chunk trailer");
                }
                delimiter = true;
            }
            int n = in.read(b, off, (int) Math.min(count, remaining));
            if (n < 0) throw new EOFException("بدنهٔ دانلود ناقص است");
            remaining -= n; return n;
        }
    }
}
