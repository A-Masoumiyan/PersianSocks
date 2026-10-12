//go:build windows

package main

import (
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func waitDesktop(t *testing.T, condition func() bool) {
	t.Helper()
	until := time.Now().Add(25 * time.Second)
	for time.Now().Before(until) {
		if condition() {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatal("native desktop did not reach expected state")
}
func TestNativeDesktopHideRestoreAndExit(t *testing.T) {
	a := newTestApp(t)
	// WebView runtime processes close asynchronously after controller.Close.
	// Verify profile handles are released before testing.TempDir removes the tree.
	t.Cleanup(func() {
		deadline := time.Now().Add(20 * time.Second)
		for {
			err := os.RemoveAll(filepath.Join(a.dir, "WebView2"))
			if err == nil {
				return
			}
			if time.Now().After(deadline) {
				t.Errorf("WebView profile still locked after close: %v", err)
				return
			}
			time.Sleep(100 * time.Millisecond)
		}
	})
	server := httptest.NewServer(a.handler())
	defer server.Close()
	a.origin = server.URL
	a.token = "native-fixture-token"
	done := make(chan struct{})
	defer close(done)
	defer stopDesktopUI()
	var stopped atomic.Int32
	a.scanCancel = func() { stopped.Add(1) }
	a.fetchCancel = func() { stopped.Add(1) }
	a.connectCancel = func() { stopped.Add(1) }
	exited := make(chan struct{}, 1)
	a.shutdown = func() { exited <- struct{}{} }
	startDesktopUI(a, done)
	waitDesktop(t, func() bool { return desktopReady.Load() })
	h := desktopWindow.Load()
	if h == 0 {
		t.Fatal("desktop HWND missing")
	}
	user32.NewProc("PostMessageW").Call(h, 0x10, 0, 0)
	waitDesktop(t, func() bool { return !desktopState().Visible })
	if stopped.Load() != 0 || !desktopReady.Load() {
		t.Fatal("close stopped background work or destroyed browser")
	}
	openUI(a.origin + "/#" + a.token)
	waitDesktop(t, func() bool { return desktopState().Visible })
	if desktopWindow.Load() != h {
		t.Fatal("reopening created a duplicate window")
	}
	a.mu.Lock()
	s := a.disk.Settings
	s.ExitOnClose = true
	a.mu.Unlock()
	if err := a.applySettings(s); err != nil {
		t.Fatal(err)
	}
	user32.NewProc("PostMessageW").Call(h, 0x10, 0, 0)
	select {
	case <-exited:
	case <-time.After(time.Second):
		t.Fatal("native exit policy did not invoke shutdown")
	}
	stopDesktopUI()
	waitDesktop(t, func() bool { return desktopWindow.Load() == 0 })
	if desktopReady.Load() {
		t.Fatal("WebView still ready after native stop")
	}
	if !strings.Contains(a.reportText(), "خروج کامل") {
		t.Fatal("close policy not reported")
	}
}
