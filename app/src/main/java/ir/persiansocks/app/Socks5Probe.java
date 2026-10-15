package ir.persiansocks.app;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.HashSet;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.*;

/** A SOCKS reply alone is never counted as a working proxy. */
public final class Socks5Probe {
    public enum Failure {
        NONE, INSECURE_PROXY, TCP_TIMEOUT, TCP_REFUSED, SOCKS_TIMEOUT, SOCKS_AUTH, SOCKS_PROTOCOL,
        DESTINATION_REJECTED, HTTPS_TIMEOUT, TLS_FAILED, HTTP_FAILED, IO_ERROR, CANCELLED
    }
    public static final class Result {
        public final ProxyEndpoint endpoint;
        public final Failure failure;
        public final boolean tcpReached, socksReached;
        public Result(ProxyEndpoint e, Failure f, boolean tcp, boolean socks) {
            endpoint=e; failure=f; tcpReached=tcp; socksReached=socks;
        }
    }
    private static final ScheduledThreadPoolExecutor DEADLINES = new ScheduledThreadPoolExecutor(1, r -> {
        Thread t = new Thread(r, "socks-probe-deadlines"); t.setDaemon(true); return t;
    });
    static { DEADLINES.setRemoveOnCancelPolicy(true); }
    public interface SocketMaker { Socket create() throws Exception; default boolean valid(){return true;} default DatagramSocket datagram() throws Exception {throw new IOException("UDP route unavailable");} }
    public interface Resolver { InetAddress resolve(String host) throws Exception; }
    private static volatile Resolver resolver=InetAddress::getByName;
    public static void setResolver(Resolver r){resolver=r==null?InetAddress::getByName:r;}
    private static volatile SocketMaker socketMaker = Socket::new;
    public static void setSocketMaker(SocketMaker maker) { socketMaker = maker == null ? Socket::new : maker; }
    private Socks5Probe() {}

