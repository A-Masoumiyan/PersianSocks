package main

import (
	"os"
	"path/filepath"
	"testing"
)

func TestBundledRuntimeExtractionAndRepair(t *testing.T) {
	if _, err := bundle.ReadFile("assets/windows-runtime.zip"); err != nil {
		t.Skip("optional embedded runtime not staged")
	}
	dir := t.TempDir()
	bin, err := prepareBundledRuntime(dir)
	if err != nil {
		t.Fatal(err)
	}
	if err = verifyFile(bin, coreSHA); err != nil {
		t.Fatal(err)
	}
	driver := filepath.Join(dir, "wintun.dll")
	stat, _ := os.Stat(bin)
	if _, err = prepareBundledRuntime(dir); err != nil {
		t.Fatal(err)
	}
	after, _ := os.Stat(bin)
	if !after.ModTime().Equal(stat.ModTime()) {
		t.Fatal("verified cache was rewritten")
	}
	if err = os.WriteFile(driver, []byte("corrupt"), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err = prepareBundledRuntime(dir); err != nil {
		t.Fatal(err)
	}
	if err = verifyFile(driver, wintunSHA); err != nil {
		t.Fatal("corrupt driver was not repaired", err)
	}
}
