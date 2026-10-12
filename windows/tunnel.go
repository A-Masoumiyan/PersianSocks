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
	"sort"
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
	return map[string]any{
		"log":       map[string]any{"level": "warn", "timestamp": true},
		"dns":       map[string]any{"servers": []any{map[string]any{"type": "https", "tag": "remote-dns", "server": "1.1.1.1", "tls": map[string]any{"enabled": true, "server_name": "cloudflare-dns.com"}, "detour": "proxy"}}, "final": "remote-dns", "strategy": "ipv4_only"},
		"inbounds":  []any{map[string]any{"type": "tun", "tag": "tun", "interface_name": "PersianSocks-TUN", "address": []string{"172.29.251.1/30", "fdfe:dcba:9876::1/126"}, "mtu": 1500, "auto_route": true, "strict_route": false, "dns_mode": "hijack", "stack": "mixed"}, map[string]any{"type": "mixed", "tag": "local-proxy", "listen": "127.0.0.1", "listen_port": proxyPort}},
		"outbounds": []any{out, map[string]any{"type": "direct", "tag": "direct", "bind_interface": p.Name}},
		"route":     map[string]any{"default_interface": p.Name, "final": "proxy", "rules": []any{map[string]any{"inbound": "tun", "process_path": exe, "action": "route", "outbound": "direct"}, map[string]any{"protocol": "dns", "action": "hijack-dns"}, map[string]any{"inbound": "tun", "ip_is_private": true, "action": "route", "outbound": "direct"}}},
	}
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
func (a *App) connect(key string) error {
	a.mu.Lock()
	if a.vpnState != "off" {
		a.mu.Unlock()
		return errors.New("ابتدا اتصال قبلی را قطع کن")
	}
	e, ok := a.disk.Results[key]
	if !ok {
		a.mu.Unlock()
		return errors.New("یک پروکسی سالم انتخاب کن")
	}
	if !physicalReady(a.physical) {
		a.mu.Unlock()
		return errors.New("کارت شبکهٔ اصلی شناسایی نشده؛ از تنظیمات، شبکه را تازه‌سازی کن")
	}
	ctx, cancel := context.WithCancel(context.Background())
	a.connectCancel = cancel
	a.vpnState = "connecting"
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
		a.mu.Unlock()
	}()
	return nil
}
func (a *App) startCore(ctx context.Context, e Endpoint, p Physical) error {
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
	b, err := json.MarshalIndent(tunnelConfig(e, p, port), "", "  ")
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
	ts := []Target{targets[0]}
	for _, t := range targets {
		for _, s := range e.Services {
			if s == t.ID {
				ts = []Target{t}
				break
			}
		}
	}
	verifyCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
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
	a.mu.Unlock()
	go func() {
		<-done
		closeGuard(job)
		a.mu.Lock()
		if a.core == cmd {
			a.core = nil
			a.job = 0
			a.connected = nil
			if a.vpnState == "on" {
				a.vpnState = "off"
				a.err = "موتور اتصال متوقف شد: " + log.String()
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
		a.mu.Unlock()
		return
	}
	a.vpnState = "stopping"
	a.mu.Unlock()
	stopCore(cmd, done)
	a.mu.Lock()
	a.vpnState = "off"
	a.connected = nil
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
			p := a.physical
			cmd := a.core
			a.mu.Unlock()
			key := e.key()
			if last != key {
				failed = 0
				last = key
			}
			ts := []Target{}
			for _, t := range targets {
				for _, id := range e.Services {
					if id == t.ID {
						ts = append(ts, t)
						break
					}
				}
			}
			if len(ts) == 0 {
				ts = targets[:1]
			} else {
				ts = ts[:1]
			}
			ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
			_, err := (Probe{Physical: p}).inspect(ctx, e, 6, ts)
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
			if a.core != cmd || a.vpnState != "on" {
				a.mu.Unlock()
				continue
			}
			delete(a.disk.Results, key)
			candidates := []Endpoint{}
			for _, candidate := range a.disk.Results {
				if candidate.key() == key || a.disk.Settings.TLSOnly && candidate.Type != "HTTPS" {
					continue
				}
				for _, id := range candidate.Services {
					if id == ts[0].ID {
						candidates = append(candidates, candidate)
						break
					}
				}
			}
			a.mu.Unlock()
			sort.Slice(candidates, func(i, j int) bool { return candidates[i].Ping < candidates[j].Ping })
			a.disconnect()
			a.saveChecked()
			if len(candidates) > 0 {
				a.connect(candidates[0].key())
			} else {
				a.mu.Lock()
				a.err = "اتصال قطع شد؛ جایگزین سالمی برای سرویس فعلی وجود ندارد"
				a.mu.Unlock()
			}
		}
	}
}
