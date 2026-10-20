package main

import (
	"context"
	"crypto/x509"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestDiscoveryJSONProtocolsCountryAndUntrustedInput(t *testing.T) {
	old := geo
	geo, _ = bundle.ReadFile("assets/ip-country.bin")
	defer func() { geo = old }()
	input := `[{"host":"212.33.201.143","port":80,"protocol":"HTTP"},{"proxy":"212.33.201.143:1080","protocol":"socks5"},{"proxy":"212.33.201.143:1080","protocol":"socks5"},{"proxy":"212.33.201.143:1080","protocol":"socks4"},{"proxy":"8.8.8.8:1080","protocol":"socks5","country":"IR"},{"proxy":"127.0.0.1:1080","protocol":"socks5"}]`
	rows, err := readSource(strings.NewReader(input), Source{Name: "test", Format: "json", Country: "IR"})
	if err != nil || len(rows) != 2 || rows[0].Type != "HTTP" || rows[1].Type != "SOCKS5" {
		t.Fatalf("invalid discovery: %+v %v", rows, err)
	}
	for _, e := range rows {
		if e.Ping != 0 || len(e.Services) > 0 || e.Updated != 0 || e.Country != "IR" {
			t.Fatal("upstream health/country trusted", e)
		}
	}
	for _, raw := range []string{`<html>error</html>`, `{"data":null}`, `[{}]`} {
		if _, err := readSource(strings.NewReader(raw), Source{Format: "json"}); err == nil {
			t.Fatal("invalid source accepted")
		}
	}
	rows, err = readSource(strings.NewReader(`{"data":[{"ip":"8.8.8.8","port":"1080","protocols":["socks4","socks5"]}]}`), Source{Format: "json"})
	if err != nil || len(rows) != 1 || rows[0].Type != "SOCKS5" {
		t.Fatal(rows, err)
	}
}
func TestIranCandidatesSurviveScanLimitAndDedup(t *testing.T) {
	ir := Endpoint{Host: "212.33.201.143", Port: 80, Type: "HTTP", Country: "IR"}
	other := Endpoint{Host: "8.8.8.8", Port: 80, Type: "HTTP", Country: "US"}
	got := prioritizeIran(balanced([][]Endpoint{{other, ir}, {ir, other}}))
	if len(got) != 2 || got[0].key() != ir.key() {
		t.Fatal(got)
	}
}
func TestInitialFetchOnceRetryAndExistingProfile(t *testing.T) {
	a := newTestApp(t)
	if !a.claimInitialFetch() || a.claimInitialFetch() {
		t.Fatal("initial download not once per session")
	}
	// A failed first run may persist bootstrap entries. It must retry next launch.
	a.disk.Raw = []Endpoint{{Host: "8.8.8.8", Port: 80, Type: "HTTP"}}
	if err := a.save(); err != nil {
		t.Fatal(err)
	}
	b := newTestApp(t)
	b.dir = a.dir
	b.disk = newApp(a.dir).disk
	if !b.claimInitialFetch() {
		t.Fatal("failed initial download not retried next launch")
	}
	b.disk.InitialFetchDone = true
	b.save()
	c := newApp(a.dir)
	if !c.disk.InitialFetchDone || c.claimInitialFetch() {
		t.Fatal("completed initial download repeated")
	}
}
func TestRapidWorkerBoundsAndCheckpointPersistence(t *testing.T) {
	for _, n := range []int{1, 24, 48} {
		s := defaultSettings()
		s.Workers = n
		if scanWorkerCount(s, true) != n*8 || scanWorkerCount(s, false) != n {
			t.Fatal("bad concurrency multiplier")
		}
	}
	a := newTestApp(t)
	s := a.disk.Settings
	a.disk.Checkpoint = &Checkpoint{Rapid: true, Input: []Endpoint{{Host: "8.8.8.8", Port: 1080, Type: "SOCKS5"}}, Done: []bool{false}, Settings: s, Targets: targets}
	a.save()
	b := newApp(a.dir)
	if b.disk.Checkpoint == nil || !b.disk.Checkpoint.Rapid || b.disk.Checkpoint.Settings.Timeout != s.Timeout || b.disk.Checkpoint.Settings.Workers != s.Workers {
		t.Fatal("rapid checkpoint changed timeout/settings")
	}
}
func TestRapidScanThroughputSameTLSChecks(t *testing.T) {
	origin := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(120 * time.Millisecond)
		w.Write([]byte("ok"))
	}))
	defer origin.Close()
	roots := x509.NewCertPool()
	roots.AddCert(origin.Certificate())
	probe := Probe{Roots: roots}
	e, closeProxy := proxyServer(t, "HTTP", origin.TLS.Certificates[0], 0)
	defer closeProxy()
	ts := []Target{{"one", "one", origin.URL}, {"two", "two", origin.URL}, {"three", "three", origin.URL}}
	s := defaultSettings()
	s.Workers = 4
	run := func(rapid bool) time.Duration {
		jobs := make(chan int)
		var wg sync.WaitGroup
		var failed atomic.Int32
		start := time.Now()
		for n := 0; n < scanWorkerCount(s, rapid); n++ {
			wg.Add(1)
			go func() {
				defer wg.Done()
				for range jobs {
					got, err := probe.inspect(context.Background(), e, s.Timeout, ts)
					if err != nil || len(got.Services) != 3 || len(got.Checked) != 3 {
						failed.Add(1)
					}
				}
			}()
		}
		for n := 0; n < 128; n++ {
			jobs <- n
		}
		close(jobs)
		wg.Wait()
		if failed.Load() != 0 {
			t.Fatalf("quality changed: %d failures", failed.Load())
		}
		return time.Since(start)
	}
	normal, rapid := run(false), run(true)
	ratio := float64(normal) / float64(rapid)
	t.Logf("RAPID_SCAN normal=%.3fs rapid=%.3fs measured=%.2fx; 128 HTTP proxies, identical three TLS checks, workers 4 -> 32 (8x)", normal.Seconds(), rapid.Seconds(), ratio)
	if ratio < 3 {
		t.Fatalf("controlled rapid scan did not improve throughput sufficiently: %.2fx", ratio)
	}
}

