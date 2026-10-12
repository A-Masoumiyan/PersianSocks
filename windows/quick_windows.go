//go:build windows

package main

import (
	"encoding/binary"
	"errors"
	"fmt"
	"runtime"
	"sync/atomic"
	"syscall"
	"unsafe"
)

var user32 = syscall.NewLazyDLL("user32.dll")
var gdi32 = syscall.NewLazyDLL("gdi32.dll")
var quickWindow atomic.Uintptr
var quickCallback uintptr

// Native layouts are for the Windows amd64 build.
type winClass struct {
	Size, Style                        uint32
	Proc                               uintptr
	ClassExtra, WindowExtra            int32
	Instance, Icon, Cursor, Background uintptr
	Menu, Name                         *uint16
	SmallIcon                          uintptr
}
type winPoint struct{ X, Y int32 }
type winRect struct{ Left, Top, Right, Bottom int32 }
type winMessage struct {
	Window         uintptr
	Message        uint32
	WParam, LParam uintptr
	Time           uint32
	Point          winPoint
	Private        uint32
}
type notifyData struct {
	Size               uint32
	Window             uintptr
	ID, Flags, Message uint32
	Icon               uintptr
	Tip                [128]uint16
	State, StateMask   uint32
	Info               [256]uint16
	Version            uint32
	Title              [64]uint16
	InfoFlags          uint32
	GUID               [16]byte
	BalloonIcon        uintptr
}
type measureItem struct {
	CtlType, CtlID, ItemID, Width, Height uint32
	Data                                  uintptr
}

type menuInfo struct {
	Size, Mask, Style, MaxHeight uint32
	Background                   uintptr
	ContextHelp                  uint32
	Data                         uintptr
}

var trayLabels = map[uintptr]string{1: "بازکردن برنامه", 2: "اتصال / قطع اتصال", 3: "اسکن و اتصال خودکار", 4: "ویجت دسکتاپ", 5: "توقف کامل و خروج", 6: "توقف عملیات جاری"}

func appIcon() uintptr {
	b, err := bundle.ReadFile("assets/app.ico")
	if err != nil || len(b) < 22 {
		return 0
	}
	n := binary.LittleEndian.Uint32(b[14:18])
	off := binary.LittleEndian.Uint32(b[18:22])
	if int(off+n) > len(b) {
		return 0
	}
	h, _, _ := user32.NewProc("CreateIconFromResourceEx").Call(uintptr(unsafe.Pointer(&b[off])), uintptr(n), 1, 0x30000, 32, 32, 0)
	return h
}

type drawItem struct {
	CtlType, CtlID, ItemID, ItemAction, ItemState uint32
	Window, DC                                    uintptr
	Rect                                          winRect
	Data                                          uintptr
}

