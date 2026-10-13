package main

import (
	"context"
	"errors"
	"fmt"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"
)

func waitBatch(t *testing.T, a *App) {
	t.Helper()
	until := time.Now().Add(3 * time.Second)
	for time.Now().Before(until) {
		a.mu.Lock()
		busy := a.speedBatchCancel != nil
		a.mu.Unlock()
		if !busy {
			return
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatal("batch still running")
}
func TestBatchAllPagesProgressAndFailureRetention(t *testing.T) {
	a := newTestApp(t)
	a.disk.Filter = Filter{Type: "SOCKS5"}
	for n := 1; n <= 215; n++ {
		e := Endpoint{Host: "127.0.0.1", Port: n, Type: "SOCKS5", Ping: int64(n)}
		a.disk.Results[e.key()] = e
	}
	other := Endpoint{Host: "127.0.0.1", Port: 1000, Type: "HTTP"}
	a.disk.Results[other.key()] = other
	first := make(chan struct{})
	release := make(chan struct{})
	var calls atomic.Int32
	a.speedRunner = func(ctx context.Context, p Physical, e Endpoint, progress func(float64, int64)) (float64, int64, error) {
		if calls.Add(1) == 1 {
			close(first)
			<-release
		}
		progress(1.5, 100)
		if e.Port == 2 {
			return 0, 100, errors.New("fixture speed failure")
		}
		return 1.5, 100, nil
	}
	if err := a.startFilteredSpeed(); err != nil {
		t.Fatal(err)
	}
	<-first
	a.mu.Lock()
	a.disk.Filter = Filter{Type: "HTTP"}
	a.mu.Unlock()
	close(release)
	waitBatch(t, a)
	a.mu.Lock()
	defer a.mu.Unlock()
	if calls.Load() != 215 || a.speedBatch.Done != 215 || a.speedBatch.Total != 215 || a.speedBatch.Bytes != 21500 || a.speedBatch.Failed != 1 || len(a.disk.Results) != 216 {
		t.Fatalf("batch scope/results incorrect: %+v calls=%d healthy=%d", a.speedBatch, calls.Load(), len(a.disk.Results))
	}
	if a.disk.Results[other.key()].Speed != 0 {
		t.Fatal("filter change switched batch input")
	}
}
func TestBatchCancelLeavesIndependentSpeedRunning(t *testing.T) {
	a := newTestApp(t)
	for n := 1; n <= 2; n++ {
		e := Endpoint{Host: "127.0.0.1", Port: n, Type: "SOCKS5", Ping: int64(n)}
		a.disk.Results[e.key()] = e
	}
	a.disk.Filter = Filter{Ping: 500}
	started := make(chan int, 2)
	independentRelease := make(chan struct{})
	a.speedRunner = func(ctx context.Context, p Physical, e Endpoint, progress func(float64, int64)) (float64, int64, error) {
		started <- e.Port
		if e.Port == 2 {
			select {
			case <-independentRelease:
				return 2, 20, nil
			case <-ctx.Done():
				return 0, 0, ctx.Err()
			}
		}
		<-ctx.Done()
		return 0, 0, ctx.Err()
	}
	independent := Endpoint{Host: "127.0.0.1", Port: 2, Type: "SOCKS5"}
	if err := a.runSingle(independent.key(), "speed"); err != nil {
		t.Fatal(err)
	}
	<-started
	if err := a.startFilteredSpeed(); err != nil {
		t.Fatal(err)
	}
	<-started
	a.stopFilteredSpeed()
	waitBatch(t, a)
	a.mu.Lock()
	task := a.tasks[independent.key()]
	a.mu.Unlock()
	if task == nil {
		t.Fatal("stopping batch canceled independent test")
	}
	close(independentRelease)
	waitTasks(t, a)
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.disk.Results[independent.key()].Speed != 2 || len(a.disk.Results) != 2 {
		t.Fatal("independent speed result or healthy cache lost")
	}
}
func TestBatchStopAndWindowClosePoliciesPersist(t *testing.T) {
	a := newTestApp(t)
	var canceled atomic.Int32
	a.scanCancel = func() { canceled.Add(1) }
	a.fetchCancel = func() { canceled.Add(1) }
	a.speedBatchCancel = func() { canceled.Add(1) }
	exit := make(chan struct{}, 1)
	a.shutdown = func() { exit <- struct{}{} }
	a.windowClosed()
	if canceled.Load() != 0 {
		t.Fatal("background close canceled operations")
	}
	s := a.disk.Settings
	s.ExitOnClose = true
	if err := a.applySettings(s); err != nil {
		t.Fatal(err)
	}
	a.windowClosed()
	select {
	case <-exit:
	case <-time.After(time.Second):
		t.Fatal("exit-on-close not sent")
	}
	b := newApp(a.dir)
	if !b.disk.Settings.ExitOnClose {
		t.Fatal("close policy not persisted")
	}
	a.stopOperations()
	if canceled.Load() != 3 {
		t.Fatal("stop operations did not stop all types")
	}
}
func TestBatchAPIIncludesUsageAndFrozenFilter(t *testing.T) {
	a := newTestApp(t)
	a.speedBatch = SpeedBatch{Bytes: 2500, Failed: 2, Filter: Filter{Type: "SOCKS5"}}
	a.speedBatchDone = 7
	a.speedBatchTotal = 200
	s := a.snapshot(httptest.NewRequest("GET", "http://localhost/api/state?page=1", nil)).(map[string]any)["speedBatch"].(map[string]any)
	if fmt.Sprint(s["bytes"]) != "2500" || s["total"] != 200 || s["done"] != 7 {
		t.Fatal(s)
	}
}
