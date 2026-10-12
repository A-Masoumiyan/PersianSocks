package main

import (
	"bufio"
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type fixtureOptions struct {
	Target      string
	Connections *atomic.Int32
}

func proxyServer(t *testing.T, typ string, cert tls.Certificate, delay time.Duration, options ...fixtureOptions) (Endpoint, func()) {
	t.Helper()
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	if typ == "HTTPS" {
		l = tls.NewListener(l, &tls.Config{Certificates: []tls.Certificate{cert}})
	}
	var active atomic.Int32
	go func() {
		for {
			c, err := l.Accept()
			if err != nil {
				return
			}
			active.Add(1)
			if len(options) > 0 && options[0].Connections != nil {
				options[0].Connections.Add(1)
			}
			go func() {
				defer active.Add(-1)
				defer c.Close()
				c.SetDeadline(time.Now().Add(5 * time.Second))
				time.Sleep(delay)
				target := ""
				if typ == "SOCKS5" {
					b, err := exact(c, 3)
					if err != nil || b[0] != 5 {
						return
					}
					c.Write([]byte{5, 0})
					b, err = exact(c, 4)
					if err != nil || b[1] != 1 {
						return
					}
					var host string
					switch b[3] {
					case 1:
						ip, er := exact(c, 4)
						if er != nil {
							return
						}
						host = net.IP(ip).String()
					case 4:
						ip, er := exact(c, 16)
						if er != nil {
							return
						}
						host = net.IP(ip).String()
					case 3:
						n, er := exact(c, 1)
						if er != nil {
							return
						}
						name, er := exact(c, int(n[0]))
						if er != nil {
							return
						}
						host = string(name)
					default:
						return
					}
					port, err := exact(c, 2)
					if err != nil {
						return
					}
					target = net.JoinHostPort(host, fmt.Sprint(int(port[0])*256+int(port[1])))
				} else {
					r, err := http.ReadRequest(bufio.NewReader(c))
					if err != nil || r.Method != "CONNECT" {
						return
					}
					target = r.Host
				}
				if len(options) > 0 && options[0].Target != "" {
					target = options[0].Target
				}
				upstream, err := net.DialTimeout("tcp", target, time.Second)
				if err != nil {
					return
				}
				defer upstream.Close()
				if typ == "SOCKS5" {
					c.Write([]byte{5, 0, 0, 1, 127, 0, 0, 1, 0, 0})
				} else {
					io.WriteString(c, "HTTP/1.1 200 Connection established\r\n\r\n")
				}
				go io.Copy(upstream, c)
				io.Copy(c, upstream)
			}()
		}
	}()
	addr := l.Addr().(*net.TCPAddr)
	return Endpoint{Host: "127.0.0.1", Port: addr.Port, Type: typ}, func() { l.Close() }
}
func TestEndpointValidation(t *testing.T) {
	good := []string{"8.8.8.8:1080", "http://1.1.1.1:8080", "https://8.8.4.4:443", "socks5://9.9.9.9:1"}
	for _, s := range good {
		if _, ok := parseEndpoint(s, "SOCKS5", "test"); !ok {
			t.Errorf("rejected %s", s)
		}
	}
	bad := []string{"127.0.0.1:80", "10.1.2.3:80", "172.16.1.1:80", "192.168.1.1:80", "100.64.1.1:80", "169.254.1.1:80", "0.1.1.1:80", "224.1.1.1:80", "240.1.1.1:80", "192.0.2.1:80", "http://a:b@8.8.8.8:80", "ftp://8.8.8.8:80", "8.8.8.8:65536", "8.8.8.8:0", "8.8.8.8:-1", "8.8.8.8:80/path", "example.com:80", "http://8.8.8.8:80?x=y"}
	for _, s := range bad {
		if _, ok := parseEndpoint(s, "SOCKS5", "test"); ok {
			t.Errorf("accepted %s", s)
		}
	}
}
func TestRealTLSOverAllProxyTypes(t *testing.T) {
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Write([]byte("valid origin")) }))
	defer server.Close()
	pool := x509.NewCertPool()
	pool.AddCert(server.Certificate())
	p := Probe{Roots: pool}
	for _, typ := range []string{"SOCKS5", "HTTP", "HTTPS"} {
		t.Run(typ, func(t *testing.T) {
			e, close := proxyServer(t, typ, server.TLS.Certificates[0], 0)
			defer close()
			ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			defer cancel()
			got, err := p.inspect(ctx, e, 2, []Target{{"test", "test", server.URL}})
			if err != nil {
				t.Fatal(err)
			}
			if got.Ping < 1 || len(got.Services) != 1 {
				t.Fatalf("invalid success %#v", got)
			}
			if _, err = (Probe{}).inspect(ctx, e, 2, []Target{{"test", "test", server.URL}}); err == nil {
				t.Fatal("untrusted TLS accepted")
			}
		})
	}
}
func TestHTTPFailureIsNotHealthy(t *testing.T) {
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(403) }))
	defer server.Close()
	pool := x509.NewCertPool()
	pool.AddCert(server.Certificate())
	e, close := proxyServer(t, "SOCKS5", server.TLS.Certificates[0], 0)
	defer close()
	_, err := (Probe{Roots: pool}).inspect(context.Background(), e, 2, []Target{{"test", "test", server.URL}})
	if err == nil {
		t.Fatal("HTTP 403 accepted")
	}
}
func TestCancellationClosesBlockedSocket(t *testing.T) {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	closed := make(chan struct{})
	go func() {
		c, err := l.Accept()
		if err != nil {
			return
		}
		defer c.Close()
		b := make([]byte, 3)
		io.ReadFull(c, b)
		c.Read(b)
		close(closed)
	}()
	ctx, cancel := context.WithCancel(context.Background())
	e := Endpoint{Host: "127.0.0.1", Port: l.Addr().(*net.TCPAddr).Port, Type: "SOCKS5"}
	finished := make(chan error, 1)
	go func() { _, err := (Probe{}).inspect(ctx, e, 10, targets[:1]); finished <- err }()
	time.Sleep(30 * time.Millisecond)
	cancel()
	select {
	case err := <-finished:
		if err == nil {
			t.Fatal("cancel succeeded")
		}
	case <-time.After(time.Second):
		t.Fatal("cancel did not stop socket")
	}
	select {
	case <-closed:
	case <-time.After(time.Second):
		t.Fatal("server socket left open")
	}
}
func TestBalancedSourcesAndDedup(t *testing.T) {
	a := Endpoint{Host: "8.8.8.8", Port: 1, Type: "SOCKS5"}
	b := a
	b.Port = 2
	c := a
	c.Type = "HTTP"
	got := balanced([][]Endpoint{{a, b}, {c, a}})
	if len(got) != 3 || got[0].key() != a.key() || got[1].key() != c.key() || got[2].key() != b.key() {
		t.Fatal(got)
	}
}
func TestPersistentOutOfOrderCheckpointAndFilters(t *testing.T) {
	a := newTestApp(t)
	defer os.RemoveAll(a.dir)
	e := Endpoint{Host: "8.8.8.8", Port: 1, Type: "SOCKS5", Ping: 500, Services: []string{"telegram"}}
	e2 := e
	e2.Port = 2
	e2.Ping = 499
	e3 := e
	e3.Port = 3
	e3.Ping = 0
	a.disk.Results = map[string]Endpoint{e.key(): e, e2.key(): e2, e3.key(): e3}
	a.disk.Raw = []Endpoint{e, e2, e3}
	a.disk.Checkpoint = &Checkpoint{Input: a.disk.Raw, Done: []bool{false, true, false}, Settings: defaultSettings(), Targets: targets}
	if err := a.save(); err != nil {
		t.Fatal(err)
	}
	b := newApp(a.dir)
	if b.disk.Checkpoint.Done[0] || !b.disk.Checkpoint.Done[1] || b.disk.Checkpoint.Done[2] {
		t.Fatal("out of order progress damaged")
	}
	r := httptest.NewRequest("GET", "http://localhost/api/state?service=telegram&ping=500", nil)
	data := b.snapshot(r).(map[string]any)
	if data["filtered"].(int) != 1 {
		t.Fatal("ping boundary or unknown ping accepted")
	}
	if len(b.disk.Results) != 3 {
		t.Fatal("filter deleted underlying results")
	}
}
func TestStopResumeUsesSavedInputs(t *testing.T) {
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Write([]byte("ok")) }))
	defer server.Close()
	a := newTestApp(t)
	a.disk.Settings.Workers = 2
	var closes []func()
	for i := 0; i < 4; i++ {
		e, close := proxyServer(t, "SOCKS5", server.TLS.Certificates[0], 150*time.Millisecond)
		closes = append(closes, close)
		a.disk.Raw = append(a.disk.Raw, e)
	}
	defer func() {
		for _, c := range closes {
			c()
		}
	}()
	a.disk.Checkpoint = &Checkpoint{Input: append([]Endpoint(nil), a.disk.Raw...), Done: make([]bool, 4), Settings: a.disk.Settings, Targets: []Target{{"test", "test", server.URL}}}
	if err := a.startScan("resume"); err != nil {
		t.Fatal(err)
	}
	time.Sleep(25 * time.Millisecond)
	a.act(Action{Action: "stop"})
	wait := func() {
		deadline := time.Now().Add(5 * time.Second)
		for time.Now().Before(deadline) {
			a.mu.Lock()
			busy := a.scanRunning
			a.mu.Unlock()
			if !busy {
				return
			}
			time.Sleep(10 * time.Millisecond)
		}
		t.Fatal("scan did not finish")
	}
	wait()
	a.mu.Lock()
	cp := a.disk.Checkpoint
	if cp == nil {
		a.mu.Unlock()
		t.Fatal("cancelled checkpoint lost")
	}
	if cp.Done[0] || cp.Done[1] {
		a.mu.Unlock()
		t.Fatal("cancelled attempts marked done")
	}
	a.disk.Raw = nil
	a.mu.Unlock()
	if err := a.startScan("resume"); err != nil {
		t.Fatal(err)
	}
	wait()
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.disk.Checkpoint != nil || a.lastTotal != 4 {
		t.Fatal("resume did not finish original inputs")
	}
}
func TestLoopbackAPIAuthorization(t *testing.T) {
	a := newTestApp(t)
	a.origin = "http://127.0.0.1:12345"
	a.token = "secret"
	h := a.handler()
	for _, tc := range []struct {
		token, origin, host string
		code                int
	}{{"", "", "127.0.0.1:12345", 403}, {"secret", "https://evil.example", "127.0.0.1:12345", 403}, {"secret", "", "evil.example", 403}, {"secret", "", "127.0.0.1:12345", 200}} {
		r := httptest.NewRequest("GET", a.origin+"/api/state", nil)
		r.Host = tc.host
		r.Header.Set("X-App-Token", tc.token)
		r.Header.Set("Origin", tc.origin)
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		if w.Code != tc.code {
			t.Fatalf("API authorization %v got %d", tc, w.Code)
		}
	}
}
func TestConfigAcceptedByBundledCore(t *testing.T) {
	root, err := filepath.Abs("../toolchain/sing-box-1.14.3-linux-amd64/sing-box")
	if override := os.Getenv("PERSIANSOCKS_TEST_CORE"); override != "" {
		root = override
	}
	if err != nil {
		t.Fatal(err)
	}
	if _, err = os.Stat(root); err != nil {
		t.Skip("validation core not available")
	}
	for _, typ := range []string{"SOCKS5", "HTTP", "HTTPS"} {
		t.Run(typ, func(t *testing.T) {
			config := tunnelConfig(Endpoint{Host: "8.8.8.8", Port: 1080, Type: typ}, Physical{Name: "eth0"}, 31081)
			b, _ := json.Marshal(config)
			path := filepath.Join(t.TempDir(), "tunnel.json")
			os.WriteFile(path, b, 0600)
			cmd := exec.Command(root, "check", "-c", path)
			out, err := cmd.CombinedOutput()
			if err != nil {
				t.Fatalf("config rejected: %v\n%s", err, out)
			}
			s := string(b)
			if !strings.Contains(s, "process_path") || !strings.Contains(s, "bind_interface") || strings.Contains(s, "insecure") {
				t.Fatal("direct binding or TLS verification missing")
			}
		})
	}
}
func TestLargeStoreAndPageBound(t *testing.T) {
	a := newTestApp(t)
	for i := 1; i <= 50000; i++ {
		e := Endpoint{Host: "8.8.8.8", Port: i, Type: "SOCKS5", Ping: int64(i)}
		a.disk.Results[e.key()] = e
	}
	if err := a.save(); err != nil {
		t.Fatal(err)
	}
	b := newApp(a.dir)
	if len(b.disk.Results) != 50000 {
		t.Fatal("large cache truncated")
	}
	s := b.snapshot(httptest.NewRequest("GET", "http://localhost/api/state?page=499", nil)).(map[string]any)
	if len(s["rows"].([]Endpoint)) != 100 {
		t.Fatal("page not bounded")
	}
}

func TestStateSerializationDuringUpdates(t *testing.T) {
	a := newTestApp(t)
	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		for i := 0; i < 1000; i++ {
			a.mu.Lock()
			a.test["proxy"] = i%2 == 0
			delete(a.test, "old")
			a.mu.Unlock()
		}
	}()
	for i := 0; i < 100; i++ {
		value := a.snapshot(httptest.NewRequest("GET", "http://localhost/api/state", nil))
		if _, err := json.Marshal(value); err != nil {
			t.Fatal(err)
		}
	}
	wg.Wait()
}

func newTestApp(t *testing.T) *App {
	t.Helper()
	a := newApp(t.TempDir())
	if runtime.GOOS == "windows" {
		a.physical = Physical{Index: 1, Name: "Loopback Pseudo-Interface 1", IP: "127.0.0.1"}
	}
	return a
}
