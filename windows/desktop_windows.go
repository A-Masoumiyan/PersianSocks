//go:build windows

package main

import (
	"fmt"
	"github.com/jchv/go-webview2/pkg/edge"
	"path/filepath"
	"runtime"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
	"unsafe"
)

const desktopClass = "PersianSocksDesktopWindow"
const desktopShow = 0x8010
const desktopStop = 0x8011

var desktopWindow atomic.Uintptr
var desktopReady atomic.Bool
var desktopCallback uintptr
var desktopStarted atomic.Bool
var desktopClosing atomic.Bool
var desktopMu sync.Mutex
var desktopFinished <-chan struct{}

func openUI(_ string) {
	h := desktopWindow.Load()
	if h == 0 {
		h, _, _ = user32.NewProc("FindWindowW").Call(uintptr(unsafe.Pointer(wide(desktopClass))), 0)
	}
	if h != 0 {
		user32.NewProc("PostMessageW").Call(h, desktopShow, 0, 0)
	}
}
func startDesktopUI(a *App, done <-chan struct{}) {
	if !desktopStarted.CompareAndSwap(false, true) {
		openUI("")
		return
	}
	finished := make(chan struct{})
	desktopMu.Lock()
	desktopFinished = finished
	desktopMu.Unlock()
	desktopClosing.Store(false)
	go func() {
		defer close(finished)
		runtime.LockOSThread()
		defer runtime.UnlockOSThread()
		defer desktopStarted.Store(false)
		ole := syscall.NewLazyDLL("ole32.dll")
		hr, _, _ := ole.NewProc("CoInitializeEx").Call(0, 2)
		if int32(hr) < 0 {
			a.mu.Lock()
			a.err = "راه‌اندازی پنجرهٔ برنامه ناموفق بود"
			a.mu.Unlock()
			return
		}
		defer ole.NewProc("CoUninitialize").Call()
		instance, _, _ := kernel32.NewProc("GetModuleHandleW").Call(0)
		icon := appIcon()
		if icon != 0 {
			defer user32.NewProc("DestroyIcon").Call(icon)
		}
		brush, _, _ := gdi32.NewProc("CreateSolidBrush").Call(0x1d110b)
		defer gdi32.NewProc("DeleteObject").Call(brush)
		cursor, _, _ := user32.NewProc("LoadCursorW").Call(0, 32512)
		browser := edge.NewChromium()
		browser.DataPath = filepath.Join(a.dir, "WebView2")
		browser.SetPermission(edge.CoreWebView2PermissionKindClipboardRead, edge.CoreWebView2PermissionStateAllow)
		ready := false
		closePending := false
		stopPending := false
		resize := func() {
			if !ready {
				return
			}
			controller := browser.GetController()
			bounds, err := controller.GetBounds()
			if err != nil {
				return
			}
			user32.NewProc("GetClientRect").Call(desktopWindow.Load(), uintptr(unsafe.Pointer(bounds)))
			controller.PutBounds(*bounds)
		}
		callback := syscall.NewCallback(func(h uintptr, msg uint32, w, l uintptr) uintptr {
			switch msg {
			case 0x10:
				user32.NewProc("ShowWindow").Call(h, 0)
				if ready {
					browser.Hide()
				}
				if ready {
					a.windowClosed()
				} else {
					closePending = true
				}
				return 0
			case desktopShow:
				show := uintptr(5)
				iconic, _, _ := user32.NewProc("IsIconic").Call(h)
				if iconic != 0 {
					show = 9
				}
				user32.NewProc("ShowWindow").Call(h, show)
				user32.NewProc("SetForegroundWindow").Call(h)
				if ready {
					browser.Show()
					resize()
					browser.Focus()
				}
				return 0
			case desktopStop:
				if !ready {
					stopPending = true
					return 0
				}
				if ready {
					controller := browser.GetController()
					vtbl := *(**[26]uintptr)(unsafe.Pointer(controller))
					syscall.SyscallN(vtbl[24], uintptr(unsafe.Pointer(controller)))
					ready = false
					desktopReady.Store(false)
				}
				user32.NewProc("DestroyWindow").Call(h)
				return 0
			case 2:
				user32.NewProc("PostQuitMessage").Call(0)
				return 0
			case 5:
				if ready {
					if w == 1 {
						browser.Hide()
					} else {
						browser.Show()
						resize()
					}
				}
				return 0
			case 3:
				if ready {
					browser.NotifyParentWindowPositionChanged()
				}
				return 0
			case 7:
				if ready {
					browser.Focus()
				}
				return 0
			case 0x11:
				return 1
			case 0x16:
				if w != 0 {
					go a.shutdown()
				}
				return 0
			}
			result, _, _ := user32.NewProc("DefWindowProcW").Call(h, uintptr(msg), w, l)
			return result
		})
		desktopCallback = callback
		name := wide(desktopClass)
		wc := winClass{Size: uint32(unsafe.Sizeof(winClass{})), Proc: callback, Instance: instance, Icon: icon, SmallIcon: icon, Cursor: cursor, Background: brush, Name: name}
		atom, _, _ := user32.NewProc("RegisterClassExW").Call(uintptr(unsafe.Pointer(&wc)))
		if atom == 0 {
			a.mu.Lock()
			a.err = "ساخت پنجرهٔ دسکتاپ ناموفق بود"
			a.mu.Unlock()
			return
		}
		defer user32.NewProc("UnregisterClassW").Call(uintptr(unsafe.Pointer(name)), instance)
		var work winRect
		user32.NewProc("SystemParametersInfoW").Call(0x30, 0, uintptr(unsafe.Pointer(&work)), 0)
		width, height := int32(1366), int32(820)
		x, y := int32(80), int32(60)
		if work.Right > work.Left && work.Bottom > work.Top {
			if available := work.Right - work.Left - 40; available < width {
				width = available
			}
			if available := work.Bottom - work.Top - 40; available < height {
				height = available
			}
			x = work.Left + (work.Right-work.Left-width)/2
			y = work.Top + (work.Bottom-work.Top-height)/2
		}
		h, _, err := user32.NewProc("CreateWindowExW").Call(0, uintptr(unsafe.Pointer(name)), uintptr(unsafe.Pointer(wide("PersianSocks"))), 0x00cf0000, uintptr(x), uintptr(y), uintptr(width), uintptr(height), 0, 0, instance, 0)
		if h == 0 {
			a.mu.Lock()
			a.err = fmt.Sprint(err)
			a.mu.Unlock()
			return
		}
		desktopWindow.Store(h)
		defer desktopWindow.Store(0)
		defer desktopReady.Store(false)
		dark := int32(1)
		syscall.NewLazyDLL("dwmapi.dll").NewProc("DwmSetWindowAttribute").Call(h, 20, uintptr(unsafe.Pointer(&dark)), 4)
		go func() { <-done; user32.NewProc("PostMessageW").Call(h, desktopStop, 0, 0) }()
		user32.NewProc("ShowWindow").Call(h, 5)
		if !browser.Embed(h) {
			user32.NewProc("DestroyWindow").Call(h)
			alert("مؤلفهٔ نمایش Microsoft WebView2 در دسترس نیست. آن را از سایت Microsoft نصب کن و برنامه را دوباره اجرا کن.")
			go a.shutdown()
			return
		}
		ready = true
		desktopReady.Store(true)
		resize()
		settings, err := browser.GetSettings()
		if err == nil {
			settings.PutAreDefaultContextMenusEnabled(false)
			settings.PutAreDevToolsEnabled(false)
			settings.PutIsStatusBarEnabled(false)
		}
		browser.Navigate(a.origin + "/#" + a.token)
		if stopPending || desktopClosing.Load() {
			user32.NewProc("PostMessageW").Call(h, desktopStop, 0, 0)
		} else if closePending {
			browser.Hide()
			a.windowClosed()
		}
		var msg winMessage
		for {
			r, _, _ := user32.NewProc("GetMessageW").Call(uintptr(unsafe.Pointer(&msg)), 0, 0, 0)
			if int32(r) <= 0 {
				break
			}
			user32.NewProc("TranslateMessage").Call(uintptr(unsafe.Pointer(&msg)))
			user32.NewProc("DispatchMessageW").Call(uintptr(unsafe.Pointer(&msg)))
		}
	}()
}

func stopDesktopUI() {
	desktopClosing.Store(true)
	h := desktopWindow.Load()
	if h != 0 {
		user32.NewProc("PostMessageW").Call(h, desktopStop, 0, 0)
	}
	desktopMu.Lock()
	finished := desktopFinished
	desktopMu.Unlock()
	if finished != nil {
		select {
		case <-finished:
		case <-time.After(3 * time.Second):
		}
	}
}

func desktopState() DesktopState {
	h := desktopWindow.Load()
	visible := false
	if h != 0 {
		r, _, _ := user32.NewProc("IsWindowVisible").Call(h)
		visible = r != 0
	}
	return DesktopState{Ready: desktopReady.Load(), Visible: visible}
}
