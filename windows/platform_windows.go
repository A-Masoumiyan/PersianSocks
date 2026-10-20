//go:build windows

package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"time"
	"unsafe"
)

var shell32 = syscall.NewLazyDLL("shell32.dll")
var kernel32 = syscall.NewLazyDLL("kernel32.dll")
var instanceMutex uintptr

func wide(s string) *uint16 { p, _ := syscall.UTF16PtrFromString(s); return p }
func alert(s string) {
	syscall.NewLazyDLL("user32.dll").NewProc("MessageBoxW").Call(0, uintptr(unsafe.Pointer(wide(s))), uintptr(unsafe.Pointer(wide("PersianSocks"))), 0x10)
}
func preparePlatform() bool {
	admin, _, _ := shell32.NewProc("IsUserAnAdmin").Call()
	if admin == 0 {
		exe, _ := os.Executable()
		r, _, _ := shell32.NewProc("ShellExecuteW").Call(0, uintptr(unsafe.Pointer(wide("runas"))), uintptr(unsafe.Pointer(wide(exe))), 0, uintptr(unsafe.Pointer(wide(filepath.Dir(exe)))), 1)
		if r <= 32 {
			alert("برای اتصال VPN، اجازهٔ اجرای Administrator را تأیید کن.")
		}
		return false
	}
	mutex, _, err := kernel32.NewProc("CreateMutexW").Call(0, 0, uintptr(unsafe.Pointer(wide("Local\\PersianSocks-Windows"))))
	if mutex == 0 {
		alert(fmt.Sprint(err))
		return false
	}
	if err == syscall.Errno(183) {
		syscall.CloseHandle(syscall.Handle(mutex))
		base, _ := os.UserConfigDir()
		if custom := os.Getenv("PERSIANSOCKS_DATA"); custom != "" {
			base = custom
		}
		b, readErr := os.ReadFile(filepath.Join(base, "PersianSocks", "UI.url"))
		if readErr == nil && strings.HasPrefix(string(b), "http://127.0.0.1:") {
			openUI(string(b))
		} else {
			alert("PersianSocks از قبل باز است. برای بستن کامل آن، «قطع اتصال و خروج از برنامه» را بزن.")
		}
		return false
	}
	instanceMutex = mutex
	kernel32.NewProc("AllocConsole").Call()
	hwnd, _, _ := kernel32.NewProc("GetConsoleWindow").Call()
	syscall.NewLazyDLL("user32.dll").NewProc("ShowWindow").Call(hwnd, 0)
	return true
}
func hideCommand(cmd *exec.Cmd) {
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000}
}
func socketControl(index int) func(string, string, syscall.RawConn) error {
	if index == 0 {
		return nil
	}
	return func(network, address string, c syscall.RawConn) error {
		var err error
		e := c.Control(func(fd uintptr) {
			// IP_UNICAST_IF takes the interface index in network byte order.
			n := uint32(index)
			n = (n&0xff)<<24 | (n&0xff00)<<8 | (n&0xff0000)>>8 | (n >> 24)
			err = syscall.SetsockoptInt(syscall.Handle(fd), syscall.IPPROTO_IP, 31, int(n))
		})
		if e != nil {
			return e
		}
		return err
	}
}
func setupCore(cmd *exec.Cmd) {
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x00000200}
}
func guardCore(cmd *exec.Cmd) (uintptr, error) {
	job, _, err := kernel32.NewProc("CreateJobObjectW").Call(0, 0)
	if job == 0 {
		return 0, err
	}
	info := make([]byte, 144)
	*(*uint32)(unsafe.Pointer(&info[16])) = 0x2000
	ok, _, err := kernel32.NewProc("SetInformationJobObject").Call(job, 9, uintptr(unsafe.Pointer(&info[0])), uintptr(len(info)))
	if ok == 0 {
		closeGuard(job)
		return 0, err
	}
	process, _, err := kernel32.NewProc("OpenProcess").Call(0x0100|0x0001, 0, uintptr(cmd.Process.Pid))
	if process == 0 {
		closeGuard(job)
		return 0, err
	}
	defer syscall.CloseHandle(syscall.Handle(process))
	ok, _, err = kernel32.NewProc("AssignProcessToJobObject").Call(job, process)
	if ok == 0 {
		closeGuard(job)
		return 0, err
	}
	return job, nil
}
func closeGuard(job uintptr) {
	if job != 0 {
		syscall.CloseHandle(syscall.Handle(job))
	}
}
func stopCore(cmd *exec.Cmd, done chan error) {
	if cmd == nil {
		return
	}
	kernel32.NewProc("GenerateConsoleCtrlEvent").Call(1, uintptr(cmd.Process.Pid))
	select {
	case <-done:
		return
	case <-time.After(3 * time.Second):
	}
	cmd.Process.Kill()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
	}
}
