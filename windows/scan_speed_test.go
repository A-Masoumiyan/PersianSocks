package main

import (
	"context"
	"crypto/x509"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"reflect"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func (p Probe) legacyInspectForComparison(ctx context.Context, e Endpoint, timeout int, ts []Target) (Endpoint, error) {
	e.Services = nil
	e.Checked = nil
	e.Ping = 0
	var last error
	for _, t := range ts {
		e.Checked = append(e.Checked, t.ID)
		if ctx.Err() != nil {
			return e, ctx.Err()
		}
		c, cancel := context.WithTimeout(ctx, time.Duration(timeout)*time.Second)
		start := time.Now()
		r, conn, err := p.request(c, e, t.URL)
		if err == nil {
			if r.StatusCode >= 200 && r.StatusCode < 400 {
				ms := time.Since(start).Milliseconds()
				if ms < 1 {
					ms = 1
				}
				if e.Ping == 0 || ms < e.Ping {
					e.Ping = ms
				}
				e.Services = append(e.Services, t.ID)
			} else {
				err = fmt.Errorf("HTTPS %d", r.StatusCode)
			}
			r.Body.Close()
			conn.Close()
		}
		cancel()
		last = err
		var op *net.OpError
		if errors.As(err, &op) && op.Op == "dial" && len(e.Services) == 0 {
			break
		}
	}
	if ctx.Err() != nil {
		return e, ctx.Err()
	}
	if len(e.Services) == 0 {
		if last == nil {
			last = errors.New("پاسخ معتبر HTTPS دریافت نشد")
		}
		return e, last
	}
	e.Updated = time.Now().UnixMilli()
	e.Note = ""
	if e.Type == "SOCKS5" {
		e.UDP = p.udpCapability(ctx, e)
	}
	return e, nil
}

func TestScanThroughputComparedWithPreviousVersion(t *testing.T) {
	origin := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(120 * time.Millisecond)
		w.Write([]byte("healthy"))
	}))
	defer origin.Close()
	roots := x509.NewCertPool()
	roots.AddCert(origin.Certificate())
	p := Probe{Roots: roots}
	e, closeProxy := proxyServer(t, "HTTP", origin.TLS.Certificates[0], 0)
	defer closeProxy()
	ts := []Target{{"one", "one", origin.URL}, {"two", "two", origin.URL}, {"three", "three", origin.URL}}
	run := func(workers int, optimized bool) time.Duration {
		jobs := make(chan int)
		var wg sync.WaitGroup
		var failures atomic.Int32
		started := time.Now()
		for n := 0; n < workers; n++ {
			wg.Add(1)
			go func() {
				defer wg.Done()
				for range jobs {
					var result Endpoint
					var err error
					if optimized {
						result, err = p.inspect(context.Background(), e, 3, ts)
					} else {
						result, err = p.legacyInspectForComparison(context.Background(), e, 3, ts)
					}
					if err != nil || len(result.Services) != 3 || len(result.Checked) != 3 {
						failures.Add(1)
					}
				}
			}()
		}
		for n := 0; n < 48; n++ {
			jobs <- n
		}
		close(jobs)
		wg.Wait()
		if failures.Load() != 0 {
			t.Fatalf("health results changed: %d failures", failures.Load())
		}
		return time.Since(started)
	}
	previous := run(12, false)
	current := run(defaultSettings().Workers, true)
	ratio := float64(previous) / float64(current)
	t.Logf("SCAN_COMPARISON old=%.3fs new=%.3fs speedup=%.2fx; 48 HTTP proxies, 3 valid TLS destinations, 120ms response delay", previous.Seconds(), current.Seconds(), ratio)
	if ratio < 2 {
		t.Fatalf("controlled scan throughput improvement below 2x: %.2fx", ratio)
	}
}
func TestParallelTargetsBoundedAndResultOrderPreserved(t *testing.T) {
	var active, peak atomic.Int32
	origin := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		n := active.Add(1)
		defer active.Add(-1)
		for old := peak.Load(); n > old; old = peak.Load() {
			if peak.CompareAndSwap(old, n) {
				break
			}
		}
		time.Sleep(40 * time.Millisecond)
		if r.URL.Path == "/bad" {
			w.WriteHeader(403)
		} else {
			w.Write([]byte("healthy"))
		}
	}))
	defer origin.Close()
	roots := x509.NewCertPool()
	roots.AddCert(origin.Certificate())
	p := Probe{Roots: roots}
	e, closeProxy := proxyServer(t, "HTTP", origin.TLS.Certificates[0], 0)
	defer closeProxy()
	ts := []Target{}
	for n := 0; n < 8; n++ {
		u := origin.URL
		if n == 2 {
			u += "/bad"
		}
		ts = append(ts, Target{ID: fmt.Sprint(n), URL: u})
	}
	got, err := p.inspect(context.Background(), e, 3, ts)
	if err != nil || peak.Load() > 3 || peak.Load() < 2 {
		t.Fatalf("bad parallelism/result: peak=%d err=%v", peak.Load(), err)
	}
	if !reflect.DeepEqual(got.Checked, []string{"0", "1", "2", "3", "4", "5", "6", "7"}) || !reflect.DeepEqual(got.Services, []string{"0", "1", "3", "4", "5", "6", "7"}) {
		t.Fatalf("service classification changed: %+v", got)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err = p.inspect(ctx, e, 3, ts); !errors.Is(err, context.Canceled) {
		t.Fatalf("cancel ignored: %v", err)
	}
}
func TestScanSettingsMigrationDoublesOnceAndRetainsCheckpoint(t *testing.T) {
	dir := t.TempDir()
	s := defaultSettings()
	s.Workers = 12
	s.ExitOnClose = true
	s.SplitMode = "only"
	s.SplitApps = []string{`C:\Apps\Telegram.exe`}
	e := Endpoint{Host: "8.8.8.8", Port: 1080, Type: "SOCKS5"}
	d := Disk{Settings: s, Results: map[string]Endpoint{e.key(): e}, Checkpoint: &Checkpoint{Input: []Endpoint{e}, Done: []bool{false}, Settings: s, Targets: targets}, Filter: Filter{Service: "full"}, Selected: e.key()}
	b, _ := json.Marshal(d)
	os.WriteFile(filepath.Join(dir, "state.json"), b, 0600)
	a := newApp(dir)
	if a.disk.Settings.Workers != 24 || a.disk.Checkpoint.Settings.Workers != 24 || len(a.disk.Results) != 1 || a.disk.Filter.Service != "full" || !a.disk.Settings.ExitOnClose || len(a.disk.Settings.SplitApps) != 1 {
		t.Fatalf("migration lost settings/progress: %+v", a.disk)
	}
	a.disk.Settings.Workers = 5
	if err := a.save(); err != nil {
		t.Fatal(err)
	}
	again := newApp(dir)
	if again.disk.Settings.Workers != 5 || again.disk.ScanEngine != scanEngineVersion {
		t.Fatal("migration repeated and changed manual worker choice")
	}
	s.Workers = maxScanWorkers
	if err := validateSettings(s); err != nil {
		t.Fatal(err)
	}
	s.Workers = maxScanWorkers + 1
	if validateSettings(s) == nil {
		t.Fatal("unbounded worker count accepted")
	}
}
func TestSpeedUsesSameFinalAverageAndDownloadCount(t *testing.T) {
	origin := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/octet-stream")
		for n := 0; n < 10; n++ {
			w.Write(make([]byte, 100000))
			w.(http.Flusher).Flush()
			time.Sleep(50 * time.Millisecond)
		}
	}))
	defer origin.Close()
	roots := x509.NewCertPool()
	roots.AddCert(origin.Certificate())
	p := Probe{Roots: roots}
	e, closeProxy := proxyServer(t, "HTTP", origin.TLS.Certificates[0], 0)
	defer closeProxy()
	var rate float64
	var amount int64
	updates := 0
	result, n, err := p.speedAt(context.Background(), e, origin.URL, func(r float64, b int64) { rate = r; amount = b; updates++ })
	if err != nil || n != 1000000 || amount != n || math.Abs(rate-result) > 1e-12 || updates < 2 || result <= 0 {
		t.Fatalf("inconsistent rate/count: final=%v progress=%v bytes=%d/%d updates=%d err=%v", result, rate, n, amount, updates, err)
	}
}
func TestSpeedPayloadIsCappedAtTwentyMB(t *testing.T) {
	origin := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Write(make([]byte, 21000000)) }))
	defer origin.Close()
	roots := x509.NewCertPool()
	roots.AddCert(origin.Certificate())
	p := Probe{Roots: roots}
	e, closeProxy := proxyServer(t, "HTTP", origin.TLS.Certificates[0], 0)
	defer closeProxy()
	_, n, err := p.speedAt(context.Background(), e, origin.URL)
	if err != nil || n != 20000000 {
		t.Fatalf("speed payload budget incorrect: %d %v", n, err)
	}
}
