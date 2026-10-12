package main

import (
	"context"
	"encoding/json"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
	"time"
)

func TestParityFiltersAndSorting(t *testing.T) {
	a := Endpoint{Host: "8.8.8.8", Port: 1, Type: "SOCKS5", Ping: 499, Services: []string{"telegram", "youtube", "instagram"}, Updated: 2}
	b := a
	b.Port = 2
	b.Ping = 500
	b.Type = "HTTP"
	b.Services = []string{"telegram"}
	b.UDP = true
	b.Speed = 7
	b.Updated = 3
	c := a
	c.Port = 3
	c.Type = "HTTPS"
	c.Ping = 0
	c.Services = []string{"custom"}
	rows := map[string]Endpoint{a.key(): a, b.key(): b, c.key(): c}
	for _, tc := range []struct {
		f    Filter
		tls  bool
		want int
	}{{Filter{Ping: 500}, false, 1}, {Filter{Service: "full"}, false, 1}, {Filter{Type: "HTTP_GROUP"}, false, 2}, {Filter{}, true, 1}, {Filter{Service: "custom"}, false, 1}, {Filter{Service: "full", Type: "HTTP_GROUP"}, false, 0}} {
		if got := len(filteredRows(rows, tc.f, tc.tls)); got != tc.want {
			t.Errorf("%+v: %d want %d", tc.f, got, tc.want)
		}
	}
	for _, tc := range []struct{ sort, key string }{{"ping", a.key()}, {"services", a.key()}, {"speed", b.key()}, {"recent", b.key()}, {"udp", b.key()}} {
		if got := filteredRows(rows, Filter{Sort: tc.sort}, false)[0].key(); got != tc.key {
			t.Errorf("%s first %s", tc.sort, got)
		}
	}
	s := defaultSettings()
	s.ScanTypes = []string{"SOCKS5"}
	if !scanAccepts(a, s) || scanAccepts(b, s) {
		t.Fatal("scan types mixed with result filter")
	}
	s.TLSOnly = true
	if scanAccepts(a, s) {
		t.Fatal("TLS scan accepted SOCKS")
	}
}
func TestCustomLabelsAndLimits(t *testing.T) {
	ts, err := customTargets("نمونه | https://example.org/a\nhttps://example.org/a")
	if err != nil || len(ts) != 4 || ts[3].Name != "نمونه" {
		t.Fatalf("%+v %v", ts, err)
	}
	if _, err = customTargets(strings.Repeat("https://example.org/\n", 20)); err != nil {
		t.Fatal("duplicates count toward limit")
	}
	var raw strings.Builder
	for i := 0; i < 9; i++ {
		raw.WriteString("https://example.org/" + strings.Repeat("a", i) + "\n")
	}
	if _, err = customTargets(raw.String()); err == nil {
		t.Fatal("nine custom targets accepted")
	}
	if _, err = parseCustomSources(strings.Repeat("HTTPS https://example.org/list\n", 21)); err == nil {
		t.Fatal("21 sources accepted")
	}
	for _, s := range []string{"HTTP http://example.org/list", "OTHER https://example.org/list", "HTTPS https://u:p@example.org/list"} {
		if _, err = parseCustomSources(s); err == nil {
			t.Fatal(s)
		}
	}
	if _, ok := parseEndpoint("https://proxy.example.org:443", "SOCKS5", "manual"); !ok {
		t.Fatal("HTTPS hostname rejected")
	}
}
func TestSplitModesAndCaseInsensitiveExactPaths(t *testing.T) {
	s := defaultSettings()
	s.SplitMode = "only"
	if _, err := splitRules(s); err == nil {
		t.Fatal("empty only mode accepted")
	}
	path := filepath.Join(t.TempDir(), "Example (1).exe")
	os.WriteFile(path, []byte("fixture"), 0600)
	s.SplitApps = []string{path, filepath.Join(t.TempDir(), "uninstalled.exe")}
	rules, err := splitRules(s)
	if err != nil || len(rules) != 2 {
		t.Fatalf("%v %v", rules, err)
	}
	r := rules[0].(map[string]any)
	pattern := r["process_path_regex"].([]string)[0]
	if !regexp.MustCompile(pattern).MatchString(strings.ToUpper(path)) || regexp.MustCompile(pattern).MatchString(path+".other") {
		t.Fatal("process rule not exact case-insensitive")
	}
	if r["outbound"] != "proxy" || rules[1].(map[string]any)["outbound"] != "direct" {
		t.Fatal("only routing wrong")
	}
	s.SplitMode = "except"
	rules, err = splitRules(s)
	if err != nil || len(rules) != 1 || rules[0].(map[string]any)["outbound"] != "direct" {
		t.Fatal("exception routing wrong")
	}
	s.SplitMode = "all"
	rules, _ = splitRules(s)
	if len(rules) != 0 {
		t.Fatal("all should have no process selection rule")
	}
	for _, p := range []string{`C:\Program Files\Example\example.exe`, `D:\Game\Game.EXE`, path} {
		if !validExecutablePath(p) {
			t.Fatal(p)
		}
	}
	for _, p := range []string{"relative.exe", `C:\App\app.dll`, `C:\App\bad.exe` + "\n"} {
		if validExecutablePath(p) {
			t.Fatal(p)
		}
	}
}
func TestTrafficDeltaResetAndPersistence(t *testing.T) {
	a := newTestApp(t)
	at := time.Now()
	a.sampleTrafficLocked(100, 50, at)
	a.sampleTrafficLocked(400, 90, at.Add(time.Second))
	if a.disk.Traffic.Down != 400 || a.disk.Traffic.DownRate != 300 {
		t.Fatal(a.disk.Traffic)
	}
	a.sampleTrafficLocked(20, 10, at.Add(2*time.Second))
	if a.disk.Traffic.Down != 420 || a.disk.Traffic.Up != 100 {
		t.Fatal("reset double-counted", a.disk.Traffic)
	}
	a.disk.Filter = Filter{Service: "full", Sort: "udp"}
	a.disk.Selected = "selection"
	a.save()
	b := newApp(a.dir)
	if b.disk.Traffic.Down != 420 || b.disk.Traffic.DownRate != 0 || b.disk.Filter.Sort != "udp" || b.disk.Selected != "selection" {
		t.Fatal("preferences/traffic lost")
	}
}
func waitTasks(t *testing.T, a *App) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		a.mu.Lock()
		n := len(a.tasks)
		a.mu.Unlock()
		if n == 0 {
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatal("test did not stop")
}
func TestCanceledSingleKeepsHealthyAndSpeedFailureKeepsHealthy(t *testing.T) {
	a := newTestApp(t)
	origin := httptest.NewTLSServer(nil)
	defer origin.Close()
	e, close := proxyServer(t, "SOCKS5", origin.TLS.Certificates[0], 300*time.Millisecond)
	defer close()
	a.disk.Results[e.key()] = e
	if err := a.runSingle(e.key(), "test"); err != nil {
		t.Fatal(err)
	}
	time.Sleep(20 * time.Millisecond)
	a.act(Action{Action: "stopTest", Key: e.key()})
	waitTasks(t, a)
	a.mu.Lock()
	_, exists := a.disk.Results[e.key()]
	a.mu.Unlock()
	if !exists {
		t.Fatal("cancellation removed healthy endpoint")
	}
	close()
	if err := a.runSingle(e.key(), "speed"); err != nil {
		t.Fatal(err)
	}
	waitTasks(t, a)
	a.mu.Lock()
	defer a.mu.Unlock()
	if _, ok := a.disk.Results[e.key()]; !ok {
		t.Fatal("speed failure removed healthy endpoint")
	}
	if a.disk.Tests[e.key()].SpeedError == "" {
		t.Fatal("failure not reported")
	}
}
func TestParityCoreConfigsAccepted(t *testing.T) {
	bin := os.Getenv("PERSIANSOCKS_TEST_CORE")
	if bin == "" {
		t.Skip("set PERSIANSOCKS_TEST_CORE to verified sing-box binary")
	}
	path := filepath.Join(t.TempDir(), "Example.exe")
	os.WriteFile(path, []byte("fixture"), 0600)
	for _, mode := range []string{"all", "only", "except"} {
		t.Run(mode, func(t *testing.T) {
			s := defaultSettings()
			s.SplitMode = mode
			s.SplitApps = []string{path}
			cfg, err := makeTunnelConfig(Endpoint{Host: "8.8.8.8", Port: 1080, Type: "SOCKS5"}, Physical{Name: "lo"}, 12987, 12988, "test-secret", s)
			if err != nil {
				t.Fatal(err)
			}
			rules := cfg["route"].(map[string]any)["rules"].([]any)
			if rules[0].(map[string]any)["inbound"] != "local-proxy" || rules[0].(map[string]any)["outbound"] != "proxy" {
				t.Fatal("verification bypasses selected proxy")
			}
			b, _ := json.Marshal(cfg)
			file := filepath.Join(t.TempDir(), "config.json")
			os.WriteFile(file, b, 0600)
			out, err := exec.CommandContext(context.Background(), bin, "check", "-c", file).CombinedOutput()
			if err != nil {
				t.Fatalf("%v %s", err, out)
			}
		})
	}
}
