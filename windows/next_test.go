package main

import (
	"testing"
	"time"
)

func TestFilteredSpeedCapturesAllPagesAndCancels(t *testing.T) {
	a := newApp(t.TempDir())
	a.physical = Physical{Index: 1, Name: "test", IP: "127.0.0.1", DNS: []string{"127.0.0.1"}}
	a.disk.Filter = Filter{Type: "SOCKS5"}
	// Keep both test slots occupied; no external traffic is needed.
	a.tasks = map[string]*TestTask{"busy1": {cancel: func() {}}, "busy2": {cancel: func() {}}}
	for i := 1; i <= 600; i++ {
		e := Endpoint{Host: "127.0.0.1", Port: i, Type: "SOCKS5"}
		a.disk.Results[e.key()] = e
	}
	other := Endpoint{Host: "127.0.0.1", Port: 700, Type: "HTTP"}
	a.disk.Results[other.key()] = other
	if err := a.startFilteredSpeed(); err != nil {
		t.Fatal(err)
	}
	a.mu.Lock()
	total := a.speedBatchTotal
	a.disk.Filter = Filter{Type: "HTTP"}
	a.mu.Unlock()
	if total != 600 {
		t.Fatalf("batch includes %d, want all 600 filtered entries", total)
	}
	if err := a.startFilteredSpeed(); err == nil {
		t.Fatal("duplicate batch accepted")
	}
	if err := a.act(Action{Action: "stopSpeedFiltered"}); err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		a.mu.Lock()
		running := a.speedBatchCancel != nil
		a.mu.Unlock()
		if !running {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("batch did not stop")
}

func TestWindowClosePolicy(t *testing.T) {
	a := newApp(t.TempDir())
	exited := make(chan struct{}, 1)
	a.shutdown = func() { exited <- struct{}{} }
	a.act(Action{Action: "uiClosed"})
	select {
	case <-exited:
		t.Fatal("default close stopped app")
	default:
	}
	a.disk.Settings.ExitOnClose = true
	a.act(Action{Action: "uiClosed"})
	select {
	case <-exited:
	case <-time.After(3 * time.Second):
		t.Fatal("exit-on-close did not stop app")
	}
}

func TestFilteredSpeedEmpty(t *testing.T) {
	a := newApp(t.TempDir())
	if a.startFilteredSpeed() == nil {
		t.Fatal("empty batch accepted")
	}
}