    public static ProxyEndpoint check(ProxyEndpoint e) { return inspect(e, false, new AtomicBoolean(false), null).endpoint; }
    public static Result inspect(ProxyEndpoint e, boolean slow, AtomicBoolean cancelled, Set<Socket> active) {
        return inspect(e, slow, cancelled, active, socketMaker, resolver);
    }
    public static Result inspect(ProxyEndpoint e, boolean slow, AtomicBoolean cancelled, Set<Socket> active,
            SocketMaker maker, Resolver dns) {
        return inspect(e,slow,cancelled,active,maker,dns,null);
    }
    public static Result inspect(ProxyEndpoint e,boolean slow,AtomicBoolean cancelled,Set<Socket> active,
            SocketMaker maker,Resolver dns,ServiceTargets.Target measurementTarget) {
        if(!DistributionPolicy.allows(e))return new Result(null,Failure.INSECURE_PROXY,false,false);
        Result last = new Result(null, Failure.IO_ERROR, false, false);
        int passed = 0, checked = 0;
        Set<String> extraPassed = new HashSet<>(), extraChecked = new HashSet<>();
        long latency = Long.MAX_VALUE;
        boolean tcp = false, socks = false;Result measured=null;Map<String,Long> timings=new HashMap<>();
        for (ServiceTargets.Target target : ServiceTargets.current()) {
            if (cancelled.get()) return new Result(null, Failure.CANCELLED, tcp, socks);
            checked |= target.bit;
            if (target.bit == 0) extraChecked.add(target.id);
            last = attempt(e, target, slow, cancelled, active, maker, dns);
            tcp |= last.tcpReached; socks |= last.socksReached;
            if(measurementTarget!=null&&target.url().equals(measurementTarget.url()))measured=last;
            if (last.endpoint != null) {
                passed |= target.bit; latency = Math.min(latency, last.endpoint.latencyMs);timings.put(target.url(),last.endpoint.latencyMs);
                if (target.bit == 0) extraPassed.add(target.id);
            } else if (!last.socksReached && passed == 0 && extraPassed.isEmpty()) break;
        }
        if ((passed == 0 && extraPassed.isEmpty()) || cancelled.get())
            return new Result(null, cancelled.get() ? Failure.CANCELLED : last.failure, tcp, socks);
        if(measured!=null&&measured.endpoint!=null)latency=measured.endpoint.latencyMs;
        boolean udp = e.type==ProxyEndpoint.Type.SOCKS5 && checkUdp(e, cancelled, active, maker, dns);
        if (cancelled.get()) return new Result(null, Failure.CANCELLED, tcp, socks);
        return new Result(new ProxyEndpoint(e.host, e.port, latency, udp, passed, checked, System.currentTimeMillis(),
            extraPassed, extraChecked, CountryLookup.lookup(e.host),e.type).withLatency(latency,measured==null||measured.endpoint==null?"":measurementTarget.url()).withServiceLatencies(timings),
            Failure.NONE, tcp, socks);
    }
    public static Result inspectTarget(ProxyEndpoint e, ServiceTargets.Target target, boolean slow,
            AtomicBoolean cancelled, Set<Socket> active, SocketMaker maker, Resolver dns) {
        if (!DistributionPolicy.allows(e)) return new Result(null, Failure.INSECURE_PROXY, false, false);
        return attempt(e, target, slow, cancelled, active, maker, dns);
    }
    private static Result attempt(ProxyEndpoint e, ServiceTargets.Target target, boolean slow,
            AtomicBoolean cancelled, Set<Socket> active, SocketMaker maker, Resolver dns) {
        AdaptiveScanPolicy.Budget budget=AdaptiveScanPolicy.current(slow);
        int connectTimeout = budget.connectMs;
        int readTimeout = budget.readMs;
        long start = System.nanoTime();
        boolean tcp = false, socks = false;
        int phase = 0;
        Socket s;
        try { s = maker.create(); }
        catch (Exception ex) { return new Result(null, Failure.IO_ERROR, false, false); }
        AtomicBoolean expired = new AtomicBoolean(false);
        if (active != null) active.add(s);
        ScheduledFuture<?> timeout = DEADLINES.schedule(() -> { expired.set(true); close(s); }, budget.totalMs, TimeUnit.MILLISECONDS);
        try (Socket owned = s) {
            if (cancelled.get()) return new Result(null, Failure.CANCELLED, false, false);
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress(dns.resolve(e.host), e.port), connectTimeout);
            tcp = true; phase = 1; s.setSoTimeout(readTimeout);
            Socket tunnel;
            try { tunnel=ProxyTransport.connect(s,e,target.host,443,readTimeout); socks=true; }
            catch(ProxyTransport.Rejected ex){return new Result(null,ex.auth?Failure.SOCKS_AUTH:ex.protocol?Failure.SOCKS_PROTOCOL:Failure.DESTINATION_REJECTED,true,!ex.auth&&!ex.protocol);}
            phase = 2;
            try { ServiceTargets.exchange(tunnel, target, readTimeout); }
            catch (SSLException | SocketTimeoutException ex) { throw ex; }
            catch (IOException ex) { return new Result(null, Failure.HTTP_FAILED, true, true); }
            long elapsed = Math.max(1, (System.nanoTime()-start)/1_000_000);
            return new Result(new ProxyEndpoint(e.host,e.port,elapsed,false).withType(e.type), Failure.NONE, true, true);
        } catch (SocketTimeoutException ex) {
            return new Result(null, phase==0 ? Failure.TCP_TIMEOUT : phase==1 ? Failure.SOCKS_TIMEOUT : Failure.HTTPS_TIMEOUT, tcp, socks);
        } catch (ConnectException ex) {
            return new Result(null, Failure.TCP_REFUSED, tcp, socks);
        } catch (SSLException ex) {
            return new Result(null, Failure.TLS_FAILED, tcp, socks);
        } catch (Exception ex) {
            Failure failure = cancelled.get() ? Failure.CANCELLED : expired.get() ?
                (phase==0 ? Failure.TCP_TIMEOUT : phase==1 ? Failure.SOCKS_TIMEOUT : Failure.HTTPS_TIMEOUT) : Failure.IO_ERROR;
            return new Result(null, failure, tcp, socks);
        } finally {
            timeout.cancel(false); if(active!=null) active.remove(s);
        }
    }
    private static boolean readReply(InputStream in) throws Exception {
        int v=in.read(), reply=in.read(), reserved=in.read(), type=in.read();
        if(v!=5 || reply!=0 || reserved!=0) return false;
        int n=type==1?4:type==4?16:type==3?in.read():-1;
        if(n<1) return false;
        for(int i=0;i<n+2;i++) if(in.read()<0) return false;
        return true;
    }
    static boolean checkUdp(ProxyEndpoint e, AtomicBoolean cancelled, Set<Socket> active,
            SocketMaker maker, Resolver dns) {
        Socket s; DatagramSocket udp;
        try { udp=maker.datagram();try{s=maker.create();}catch(Exception e1){udp.close();throw e1;} } catch(Exception ex) { return false; }
        if(active!=null) active.add(s);
        ScheduledFuture<?> timeout=DEADLINES.schedule(() -> {close(s);udp.close();},3500,TimeUnit.MILLISECONDS);
        try(Socket owned=s;DatagramSocket ownedUdp=udp) {
            if(cancelled.get()) return false;
            s.connect(new InetSocketAddress(dns.resolve(e.host),e.port),900); s.setSoTimeout(900);
            InputStream in=s.getInputStream(); OutputStream out=s.getOutputStream();
            out.write(new byte[]{5,1,0}); out.flush();
            if(in.read()!=5 || in.read()!=0) return false;
            out.write(new byte[]{5,3,0,1,0,0,0,0,0,0}); out.flush();
            InetSocketAddress relay=UdpRoundTrip.relay(in,s.getInetAddress(),dns);
            return UdpRoundTrip.exchange(udp,relay,cancelled);
        } catch(Exception ex) { return false; }
        finally { timeout.cancel(false); if(active!=null) active.remove(s); }
    }
    private static void close(Socket s) { try { s.close(); } catch(Exception ignored) {} }
    public static String describe(Failure f) {
        switch(f) {
            case INSECURE_PROXY: return "این نسخه فقط پروکسی با TLS معتبر را می‌پذیرد";
            case TCP_TIMEOUT: return "مهلت اتصال TCP تمام شد";
            case TCP_REFUSED: return "اتصال TCP رد شد";
            case SOCKS_TIMEOUT: return "پاسخ پروکسی نرسید";
            case SOCKS_AUTH: return "نیاز به نام کاربری یا رمز";
            case SOCKS_PROTOCOL: return "پروتکل پروکسی معتبر نیست";
            case DESTINATION_REJECTED: return "پروکسی مقصد را نپذیرفت";
            case HTTPS_TIMEOUT: return "مهلت HTTPS تمام شد";
            case TLS_FAILED: return "خطای TLS یا گواهی";
            case HTTP_FAILED: return "پاسخ HTTPS معتبر نبود";
            case CANCELLED: return "متوقف شد";
            case NONE: return "HTTPS تأیید شد";
            default: return "خطای ارتباط یا قطع اتصال";
        }
    }
}
