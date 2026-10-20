package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"time"
)

var coreSHA = "5c19bb3f8c12d4e85b3d47fe63123a7624b314b58dd7a8e32f51004f1c4d2faf"
var wintunSHA = "e5da8447dc2c320edc0fc52fa01885c103de8c118481f683643cacc3220dafce"

type logTail struct {
	mu sync.Mutex
	b  []byte
}

func (l *logTail) Write(b []byte) (int, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.b = append(l.b, b...)
	if len(l.b) > 16384 {
		l.b = append([]byte(nil), l.b[len(l.b)-16384:]...)
	}
	return len(b), nil
}
func (l *logTail) String() string { l.mu.Lock(); defer l.mu.Unlock(); return string(l.b) }
func tunnelConfig(e Endpoint, p Physical, proxyPort int) map[string]any {
	c, _ := makeTunnelConfig(e, p, proxyPort, 0, "", defaultSettings())
	return c
}
func makeTunnelConfig(e Endpoint, p Physical, proxyPort, statsPort int, secret string, s Settings) (map[string]any, error) {
	exe, _ := os.Executable()
	out := map[string]any{"tag": "proxy", "server": e.Host, "server_port": e.Port, "bind_interface": p.Name, "connect_timeout": "8s"}
	if e.Type == "SOCKS5" {
		out["type"] = "socks"
		out["version"] = "5"
	} else {
		out["type"] = "http"
		if e.Type == "HTTPS" {
			out["tls"] = map[string]any{"enabled": true, "server_name": e.Host}
		}
	}
	config := map[string]any{
		"log":       map[string]any{"level": "warn", "timestamp": true},
		"dns":       map[string]any{"servers": []any{map[string]any{"type": "https", "tag": "remote-dns", "server": "1.1.1.1", "tls": map[string]any{"enabled": true, "server_name": "cloudflare-dns.com"}, "detour": "proxy"}}, "final": "remote-dns", "strategy": "ipv4_only"},
		"inbounds":  []any{map[string]any{"type": "tun", "tag": "tun", "interface_name": "PersianSocks-TUN", "address": []string{"172.29.251.1/30", "fdfe:dcba:9876::1/126"}, "mtu": 1500, "auto_route": true, "strict_route": false, "dns_mode": "hijack", "stack": "mixed"}, map[string]any{"type": "mixed", "tag": "local-proxy", "listen": "127.0.0.1", "listen_port": proxyPort}},
		"outbounds": []any{out, map[string]any{"type": "direct", "tag": "direct", "bind_interface": p.Name}},
		"route":     map[string]any{"default_interface": p.Name, "final": "proxy", "rules": []any{map[string]any{"inbound": "tun", "process_path": exe, "action": "route", "outbound": "direct"}, map[string]any{"protocol": "dns", "action": "hijack-dns"}, map[string]any{"inbound": "tun", "ip_is_private": true, "action": "route", "outbound": "direct"}}},
	}
	rules := []any{map[string]any{"inbound": "local-proxy", "action": "route", "outbound": "proxy"}, map[string]any{"inbound": "tun", "process_path": exe, "action": "route", "outbound": "direct"}, map[string]any{"protocol": "dns", "action": "hijack-dns"}, map[string]any{"inbound": "tun", "ip_is_private": true, "action": "route", "outbound": "direct"}}
	split, err := splitRules(s)
	if err != nil {
		return nil, err
	}
	rules = append(rules, split...)
	config["route"].(map[string]any)["rules"] = rules
	if statsPort > 0 {
		config["experimental"] = map[string]any{"clash_api": map[string]any{"external_controller": fmt.Sprintf("127.0.0.1:%d", statsPort), "secret": secret}}
	}
	return config, nil

}
func verifyFile(path, expected string) error {
	if expected == "" {
		return errors.New("شناسهٔ اعتبارسنجی موتور موجود نیست")
	}
	b, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	sum := sha256.Sum256(b)
	if hex.EncodeToString(sum[:]) != expected {
		return errors.New("فایل موتور یا درایور با بستهٔ اصلی یکسان نیست؛ بسته را دوباره استخراج کن")
	}
	return nil
}
func (a *App) connect(key string) error { return a.connectMode(key, true) }
func (a *App) connectMode(key string, reset bool) error {
	a.mu.Lock()
	if a.vpnState != "off" {
		a.mu.Unlock()
		return errors.New("ابتدا اتصال قبلی را قطع کن")
	}
	e, ok := a.endpointLocked(key)
	if !ok {
		a.mu.Unlock()
		return errors.New("یک پروکسی سالم انتخاب کن")
	}
	if !filterAccepts(e, a.disk.Filter, a.disk.Settings.TLSOnly) {
		a.mu.Unlock()
		return errors.New("پروکسی انتخاب‌شده با فیلتر فعلی سازگار نیست")
	}
	if _, err := splitRules(a.disk.Settings); err != nil {
		a.mu.Unlock()
		return err
	}
	if !physicalReady(a.physical) {
		a.mu.Unlock()
		return errors.New("کارت شبکهٔ اصلی شناسایی نشده؛ از تنظیمات، شبکه را تازه‌سازی کن")
	}
	ctx, cancel := context.WithCancel(context.Background())
	a.connectCancel = cancel
	a.vpnState = "connecting"
	a.disk.Selected = key
	if reset {
		a.controlGeneration++
		a.disk.Traffic = Traffic{}
	}
	a.userDisconnect = false
	a.recordLocked("در حال اتصال: " + key)
	a.err = ""
	p := a.physical
	a.mu.Unlock()
	go func() {
		a.connectMu.Lock()
		defer a.connectMu.Unlock()
		err := a.startCore(ctx, e, p)
		a.mu.Lock()
		a.connectCancel = nil
		if err != nil {
			a.vpnState = "off"
			a.connected = nil
			if ctx.Err() == nil {
				a.err = "اتصال ناموفق: " + err.Error()
			}
		}
		recover := err != nil && ctx.Err() == nil && a.disk.Settings.Failover && !a.userDisconnect && !a.recovering
		a.mu.Unlock()
		if recover {
			go a.recoverConnection()
		}
	}()
	return nil
}
func (a *App) startCore(ctx context.Context, e Endpoint, p Physical) error {
	a.mu.Lock()
	settings, f := a.disk.Settings, a.disk.Filter
	a.sequence++
	claim := a.sequence
	a.attemptVersion[e.key()] = claim
	a.mu.Unlock()
	ts, _ := customTargets(settings.CustomTargets)
	fresh, probeErr := (Probe{Physical: p}).inspect(ctx, e, settings.Timeout, ts)
	if probeErr != nil || !filterAccepts(fresh, f, settings.TLSOnly) {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		a.mu.Lock()
		if a.attemptVersion[e.key()] == claim {
			delete(a.disk.Results, e.key())
		}
		a.cooldown[e.key()] = time.Now()
		a.mu.Unlock()
		if probeErr == nil {
			probeErr = errors.New("پروکسی دیگر با فیلتر فعلی سازگار نیست")
		}
		return probeErr
	}
	a.mu.Lock()
	fresh.Speed = a.disk.Results[e.key()].Speed
	if a.attemptVersion[e.key()] == claim {
		a.disk.Results[e.key()] = fresh
	}
	a.mu.Unlock()
	e = fresh
	exe, _ := os.Executable()
	name := "sing-box.exe"
	if runtime.GOOS != "windows" {
		name = "sing-box"
	}
	bin := filepath.Join(filepath.Dir(exe), "bin", name)
	if runtime.GOOS == "windows" {
		var err error
		bin, err = prepareBundledRuntime(filepath.Join(a.dir, "runtime", "1.14.3"))
		if err != nil {
			return err
		}
	}
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		return err
	}
	port := listener.Addr().(*net.TCPAddr).Port
	listener.Close()
	statsListener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		return err
	}
	statsPort := statsListener.Addr().(*net.TCPAddr).Port
	statsListener.Close()
	tunnel, err := makeTunnelConfig(e, p, port, statsPort, a.token, settings)
	if err != nil {
		return err
	}
	b, err := json.MarshalIndent(tunnel, "", "  ")
	if err != nil {
		return err
	}
	config := filepath.Join(a.dir, "tunnel.json")
	if err = os.WriteFile(config, b, 0600); err != nil {
		return err
	}
	checkCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	check := exec.CommandContext(checkCtx, bin, "check", "-c", config)
	hideCommand(check)
	output, err := check.CombinedOutput()
	cancel()
	if err != nil {
		return fmt.Errorf("پیکربندی موتور: %s %w", strings.TrimSpace(string(output)), err)
	}
	if ctx.Err() != nil {
		return ctx.Err()
	}
	log := &logTail{}
	cmd := exec.Command(bin, "run", "-c", config)
	cmd.Dir = filepath.Dir(bin)
	cmd.Stdout = log
	cmd.Stderr = log
	setupCore(cmd)
	if err = cmd.Start(); err != nil {
		return err
	}
	job, err := guardCore(cmd)
	if err != nil {
		cmd.Process.Kill()
		cmd.Wait()
		return err
	}
	done := make(chan error, 1)
	a.mu.Lock()
	a.core = cmd
	a.coreDone = done
	a.coreLog = log
	a.job = job
	a.mu.Unlock()
	go func() { err := cmd.Wait(); done <- err; close(done) }()
	fail := func(err error) error {
		stopCore(cmd, done)
		closeGuard(job)
		a.mu.Lock()
		if a.core == cmd {
			a.core = nil
			a.job = 0
		}
		a.mu.Unlock()
		return err
	}
	ready := false
	deadline := time.Now().Add(15 * time.Second)
	for time.Now().Before(deadline) {
		select {
		case <-ctx.Done():
			return fail(ctx.Err())
		case <-done:
			return fail(fmt.Errorf("موتور متوقف شد: %s", log.String()))
		default:
		}
		conn, er := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", port), 150*time.Millisecond)
		if er == nil {
			conn.Close()
			ready = true
			break
		}
		select {
		case <-ctx.Done():
			return fail(ctx.Err())
		case <-time.After(150 * time.Millisecond):
		}
	}
	if !ready {
		return fail(errors.New("موتور آماده نشد: " + log.String()))
	}
	ts = verificationTargets(e, settings, f)
	verifyCtx, cancel := context.WithTimeout(ctx, time.Duration(settings.Timeout*len(ts)+2)*time.Second)
	_, err = (Probe{}).inspect(verifyCtx, Endpoint{Host: "127.0.0.1", Port: port, Type: "SOCKS5"}, 8, ts)
	cancel()
	if err != nil {
		return fail(fmt.Errorf("تأیید مسیر پروکسی: %w", err))
	}
	if ctx.Err() != nil {
		return fail(ctx.Err())
	}
	a.mu.Lock()
	a.vpnState = "on"
	a.connected = &e
	a.corePort = port
	a.statsPort = statsPort
	a.activeSettings = settings
	a.trafficDown = 0
	a.trafficUp = 0
	a.trafficAt = time.Time{}
	a.recordLocked("اتصال برقرار شد: " + e.key())
	a.mu.Unlock()
	go func() {
		<-done
		closeGuard(job)
		a.mu.Lock()
		if a.core == cmd {
			a.core = nil
			a.job = 0
			a.connected = nil
			a.corePort = 0
			a.statsPort = 0
			a.disk.Traffic.DownRate = 0
			a.disk.Traffic.UpRate = 0
			if a.vpnState == "on" {
				a.vpnState = "off"
				a.cooldown[e.key()] = time.Now()
				a.err = "موتور اتصال متوقف شد: " + log.String()
				a.recordLocked(a.err)
				if a.disk.Settings.Failover && !a.userDisconnect && !a.recovering {
					go a.recoverConnection()
				}
			}
		}
		a.mu.Unlock()
	}()
	return nil
}
func (a *App) disconnect() {
	a.connectMu.Lock()
	defer a.connectMu.Unlock()
	a.mu.Lock()
	cmd, done := a.core, a.coreDone
	if cmd == nil {
		a.vpnState = "off"
		a.connected = nil
		a.corePort = 0
		a.statsPort = 0
		a.disk.Traffic.DownRate = 0
		a.disk.Traffic.UpRate = 0
		a.mu.Unlock()
		return
	}
	a.vpnState = "stopping"
	a.mu.Unlock()
	stopCore(cmd, done)
	a.mu.Lock()
	a.vpnState = "off"
	a.connected = nil
	a.corePort = 0
	a.statsPort = 0
	a.disk.Traffic.DownRate = 0
	a.disk.Traffic.UpRate = 0
	a.recordLocked("اتصال قطع شد")
	a.mu.Unlock()
}

