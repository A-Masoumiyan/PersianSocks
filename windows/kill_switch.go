package main

import (
	"errors"
	"os"
	"path/filepath"
	"runtime"
)

type killFirewall interface {
	Arm([]string, string) error
	Release() error
	Close() error
}

func killEnabled(s Settings) bool   { return !s.KillSwitchOff }
func (a *App) expectedCore() string { return filepath.Join(a.dir, "runtime", "1.14.3", "sing-box.exe") }

// All firewall operations and core transitions run under connectMu.
func (a *App) armKillLocked(bin string) error {
	a.mu.Lock()
	started, s := a.protectionStarted, a.disk.Settings
	a.mu.Unlock()
	if !started {
		return nil
	}
	if a.firewall == nil {
		f, err := newKillFirewall()
		if err != nil {
			return a.killFailure(err)
		}
		a.firewall = f
	}
	var err error
	if !killEnabled(s) {
		err = a.firewall.Release()
	} else {
		exe, _ := os.Executable()
		err = a.firewall.Arm([]string{exe, bin}, "PersianSocks-TUN")
	}
	if err != nil {
		return a.killFailure(err)
	}
	a.mu.Lock()
	a.killArmed = killEnabled(s)
	a.killError = ""
	a.mu.Unlock()
	return nil
}
func (a *App) killFailure(err error) error {
	a.mu.Lock()
	a.killArmed = false
	a.killError = err.Error()
	a.err = "حفاظت کیل‌سوییچ آماده نشد: " + err.Error()
	a.mu.Unlock()
	return err
}
func (a *App) startProtection() {
	if runtime.GOOS != "windows" {
		return
	}
	p, _ := physicalNetwork()
	a.mu.Lock()
	a.physical = p
	a.protectionStarted = true
	a.mu.Unlock()
	a.connectMu.Lock()
	_ = a.armKillLocked(a.expectedCore())
	a.connectMu.Unlock()
	go a.ensureProtection()
}
func (a *App) ensureProtection() {
	a.connectMu.Lock()
	defer a.connectMu.Unlock()
	a.mu.Lock()
	started, closing, s, state, guard := a.protectionStarted, a.closing, a.disk.Settings, a.vpnState, a.guardMode
	a.mu.Unlock()
	if !started || closing {
		return
	}
	if err := a.armKillLocked(a.expectedCore()); err != nil {
		return
	}
	if !killEnabled(s) {
		if guard {
			a.stopGuardLocked()
		}
		return
	}
	a.mu.Lock()
	stale := guard && (routingSignature(a.activeSettings) != routingSignature(s) || a.corePhysical.Index != a.physical.Index || a.corePhysical.IP != a.physical.IP)
	a.mu.Unlock()
	if stale {
		a.stopGuardLocked()
		guard = false
	}
	if state != "off" || guard {
		return
	}
	if err := a.startGuardLocked(s); err != nil {
		a.killFailure(err)
	}
}
func (a *App) stopGuardLocked() {
	a.mu.Lock()
	if !a.guardMode {
		a.mu.Unlock()
		return
	}
	cmd, done, closeJob := a.core, a.coreDone, a.guardCloser
	a.guardCloser = nil
	a.core = nil
	a.guardMode = false
	a.job = 0
	a.mu.Unlock()
	stopCore(cmd, done)
	if closeJob != nil {
		closeJob()
	}
	flushDNS()
}
func (a *App) stopProtection() error {
	a.connectMu.Lock()
	defer a.connectMu.Unlock()
	if a.firewall == nil {
		return nil
	}
	err := a.firewall.Release()
	if err != nil {
		return a.killFailure(err)
	}
	err = a.firewall.Close()
	a.firewall = nil
	a.mu.Lock()
	a.killArmed = false
	a.mu.Unlock()
	return err
}
func (a *App) protectionReady() error {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.protectionStarted && killEnabled(a.disk.Settings) && !a.killArmed {
		return errors.New("کیل‌سوییچ هنوز آماده نیست؛ اتصال مستقیم جایگزین نمی‌شود")
	}
	return nil
}