func textWide(dst []uint16, s string) { v, _ := syscall.UTF16FromString(s); clear(dst); copy(dst, v) }
func showQuickWidget() error {
	h := quickWindow.Load()
	if h == 0 {
		return errors.New("کنترل سریع هنوز آماده نیست")
	}
	user32.NewProc("PostMessageW").Call(h, 0x8002, 0, 0)
	return nil
}
func startQuickControls(a *App, done <-chan struct{}) {
	go func() {
		runtime.LockOSThread()
		defer runtime.UnlockOSThread()
		instance, _, _ := kernel32.NewProc("GetModuleHandleW").Call(0)
		icon := appIcon()
		if icon == 0 {
			icon, _, _ = user32.NewProc("LoadIconW").Call(0, 32512)
		}
		defer user32.NewProc("DestroyIcon").Call(icon)
		cursor, _, _ := user32.NewProc("LoadCursorW").Call(0, 32512)
		brush, _, _ := gdi32.NewProc("CreateSolidBrush").Call(0x1d110b)
		defer gdi32.NewProc("DeleteObject").Call(brush)
		var h, status, toggle, automatic uintptr
		fontHeight := int32(-15)
		font, _, _ := gdi32.NewProc("CreateFontW").Call(uintptr(fontHeight), 0, 0, 0, 400, 0, 0, 0, 1, 0, 0, 5, 0, uintptr(unsafe.Pointer(wide("Segoe UI"))))
		defer gdi32.NewProc("DeleteObject").Call(font)
		run := func(action string) {
			go func() {
				if err := a.act(Action{Action: action}); err != nil {
					a.mu.Lock()
					a.err = err.Error()
					a.recordLocked(a.err)
					a.mu.Unlock()
				}
			}()
		}
		open := func() { go openUI(a.origin + "/#" + a.token) }
		update := func() {
			a.mu.Lock()
			phase, scanning, fetching := a.vpnState, a.scanRunning, a.fetching
			label := map[string]string{"off": "قطع", "connecting": "در حال اتصال", "on": "متصل", "stopping": "در حال قطع"}[phase]
			if scanning {
				label = "در حال اسکن"
			}
			if fetching {
				label = "دریافت لیست"
			}
			if a.speedBatchCancel != nil {
				label = fmt.Sprintf("تست سرعت %d/%d", a.speedBatchDone, a.speedBatchTotal)
			}
			caption := fmt.Sprintf("%s • %d سالم", label, len(a.disk.Results))
			if scanning {
				caption = fmt.Sprintf("%s • %d از %d", label, a.lastDone, a.lastTotal)
				if cp := a.disk.Checkpoint; cp != nil && !a.fastScan {
					n := 0
					for _, b := range cp.Done {
						if b {
							n++
						}
					}
					caption = fmt.Sprintf("%s • %d از %d", label, n, len(cp.Input))
				}
			}
			a.mu.Unlock()
			button := "اتصال"
			auto := "اسکن و اتصال خودکار"
			if scanning {
				auto = "توقف اسکن"
			}
			if phase != "off" {
				button = "قطع اتصال"
				auto = "قطع اتصال"
			}
			user32.NewProc("SetWindowTextW").Call(status, uintptr(unsafe.Pointer(wide(caption))))
			user32.NewProc("SetWindowTextW").Call(toggle, uintptr(unsafe.Pointer(wide(button))))
			user32.NewProc("SetWindowTextW").Call(automatic, uintptr(unsafe.Pointer(wide(auto))))
			data := notifyData{Size: uint32(unsafe.Sizeof(notifyData{})), Window: h, ID: 1, Flags: 4}
			textWide(data.Tip[:], "PersianSocks • "+caption)
			shell32.NewProc("Shell_NotifyIconW").Call(1, uintptr(unsafe.Pointer(&data)))
		}
		callback := syscall.NewCallback(func(window uintptr, message uint32, w, l uintptr) uintptr {
			switch message {
			case 0x10:
				user32.NewProc("ShowWindow").Call(window, 0)
				return 0
			case 0x8002:
				user32.NewProc("ShowWindow").Call(window, 5)
				user32.NewProc("SetForegroundWindow").Call(window)
				update()
				return 0
			case 0x8003:
				user32.NewProc("DestroyWindow").Call(window)
				return 0
			case 2:
				user32.NewProc("PostQuitMessage").Call(0)
				return 0
			case 0x113:
				update()
				return 0
			case 0x8001:
				if l == 0x202 {
					open()
				} else if l == 0x205 {
					menu, _, _ := user32.NewProc("CreatePopupMenu").Call()
					for _, item := range []struct {
						id   uintptr
						text string
					}{{1, "بازکردن برنامه"}, {2, "اتصال / قطع اتصال"}, {3, "اسکن و اتصال خودکار"}, {4, "ویجت دسکتاپ"}, {6, "توقف عملیات جاری"}, {5, "قطع اتصال و خروج"}} {
						user32.NewProc("AppendMenuW").Call(menu, 0x100, item.id, item.id)
					}
					mi := menuInfo{Size: uint32(unsafe.Sizeof(menuInfo{})), Mask: 2, Background: brush}
					user32.NewProc("SetMenuInfo").Call(menu, uintptr(unsafe.Pointer(&mi)))
					var point winPoint
					user32.NewProc("GetCursorPos").Call(uintptr(unsafe.Pointer(&point)))
					user32.NewProc("SetForegroundWindow").Call(window)
					choice, _, _ := user32.NewProc("TrackPopupMenu").Call(menu, 0x100|2, uintptr(point.X), uintptr(point.Y), 0, window, 0)
					user32.NewProc("DestroyMenu").Call(menu)
					switch choice {
					case 1:
						open()
					case 2:
						run("quickToggle")
					case 3:
						run("autoConnect")
					case 4:
						showQuickWidget()
					case 6:
						run("stopOperations")
					case 5:
						run("exit")
					}
				}
				return 0
			case 0x111:
				switch w & 0xffff {
				case 1:
					open()
				case 2:
					run("quickToggle")
				case 3:
					run("autoConnect")
				case 4:
					user32.NewProc("ShowWindow").Call(window, 0)
				}
				return 0
			case 0x2c:
				mi := (*measureItem)(unsafe.Pointer(l))
				if mi.CtlType == 1 {
					mi.Width = 260
					mi.Height = 38
					return 1
				}
			case 0x2b:
				di := (*drawItem)(unsafe.Pointer(l))
				color := uintptr(0x302016)
				if di.ItemState&1 != 0 {
					color = 0x4c3a2b
				}
				b, _, _ := gdi32.NewProc("CreateSolidBrush").Call(color)
				user32.NewProc("FillRect").Call(di.DC, uintptr(unsafe.Pointer(&di.Rect)), b)
				gdi32.NewProc("DeleteObject").Call(b)
				gdi32.NewProc("SelectObject").Call(di.DC, font)
				gdi32.NewProc("SetBkMode").Call(di.DC, 1)
				gdi32.NewProc("SetTextColor").Call(di.DC, 0xb8e246)
				var text [128]uint16
				if di.CtlType == 1 {
					textWide(text[:], trayLabels[uintptr(di.ItemID)])
				} else {
					user32.NewProc("GetWindowTextW").Call(di.Window, uintptr(unsafe.Pointer(&text[0])), 128)
				}
				user32.NewProc("DrawTextW").Call(di.DC, uintptr(unsafe.Pointer(&text[0])), uintptr(^uint32(0)), uintptr(unsafe.Pointer(&di.Rect)), 1|4|0x20|0x20000)
				return 1
			case 0x138:
				gdi32.NewProc("SetTextColor").Call(w, 0xbdbd97)
				gdi32.NewProc("SetBkColor").Call(w, 0x1d110b)
				return brush
			}
			v, _, _ := user32.NewProc("DefWindowProcW").Call(window, uintptr(message), w, l)
			return v
		})
		quickCallback = callback
		name := wide("PersianSocksQuickControl")
		wc := winClass{Size: uint32(unsafe.Sizeof(winClass{})), Proc: callback, Instance: instance, Icon: icon, SmallIcon: icon, Cursor: cursor, Background: brush, Name: name}
		atom, _, _ := user32.NewProc("RegisterClassExW").Call(uintptr(unsafe.Pointer(&wc)))
		if atom == 0 {
			a.mu.Lock()
			a.recordLocked("ثبت پنجرهٔ کنترل سریع ناموفق بود")
			a.mu.Unlock()
			return
		}
		h, _, _ = user32.NewProc("CreateWindowExW").Call(0x8|0x80, namePtr(name), uintptr(unsafe.Pointer(wide("PersianSocks"))), 0x00c00000|0x00080000|0x00020000, 120, 120, 370, 230, 0, 0, instance, 0)
		if h == 0 {
			return
		}
		quickWindow.Store(h)
		defer quickWindow.Store(0)
		child := func(class, text string, x, y, width, height, id uintptr, style uintptr) uintptr {
			c, _, _ := user32.NewProc("CreateWindowExW").Call(0x2000, uintptr(unsafe.Pointer(wide(class))), uintptr(unsafe.Pointer(wide(text))), 0x40000000|0x10000000|style, x, y, width, height, h, id, instance, 0)
			user32.NewProc("SendMessageW").Call(c, 0x30, font, 1)
			return c
		}
		status = child("STATIC", "PersianSocks", 14, 12, 326, 28, 0, 2)
		child("BUTTON", "بازکردن برنامه", 14, 53, 150, 38, 1, 0xb)
		toggle = child("BUTTON", "اتصال", 178, 53, 162, 38, 2, 0xb)
		automatic = child("BUTTON", "اسکن و اتصال خودکار", 14, 101, 326, 38, 3, 0xb)
		child("BUTTON", "پنهان‌کردن ویجت", 14, 149, 326, 32, 4, 0xb)
		data := notifyData{Size: uint32(unsafe.Sizeof(notifyData{})), Window: h, ID: 1, Flags: 7, Message: 0x8001, Icon: icon}
		textWide(data.Tip[:], "PersianSocks")
		shell32.NewProc("Shell_NotifyIconW").Call(0, uintptr(unsafe.Pointer(&data)))
		defer shell32.NewProc("Shell_NotifyIconW").Call(2, uintptr(unsafe.Pointer(&data)))
		user32.NewProc("SetTimer").Call(h, 1, 1000, 0)
		go func() { <-done; user32.NewProc("PostMessageW").Call(h, 0x8003, 0, 0) }()
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
func namePtr(p *uint16) uintptr { return uintptr(unsafe.Pointer(p)) }