func (a *App) monitor(done <-chan struct{}) {
	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()
	failed := 0
	last := ""
	for {
		select {
		case <-done:
			return
		case <-ticker.C:
			a.mu.Lock()
			if !a.disk.Settings.Failover || a.vpnState != "on" || a.connected == nil {
				a.mu.Unlock()
				failed = 0
				continue
			}
			e := *a.connected
			port, cmd := a.corePort, a.core
			settings, f := a.activeSettings, a.disk.Filter
			a.mu.Unlock()
			if last != e.key() {
				last = e.key()
				failed = 0
			}
			ts := verificationTargets(e, settings, f)
			ctx, cancel := context.WithTimeout(context.Background(), time.Duration(8*len(ts))*time.Second)
			_, err := (Probe{}).inspect(ctx, Endpoint{Host: "127.0.0.1", Port: port, Type: "SOCKS5"}, 6, ts)
			cancel()
			if err == nil {
				failed = 0
				continue
			}
			failed++
			if failed < 2 {
				continue
			}
			failed = 0
			a.mu.Lock()
			if cmd != a.core || a.vpnState != "on" || a.userDisconnect {
				a.mu.Unlock()
				continue
			}
			a.cooldown[e.key()] = time.Now()
			a.recordLocked("دو تست مسیر ناموفق؛ تعویض خودکار")
			if a.connectCancel != nil {
				a.connectCancel()
			}
			a.mu.Unlock()
			a.disconnect()
			go a.recoverConnection()
		}
	}
}
func (a *App) recoverConnection() {
	a.mu.Lock()
	if a.recovering || a.userDisconnect || !a.disk.Settings.Failover {
		a.mu.Unlock()
		return
	}
	a.recovering = true
	generation := a.controlGeneration
	a.mu.Unlock()
	defer func() { a.mu.Lock(); a.recovering = false; a.mu.Unlock() }()
	// Keep healthy entries while the physical adapter is offline; retry discovery every ten seconds.
	for {
		a.mu.Lock()
		stopped := a.userDisconnect || generation != a.controlGeneration
		a.mu.Unlock()
		if stopped {
			return
		}
		p, err := physicalNetwork()
		if err == nil && physicalReady(p) {
			a.mu.Lock()
			a.physical = p
			a.mu.Unlock()
			break
		}
		a.mu.Lock()
		a.message = "اینترنت مستقیم قطع است؛ منتظر بازگشت شبکه"
		a.mu.Unlock()
		for n := 0; n < 100; n++ {
			time.Sleep(100 * time.Millisecond)
			a.mu.Lock()
			stopped = a.userDisconnect || generation != a.controlGeneration
			a.mu.Unlock()
			if stopped {
				return
			}
		}
	}
	attempted := map[string]bool{}
	for n := 0; n < 8; n++ {
		a.mu.Lock()
		if a.userDisconnect || generation != a.controlGeneration || !a.disk.Settings.Failover || a.vpnState != "off" {
			a.mu.Unlock()
			return
		}
		rows := a.rowsLocked(a.disk.Filter)
		key := ""
		for _, e := range rows {
			if !attempted[e.key()] && time.Since(a.cooldown[e.key()]) > 5*time.Minute {
				key = e.key()
				break
			}
		}
		a.mu.Unlock()
		if key == "" {
			break
		}
		attempted[key] = true
		if err := a.connectMode(key, false); err != nil {
			continue
		}
		for {
			time.Sleep(100 * time.Millisecond)
			a.mu.Lock()
			state := a.vpnState
			stopped := a.userDisconnect || generation != a.controlGeneration
			a.mu.Unlock()
			if stopped || state == "on" {
				return
			}
			if state == "off" {
				break
			}
		}
	}
	a.mu.Lock()
	a.err = "جایگزین سالمی با فیلتر فعلی یافت نشد؛ لیست را دوباره اسکن کن"
	a.recordLocked(a.err)
	a.mu.Unlock()
	a.saveChecked()
}
