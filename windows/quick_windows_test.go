//go:build windows

package main

import (
	"testing"
	"time"
	"unsafe"
)

func TestNativeQuickControlsCreateAndClose(t *testing.T) {
	if unsafe.Sizeof(winClass{}) != 80 || unsafe.Sizeof(winMessage{}) != 48 || unsafe.Sizeof(notifyData{}) != 976 || unsafe.Sizeof(drawItem{}) != 64 {
		t.Fatal("Windows amd64 native structure layout mismatch")
	}
	a := newTestApp(t)
	a.origin = "http://127.0.0.1:1"
	a.shutdown = func() {}
	done := make(chan struct{})
	startQuickControls(a, done)
	defer close(done)
	deadline := time.Now().Add(5 * time.Second)
	for quickWindow.Load() == 0 && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	if quickWindow.Load() == 0 {
		t.Fatal("native quick-control window did not start")
	}
	if err := showQuickWidget(); err != nil {
		t.Fatal(err)
	}
}
