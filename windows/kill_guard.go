package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"sync"
	"time"
)

func (a *App) runtimeCore() (string, error) {
	if runtime.GOOS == "windows" {
		return prepareBundledRuntime(filepath.Join(a.dir, "runtime", "1.14.3"))
	}
	exe, _ := os.Executable()
	return filepath.Join(filepath.Dir(exe), "bin", "sing-box"), nil
}
func makeGuardConfig(p Physical, port int, s Settings) (map[string]any, error) {
	cfg, err := makeTunnelConfig(Endpoint{Host: "127.0.0.1", Port: 9, Type: "SOCKS5"}, p, port, 0, "", s)
	if err != nil {
		return nil, err
	}
	route := cfg["route"].(map[string]any)
	for _, r := range route["rules"].([]any) {
		m := r.(map[string]any)
		if m["outbound"] == "proxy" {
			delete(m, "outbound")
			m["action"] = "reject"
		}
	}
	route["rules"] = append(route["rules"].([]any), map[string]any{"inbound": "tun", "action": "reject"})
	dns := cfg["dns"].(map[string]any)
	dns["rules"] = []any{map[string]any{"query_type": []string{"A", "AAAA"}, "action": "route", "server": "route-fakeip"}}
	if s.RoutingMode == "domains" || s.RoutingMode == "chatgpt" {
		ds, _ := routeDomains(s)
		dns["rules"] = append(dns["rules"].([]any), map[string]any{"domain_suffix": ds, "action": "reject"})
	}
	dns["final"] = "local-dns"
	return cfg, nil
}
func (a *App) startGuardLocked(s Settings) error {
	a.mu.Lock()
	p := a.physical
	a.mu.Unlock()
	if !physicalReady(p) {
		return errors.New("مسیر اینترنت هنوز آماده نیست؛ حفاظت فایروال روشن می‌ماند")
	}
	bin, err := a.runtimeCore()
	if err != nil {
		return err
	}
	if err = a.armKillLocked(bin); err != nil {
		return err
	}
	l, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		return err
	}
	port := l.Addr().(*net.TCPAddr).Port
	l.Close()
	cfg, err := makeGuardConfig(p, port, s)
	if err != nil {
		return err
	}
	cfg["experimental"] = map[string]any{"cache_file": map[string]any{"enabled": true, "path": filepath.Join(a.dir, "route-cache.db"), "store_fakeip": true}}
	b, _ := json.Marshal(cfg)
	file := filepath.Join(a.dir, "guard.json")
	if err = os.WriteFile(file, b, 0600); err != nil {
		return err
	}
	log := &logTail{}
	cmd := exec.Command(bin, "run", "-c", file)
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
	closeJob := sync.OnceFunc(func() { closeGuard(job) })
	done := make(chan error, 1)
	go func() { done <- cmd.Wait(); close(done) }()
	fail := func(err error) error { stopCore(cmd, done); closeJob(); flushDNS(); return err }
	ready := false
	until := time.Now().Add(15 * time.Second)
	for time.Now().Before(until) {
		a.mu.Lock()
		closing := a.closing
		a.mu.Unlock()
		if closing {
			return fail(errors.New("برنامه در حال خروج است"))
		}
		select {
		case <-done:
			return fail(fmt.Errorf("موتور محافظ متوقف شد: %s", log.String()))
		default:
		}
		c, e := net.DialTimeout("tcp4", fmt.Sprintf("127.0.0.1:%d", port), 100*time.Millisecond)
		if e == nil {
			c.Close()
			ready = true
			break
		}
		time.Sleep(100 * time.Millisecond)
	}
	if !ready {
		return fail(errors.New("موتور محافظ آماده نشد: " + log.String()))
	}
	if err = a.armKillLocked(bin); err != nil {
		return fail(err)
	}
	flushDNS()
	a.mu.Lock()
	a.core = cmd
	a.coreDone = done
	a.coreLog = log
	a.job = job
	a.guardCloser = closeJob
	a.guardMode = true
	a.corePhysical = p
	a.activeSettings = s
	a.killError = ""
	a.message = "کیل‌سوییچ روشن: مسیرهای محافظت‌شده تا اتصال VPN مسدودند"
	a.mu.Unlock()
	go func() {
		<-done
		closeJob()
		a.mu.Lock()
		same := a.core == cmd
		if same {
			a.core = nil
			a.guardMode = false
			a.job = 0
		}
		closing := a.closing
		a.mu.Unlock()
		if same && !closing {
			go a.ensureProtection()
		}
	}()
	return nil
}