// Optional local source audit reads downloaded data only; it never probes endpoints.
func TestDownloadedSourceAudit(t *testing.T) {
	dir := os.Getenv("PERSIANSOCKS_SOURCE_AUDIT")
	if dir == "" {
		t.Skip("local downloaded feed audit")
	}
	old := geo
	geo, _ = bundle.ReadFile("assets/ip-country.bin")
	defer func() { geo = old }()
	var sources []Source
	b, _ := bundle.ReadFile("assets/sources.json")
	json.Unmarshal(b, &sources)
	for _, item := range []struct{ file, name string }{{"daniyal", "Iran Proxy Scraper • Iran candidates"}, {"bateni_json", "Iran Proxy Checker • Iranian server IPs"}, {"ps_ir", "ProxyScrape Iran"}, {"feix_socks", "Feix SOCKS5"}, {"vakhov_http", "Vakhov HTTP"}} {
		b, err := os.ReadFile(dir + "/ps-source-" + item.file)
		if err != nil {
			t.Fatal(err)
		}
		for _, src := range sources {
			if src.Name != item.name {
				continue
			}
			rows, err := readSource(strings.NewReader(string(b)), src)
			ir := 0
			for _, e := range rows {
				if e.Country == "IR" {
					ir++
				}
			}
			t.Logf("SOURCE_AUDIT %s: %d candidates, %d Iranian server IPs, parse=%v", src.Name, len(rows), ir, err)
		}
	}
}

func TestDownloadedCatalogUnionAudit(t *testing.T) {
	dir := os.Getenv("PERSIANSOCKS_CATALOG_AUDIT")
	if dir == "" {
		t.Skip("downloaded catalog only")
	}
	old := geo
	geo, _ = bundle.ReadFile("assets/ip-country.bin")
	defer func() { geo = old }()
	var sources []Source
	b, _ := bundle.ReadFile("assets/sources.json")
	json.Unmarshal(b, &sources)
	previous, current := map[string]bool{}, map[string]bool{}
	for i, src := range sources {
		b, err := os.ReadFile(fmt.Sprintf("%s/%d", dir, i))
		if err != nil {
			continue
		}
		rows, _ := readSource(strings.NewReader(string(b)), src)
		n := 0
		for _, e := range rows {
			if e.Country != "IR" {
				continue
			}
			n++
			current[e.key()] = true
			if i >= 13 {
				previous[e.key()] = true
			}
		}
		if i < 13 {
			t.Logf("IR_CATALOG source=%s Iranian candidates=%d", src.Name, n)
		}
	}
	t.Logf("IR_CATALOG_UNION previous=%d expanded=%d added=%d; server-IP geography only, health not tested", len(previous), len(current), len(current)-len(previous))
}
