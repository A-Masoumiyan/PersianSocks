//go:build windows

package main

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestNativeAdapterDiscovery(t *testing.T) {
	if os.Getenv("PERSIANSOCKS_TEST_TUN") == "" {
		t.Skip("Windows runner opt-in required")
	}
	p, err := physicalNetwork()
	if err != nil || !physicalReady(p) {
		t.Fatalf("native adapter discovery failed: %+v %v", p, err)
	}
	t.Logf("Native adapter detected: %s (index %d), DNS servers %d", p.Name, p.Index, len(p.DNS))
}
func TestRoutingProbeChild(t *testing.T) {
	if os.Getenv("PERSIANSOCKS_ROUTING_CHILD") != "1" {
		return
	}
	tr := &http.Transport{Proxy: nil, DisableKeepAlives: true}
	defer tr.CloseIdleConnections()
	c := http.Client{Transport: tr, Timeout: 8 * time.Second}
	req, _ := http.NewRequest("GET", os.Getenv("PERSIANSOCKS_PROBE_URL"), nil)
	req.Host = os.Getenv("PERSIANSOCKS_PROBE_HOST")
	r, err := c.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer r.Body.Close()
	b, err := io.ReadAll(r.Body)
	if err != nil {
		t.Fatal(err)
	}
	fmt.Println("ROUTE-BODY:", string(b))
}
func reserveRoutingPort(t *testing.T) int {
	t.Helper()
	l, e := net.Listen("tcp4", "127.0.0.1:0")
	if e != nil {
		t.Fatal(e)
	}
	defer l.Close()
	return l.Addr().(*net.TCPAddr).Port
}
func queryRoutingFakeIP(t *testing.T, port int, domain string) string {
	t.Helper()
	q := []byte{0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0}
	for _, label := range strings.Split(domain, ".") {
		q = append(q, byte(len(label)))
		q = append(q, label...)
	}
	q = append(q, 0, 0, 1, 0, 1)
	c, e := net.DialTimeout("udp4", fmt.Sprintf("127.0.0.1:%d", port), time.Second)
	if e != nil {
		t.Fatal(e)
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(4 * time.Second))
	c.Write(q)
	b := make([]byte, 4096)
	n, e := c.Read(b)
	if e != nil || n < 16 {
		t.Fatalf("fake DNS failed: %d %v", n, e)
	}
	ip := net.IP(b[n-4 : n]).String()
	if !strings.HasPrefix(ip, "198.18.") && !strings.HasPrefix(ip, "198.19.") {
		t.Fatalf("not a fake IPv4 answer: %s %x", ip, b[:n])
	}
	return ip
}
func TestRealWindowsTUNScopedRoutes(t *testing.T) {
	if os.Getenv("PERSIANSOCKS_TEST_TUN") == "" {
		t.Skip("Windows runner opt-in required")
	}
	bin := os.Getenv("PERSIANSOCKS_TEST_CORE")
	if bin == "" {
		t.Fatal("native core required")
	}
	executable, _ := os.Executable()
	data, e := os.ReadFile(executable)
	if e != nil {
		t.Fatal(e)
	}
	dir := t.TempDir()
	selected := filepath.Join(dir, "ChatGPT.exe")
	unrelated := filepath.Join(dir, "UnrelatedProbe.exe")
	if e = os.WriteFile(selected, data, 0700); e != nil {
		t.Fatal(e)
	}
	if e = os.WriteFile(unrelated, data, 0700); e != nil {
		t.Fatal(e)
	}
	for _, caseName := range []string{"apps", "domains", "chatgpt", "guard-apps", "guard-domains", "guard-chatgpt"} {
		guardTest := strings.HasPrefix(caseName, "guard-")
		mode := strings.TrimPrefix(caseName, "guard-")
		t.Run(caseName, func(t *testing.T) {
			origin := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { io.WriteString(w, "VIA-PROXY") }))
			defer origin.Close()
			direct := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { io.WriteString(w, "VIA-DIRECT") }))
			defer direct.Close()
			var count atomic.Int32
			endpoint, closeProxy := proxyServer(t, "SOCKS5", tls.Certificate{}, 0, fixtureOptions{Target: strings.TrimPrefix(origin.URL, "http://"), Connections: &count})
			defer closeProxy()
			s := defaultSettings()
			s.RoutingMode = mode
			s.SplitMode = "only"
			s.SplitApps = []string{selected}
			s.SplitExact = true
			s.RouteDomains = "tunnel.example"
			port, dnsPort := reserveRoutingPort(t), reserveRoutingPort(t)
			cfg, err := makeTunnelConfig(endpoint, Physical{}, port, 0, "", s)
			if guardTest {
				cfg, err = makeGuardConfig(Physical{}, port, s)
			}
			if err != nil {
				t.Fatal(err)
			}
			tun := cfg["inbounds"].([]any)[0].(map[string]any)
			tun["interface_name"] = "PersianSocks-RouteTest"
			tun["route_address"] = []string{"203.0.113.0/24", "198.18.0.0/15"}
			tun["dns_mode"] = "disabled"
			tun["strict_route"] = false
			// Capture only isolated documentation/FakeIP destinations. No runner default
			// route, DNS settings, or unrelated CI traffic is redirected by this test.
			cfg["inbounds"] = append(cfg["inbounds"].([]any), map[string]any{"type": "direct", "tag": "test-dns", "listen": "127.0.0.1", "listen_port": dnsPort, "network": "udp"})
			rules := cfg["route"].(map[string]any)["rules"].([]any)
			filteredRules := []any{}
			for _, r := range rules {
				if r.(map[string]any)["ip_is_private"] == true {
					continue
				}
				filteredRules = append(filteredRules, r)
			}
			rules = filteredRules // documentation ranges are reserved, unlike real Internet destinations.
			_, dp, _ := net.SplitHostPort(strings.TrimPrefix(direct.URL, "http://"))
			directPort, _ := strconv.Atoi(dp)
			for _, r := range rules {
				m := r.(map[string]any)
				if m["outbound"] == "direct" {
					m["override_address"] = "127.0.0.1"
					m["override_port"] = directPort
				}
			}
			cfg["route"].(map[string]any)["rules"] = append([]any{map[string]any{"inbound": "test-dns", "action": "hijack-dns"}}, rules...)
			file := filepath.Join(t.TempDir(), "tunnel.json")
			b, _ := json.Marshal(cfg)
			os.WriteFile(file, b, 0600)
			log := &logTail{}
			cmd := exec.Command(bin, "run", "-c", file)
			cmd.Dir = filepath.Dir(bin)
			cmd.Stdout = log
			cmd.Stderr = log
			setupCore(cmd)
			if e := cmd.Start(); e != nil {
				t.Fatal(e)
			}
			done := make(chan error, 1)
			go func() { done <- cmd.Wait(); close(done) }()
			defer stopCore(cmd, done)
			ready := false
			deadline := time.Now().Add(15 * time.Second)
			for time.Now().Before(deadline) {
				select {
				case <-done:
					t.Fatalf("TUN core stopped: %s", log.String())
				default:
				}
				c, er := net.DialTimeout("tcp4", fmt.Sprintf("127.0.0.1:%d", port), 100*time.Millisecond)
				if er == nil {
					c.Close()
					ready = true
					break
				}
				time.Sleep(100 * time.Millisecond)
			}
			if !ready {
				t.Fatalf("TUN did not start: %s", log.String())
			}
			fw := newIsolatedKill(t, []string{selected, unrelated}, true)
			if e := fw.Arm([]string{executable, bin}, "PersianSocks-RouteTest"); e != nil {
				t.Fatal(e)
			}
			probe := func(exe, ip, host, want string) {
				t.Helper()
				ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
				defer cancel()
				child := exec.CommandContext(ctx, exe, "-test.run=^TestRoutingProbeChild$")
				child.Env = append(os.Environ(), "PERSIANSOCKS_ROUTING_CHILD=1", "PERSIANSOCKS_PROBE_URL=http://"+net.JoinHostPort(ip, "80")+"/", "PERSIANSOCKS_PROBE_HOST="+host)
				hideCommand(child)
				out, e := child.CombinedOutput()
				if want == "" {
					if e == nil {
						t.Fatalf("guard allowed protected request: %s", out)
					}
					return
				}
				if e != nil || !strings.Contains(string(out), "ROUTE-BODY: "+want) {
					t.Fatalf("%s %s expected %s: %v %s\nCORE: %s", filepath.Base(exe), host, want, e, out, log.String())
				}
			}
			ip := "203.0.113.77"
			host := "tunnel.example"
			if mode == "domains" {
				ip = queryRoutingFakeIP(t, dnsPort, host)
			}
			wantSelected := "VIA-PROXY"
			if guardTest {
				wantSelected = ""
			}
			probe(selected, ip, host, wantSelected)
			if guardTest && count.Load() != 0 {
				t.Fatal("guard contacted upstream proxy")
			}
			if !guardTest && count.Load() == 0 {
				t.Fatal("selected request bypassed proxy")
			}
			before := count.Load()
			otherIP := "203.0.113.78"
			if mode == "domains" {
				otherIP = queryRoutingFakeIP(t, dnsPort, "direct.example")
			}
			probe(unrelated, otherIP, "direct.example", "VIA-DIRECT")
			if count.Load() != before {
				t.Fatal("unrelated app/domain reached proxy")
			}
			if mode == "chatgpt" {
				probe(unrelated, ip, "chatgpt.com", wantSelected)
			}
			if err := cmd.Process.Kill(); err != nil {
				t.Fatal(err)
			}
			select {
			case <-done:
			case <-time.After(5 * time.Second):
				t.Fatal("core crash did not complete")
			}
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			fallback := exec.CommandContext(ctx, selected, "-test.run=^TestRoutingProbeChild$")
			fallback.Env = append(os.Environ(), "PERSIANSOCKS_ROUTING_CHILD=1", "PERSIANSOCKS_PROBE_URL="+direct.URL, "PERSIANSOCKS_PROBE_HOST=chatgpt.com")
			hideCommand(fallback)
			if out, e := fallback.CombinedOutput(); e == nil {
				t.Fatalf("direct fallback escaped after core crash: %s", out)
			}
			if e := fw.Release(); e != nil {
				t.Fatal(e)
			}
			t.Logf("Real Wintun %s: selected request proxied, unrelated request direct; upstream connections %d", caseName, count.Load())
			t.Log("Independent WFP guard blocked direct fallback after forced core termination")
		})
	}
}
