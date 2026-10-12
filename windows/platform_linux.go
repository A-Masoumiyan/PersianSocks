//go:build !windows

package main

import (
	"fmt"
	"os"
	"os/exec"
	"syscall"
	"time"
)

func preparePlatform() bool                                               { return true }
func alert(s string)                                                      { fmt.Fprintln(os.Stderr, s) }
func openUI(url string)                                                   { exec.Command("xdg-open", url).Start() }
func hideCommand(cmd *exec.Cmd)                                           {}
func physicalNetwork() (Physical, error)                                  { return Physical{}, nil }
func physicalReady(p Physical) bool                                       { return true }
func socketControl(index int) func(string, string, syscall.RawConn) error { return nil }
func setupCore(cmd *exec.Cmd) {
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true, Pdeathsig: syscall.SIGTERM}
}
func guardCore(cmd *exec.Cmd) (uintptr, error) { return 0, nil }
func closeGuard(job uintptr)                   {}
func stopCore(cmd *exec.Cmd, done chan error) {
	if cmd == nil {
		return
	}
	cmd.Process.Signal(os.Interrupt)
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		cmd.Process.Kill()
		<-done
	}
}
