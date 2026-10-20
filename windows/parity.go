package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"sync"
	"time"
)

type Filter struct {
	Country   string `json:"country"`
	Favorites bool   `json:"favorites"`
	Service   string `json:"service"`
	Type      string `json:"type"`
	Ping      int    `json:"ping"`
	Sort      string `json:"sort"`
}
type TestResult struct {
	Kind       string  `json:"kind"`
	Ping       int64   `json:"ping"`
	Speed      float64 `json:"speed"`
	Bytes      int64   `json:"bytes"`
	At         int64   `json:"at"`
	Error      string  `json:"error"`
	ProbeError string  `json:"probeError"`
	SpeedError string  `json:"speedError"`
}
type TestTask struct {
	Kind     string  `json:"kind"`
	Speed    float64 `json:"speed"`
	Bytes    int64   `json:"bytes"`
	Started  int64   `json:"started"`
	cancel   context.CancelFunc
	Batch    bool `json:"batch"`
	finished chan struct{}
}
type Traffic struct {
	Down     int64   `json:"down"`
	Up       int64   `json:"up"`
	DownRate float64 `json:"downRate"`
	UpRate   float64 `json:"upRate"`
}
type InstalledApp struct {
	Name   string `json:"name"`
	Path   string `json:"path"`
	System bool   `json:"system"`
}

func hasService(e Endpoint, id string) bool {
	for _, s := range e.Services {
		if s == id {
			return true
		}
	}
	return false
}
func filterAccepts(e Endpoint, f Filter, tlsOnly bool) bool {
	if !countryAccepts(e.Country, f.Country) {
		return false
	}
	if tlsOnly && e.Type != "HTTPS" {
		return false
	}
	if f.Type == "HTTP_GROUP" {
		if e.Type != "HTTP" && e.Type != "HTTPS" {
			return false
		}
	} else if f.Type != "" && f.Type != "ALL" && e.Type != f.Type {
		return false
	}
	if f.Ping > 0 && (e.Ping <= 0 || e.Ping >= int64(f.Ping)) {
		return false
	}
	if f.Service == "full" {
		for _, t := range targets {
			if !hasService(e, t.ID) {
				return false
			}
		}
	} else if f.Service != "" && f.Service != "all" && !hasService(e, f.Service) {
		return false
	}
	return true
}
func filteredRows(results map[string]Endpoint, f Filter, tlsOnly bool) []Endpoint {
	rows := []Endpoint{}
	for _, e := range results {
		if filterAccepts(e, f, tlsOnly) {
			rows = append(rows, e)
		}
	}
	sort.Slice(rows, func(i, j int) bool {
		a, b := rows[i], rows[j]
		switch f.Sort {
		case "speed":
			if a.Speed != b.Speed {
				return a.Speed > b.Speed
			}
		case "services":
			if len(a.Services) != len(b.Services) {
				return len(a.Services) > len(b.Services)
			}
		case "recent":
			if a.Updated != b.Updated {
				return a.Updated > b.Updated
			}
		case "udp":
			if a.UDP != b.UDP {
				return a.UDP
			}
		}
		if a.Ping != b.Ping {
			if a.Ping <= 0 {
				return false
			}
			if b.Ping <= 0 {
				return true
			}
			return a.Ping < b.Ping
		}
		return a.key() < b.key()
	})
	return rows
}
func validateFilter(f Filter, s Settings) error {
	if err := validateCountry(f.Country); err != nil {
		return err
	}
	switch f.Type {
	case "", "ALL", "SOCKS5", "HTTP", "HTTPS", "HTTP_GROUP":
	default:
		return errors.New("نوع فیلتر نامعتبر است")
	}
	switch f.Sort {
	case "", "ping", "speed", "services", "recent", "udp":
	default:
		return errors.New("مرتب‌سازی نامعتبر است")
	}
	switch f.Ping {
	case 0, 500, 1000, 1500, 2000:
	default:
		return errors.New("فیلتر پینگ نامعتبر است")
	}
	if f.Service == "" || f.Service == "all" || f.Service == "full" {
		return nil
	}
	ts, _ := customTargets(s.CustomTargets)
	for _, t := range ts {
		if f.Service == t.ID {
			return nil
		}
	}
	return errors.New("دامنهٔ فیلتر موجود نیست")
}
func scanAccepts(e Endpoint, s Settings) bool {
	if !countryAccepts(e.Country, s.ScanCountry) {
		return false
	}
	if s.TLSOnly && e.Type != "HTTPS" {
		return false
	}
	if len(s.ScanTypes) == 0 {
		return true
	}
	for _, t := range s.ScanTypes {
		if t == e.Type {
			return true
		}
	}
	return false
}
func parseCustomSources(raw string) ([]Source, error) {
	out := []Source{}
	for _, line := range strings.Split(raw, "\n") {
		fields := strings.Fields(line)
		if len(fields) == 0 {
			continue
		}
		if len(fields) > 2 {
			return nil, errors.New("هر منبع: نوع و سپس نشانی HTTPS")
		}
		typ, u := "SOCKS5", fields[0]
		if len(fields) == 2 {
			typ, u = strings.ToUpper(fields[0]), fields[1]
		}
		r, err := http.NewRequest("GET", u, nil)
		if err != nil || r.URL.Scheme != "https" || r.URL.User != nil || !validPublicHostname(r.URL.Hostname()) || (typ != "SOCKS5" && typ != "HTTP" && typ != "HTTPS") {
			return nil, errors.New("منبع باید HTTPS و نوع آن SOCKS5، HTTP یا HTTPS باشد")
		}
		if len(out) >= 20 {
			return nil, errors.New("حداکثر ۲۰ منبع دلخواه")
		}
		out = append(out, Source{Name: fmt.Sprintf("دلخواه %d", len(out)+1), URL: u, Type: typ})
	}
	return out, nil
}
func validPublicHostname(host string) bool {
	host = strings.ToLower(host)
	if net.ParseIP(host) != nil {
		return false
	}
	if len(host) > 253 || !strings.Contains(host, ".") || strings.HasSuffix(host, ".local") || strings.HasSuffix(host, ".localhost") {
		return false
	}
	for _, label := range strings.Split(host, ".") {
		if len(label) == 0 || len(label) > 63 || label[0] == '-' || label[len(label)-1] == '-' {
			return false
		}
		for _, c := range label {
			if !(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-') {
				return false
			}
		}
	}
	return true
}

var windowsEXE = regexp.MustCompile(`(?i)^[a-z]:\\[^\r\n<>|?*]+\.exe$`)

func validExecutablePath(path string) bool {
	return windowsEXE.MatchString(path) || (filepath.IsAbs(path) && strings.EqualFold(filepath.Ext(path), ".exe") && !strings.ContainsAny(path, "\r\n"))
}
func splitRules(s Settings) ([]any, error) {
	if s.SplitMode == "" || s.SplitMode == "all" {
		return nil, nil
	}
	paths := []string{}
	seen := map[string]bool{}
	for _, path := range s.SplitApps {
		info, err := os.Stat(path)
		if err != nil {
			// Store package paths change with app updates; keep their identity matcher.
			packaged := strings.Contains(strings.ToLower(strings.ReplaceAll(path, `\`, "/")), "/windowsapps/") && len(appFamilyPatterns(path)) > 0
			if !os.IsPermission(err) && !packaged {
				continue
			}
		} else if info.IsDir() {
			continue
		}
		clean := filepath.Clean(path)
		if !seen[strings.ToLower(clean)] {
			seen[strings.ToLower(clean)] = true
			paths = append(paths, `(?i)^`+regexp.QuoteMeta(clean)+`$`)
			if !s.SplitExact {
				paths = append(paths, appFamilyPatterns(clean)...)
			}
		}
	}
	if len(paths) == 0 && s.SplitMode == "only" {
		return nil, errors.New("هیچ برنامهٔ انتخاب‌شده‌ای روی این رایانه موجود نیست؛ اسپلیت تونل را بررسی کن")
	}
	out := []any{}
	if len(paths) > 0 {
		outbound := "direct"
		if s.SplitMode == "only" {
			outbound = "proxy"
		}
		out = append(out, map[string]any{"inbound": "tun", "process_path_regex": paths, "action": "route", "outbound": outbound})
	}
	if s.SplitMode == "only" {
		out = append(out, map[string]any{"inbound": "tun", "action": "route", "outbound": "direct"})
	}
	return out, nil
}
func latencyTargets(e Endpoint, s Settings, f Filter) []Target {
	ts, _ := customTargets(s.CustomTargets)
	for _, t := range ts {
		if f.Service == t.ID {
			return []Target{t}
		}
	}
	for _, t := range ts {
		if hasService(e, t.ID) {
			return []Target{t}
		}
	}
	return ts[:1]
}
func verificationTargets(e Endpoint, s Settings, f Filter) []Target {
	if f.Service == "full" {
		return targets
	}
	return latencyTargets(e, s, f)
}
func (a *App) recordLocked(msg string) {
	a.report = append(a.report, time.Now().Format("15:04:05")+"  "+msg)
	if len(a.report) > 300 {
		a.report = a.report[len(a.report)-300:]
	}
}
func (a *App) reportText() string {
	a.mu.Lock()
	defer a.mu.Unlock()
	text := fmt.Sprintf("PersianSocks Windows %s\nلیست: %d • سالم: %d\nشبکه: %s\nاتصال: %s\n%s\n\n%s\n\n%s", version, len(a.disk.Raw), len(a.disk.Results), a.physical.Name, a.vpnState, a.err, strings.Join(a.sourceReport, "\n"), strings.Join(a.report, "\n"))
	if a.coreLog != nil {
		text += "\n\n" + a.coreLog.String()
	}
	return text
}
func (a *App) applySettings(s Settings) error {
	if len(s.ScanTypes) == 0 {
		return errors.New("حداقل یک نوع اسکن انتخاب کن")
	}
	if s.SplitMode == "" {
		s.SplitMode = "all"
	}
	if err := validateSettings(s); err != nil {
		return err
	}
	a.mu.Lock()
	old := a.disk.Settings
	changed := old.TLSOnly != s.TLSOnly || old.CustomTargets != s.CustomTargets
	if changed {
		a.autoConnect = false
		if a.scanCancel != nil {
			a.scanCancel()
		}
		if a.fetchCancel != nil {
			a.fetchCancel()
		}
		for _, t := range a.tasks {
			t.cancel()
		}
	}
	routingChanged := routingSignature(old) != routingSignature(s)
	reconnectKey := ""
	if routingChanged && a.vpnState != "off" {
		reconnectKey = a.disk.Selected
	}
	disconnect := (old.TLSOnly != s.TLSOnly || routingChanged) && a.vpnState != "off"
	if disconnect {
		a.userDisconnect = true
		a.controlGeneration++
		if a.connectCancel != nil {
			a.connectCancel()
		}
	}
	a.disk.Settings = s
	if validateFilter(a.disk.Filter, s) != nil {
		a.disk.Filter.Service = ""
	}
	generation := a.controlGeneration
	a.recordLocked("تنظیمات ذخیره شد؛ تغییر مسیرهای اتصال روی تونل تازه اعمال می‌شود")
	a.mu.Unlock()
	if disconnect {
		go func() {
			a.disconnect()
			if reconnectKey != "" {
				a.mu.Lock()
				ok := generation == a.controlGeneration
				a.mu.Unlock()
				if ok {
					if err := a.connect(reconnectKey); err != nil {
						a.mu.Lock()
						a.err = err.Error()
						a.mu.Unlock()
					}
				}
			}
		}()
	}
	a.saveChecked()
	return nil
}
func (a *App) runSingle(key, kind string) error {
	_, err := a.runSingleTask(key, kind, context.Background(), false)
	return err
}
func (a *App) runSingleTask(key, kind string, parent context.Context, batch bool) (*TestTask, error) {
	a.mu.Lock()
	e, ok := a.endpointLocked(key)
	if !ok {
		a.mu.Unlock()
		return nil, errMissingProxy
	}
	if !physicalReady(a.physical) {
		a.mu.Unlock()
		return nil, errors.New("شبکهٔ اصلی آماده نیست")
	}
	if a.tasks[key] != nil || len(a.tasks) >= 2 {
		a.mu.Unlock()
		return nil, errTestBusy
	}
	if parent.Err() != nil {
		a.mu.Unlock()
		return nil, parent.Err()
	}
	ctx, cancel := context.WithCancel(parent)
	task := &TestTask{Kind: kind, Started: time.Now().UnixMilli(), cancel: cancel, Batch: batch, finished: make(chan struct{})}
	a.tasks[key] = task
	a.test[key] = true
	a.sequence++
	claim := a.sequence
	if kind != "speed" {
		a.attemptVersion[key] = claim
	}
	p, s, f := a.physical, a.disk.Settings, a.disk.Filter
	runner := a.speedRunner
	a.mu.Unlock()
	go func() {
		defer cancel()
		defer close(task.finished)
		var fresh Endpoint
		var speed float64
		var n int64
		var err error
		if kind == "speed" {
			progress := func(rate float64, n int64) {
				a.mu.Lock()
				if task.Batch {
					delta := n - task.Bytes
					if delta > 0 {
						a.speedBatch.Bytes += delta
					}
				}
				task.Speed = rate
				task.Bytes = n
				a.mu.Unlock()
			}
			if runner != nil {
				speed, n, err = runner(ctx, p, e, progress)
			} else {
				speed, n, err = (Probe{Physical: p}).speed(ctx, e, progress)
			}
		} else {
			ts, _ := customTargets(s.CustomTargets)
			if kind == "ping" {
				ts = latencyTargets(e, s, f)
			}
			fresh, err = (Probe{Physical: p}).inspect(ctx, e, s.Timeout, ts)
		}
		a.mu.Lock()
		canceled := ctx.Err() != nil
		res := a.disk.Tests[key]
		res.Kind = kind
		res.At = time.Now().UnixMilli()
		res.Error = ""
		if err != nil {
			res.Error = err.Error()
		}
		if canceled {
			res.Error = "تست متوقف شد"
		}
		if kind == "speed" {
			if task.Batch && n > task.Bytes {
				a.speedBatch.Bytes += n - task.Bytes
			}
			task.Bytes = n
			res.Bytes = n
			res.SpeedError = res.Error
			if err == nil && !canceled {
				res.Speed = speed
				if current, exists := a.endpointLocked(key); exists {
					current.Speed = speed
					current.Note = ""
					if _, healthy := a.disk.Results[key]; healthy {
						a.disk.Results[key] = current
					} else {
						a.disk.Favorites[key] = current
					}
				}
			}
		} else {
			res.ProbeError = res.Error
			res.Ping = fresh.Ping
			if !canceled && a.attemptVersion[key] == claim {
				if err != nil {
					delete(a.disk.Results, key)
				} else {
					if kind == "ping" {
						e.Ping = fresh.Ping
						e.Updated = fresh.Updated
						a.disk.Results[key] = e
					} else {
						fresh.Speed = a.disk.Results[key].Speed
						a.disk.Results[key] = fresh
					}
				}
			}
		}
		a.disk.Tests[key] = res
		a.recordLocked(fmt.Sprintf("%s • %s • %s", kind, key, res.Error))
		a.mu.Unlock()
		a.saveChecked()
		a.mu.Lock()
		delete(a.tasks, key)
		delete(a.test, key)
		a.mu.Unlock()
	}()
	return task, nil
}
func (a *App) fastPing() error {
	a.mu.Lock()
	if a.scanRunning {
		a.mu.Unlock()
		return errors.New("ابتدا اسکن جاری را متوقف کن")
	}
	if !physicalReady(a.physical) {
		a.mu.Unlock()
		return errors.New("شبکهٔ اصلی آماده نیست")
	}
	f := a.disk.Filter
	f.Sort = "ping"
	rows := a.rowsLocked(f)
	if len(rows) == 0 {
		a.mu.Unlock()
		return errors.New("پروکسی سالمی با این فیلتر نیست")
	}
	if len(rows) > 20 {
		rows = rows[:20]
	}
	ctx, cancel := context.WithCancel(context.Background())
	a.scanCancel = cancel
	a.scanRunning = true
	a.fastScan = true
	a.lastDone = 0
	a.lastTotal = len(rows)
	p, s := a.physical, a.disk.Settings
	a.message = "بازسنجی ۲۰ سریع‌ترین"
	a.mu.Unlock()

	go func() {
		defer cancel()
		jobs := make(chan Endpoint)
		var wg sync.WaitGroup
		for n := 0; n < 4; n++ {
			wg.Add(1)
			go func() {
				defer wg.Done()
				for e := range jobs {
					if ctx.Err() != nil {
						continue
					}
					a.mu.Lock()
					a.sequence++
					claim := a.sequence
					a.attemptVersion[e.key()] = claim
					a.mu.Unlock()
					fresh, err := (Probe{Physical: p}).inspect(ctx, e, s.Timeout, latencyTargets(e, s, f))
					a.mu.Lock()
					if ctx.Err() == nil {
						a.lastDone++
						if a.attemptVersion[e.key()] == claim {
							if err != nil {
								delete(a.disk.Results, e.key())
							} else if current, ok := a.disk.Results[e.key()]; ok {
								current.Ping = fresh.Ping
								current.Updated = fresh.Updated
								a.disk.Results[e.key()] = current
							}
						}
					}
					a.mu.Unlock()
				}
			}()
		}
	loop:
		for _, e := range rows {
			select {
			case jobs <- e:
			case <-ctx.Done():
				break loop
			}
		}
		close(jobs)
		wg.Wait()
		a.mu.Lock()
		a.scanRunning = false
		a.scanCancel = nil
		a.message = "بازسنجی پایان یافت"
		a.recordLocked(a.message)
		a.mu.Unlock()
		a.saveChecked()
	}()
	return nil
}
func (a *App) loadApps() {
	a.mu.Lock()
	if a.appsLoading {
		a.mu.Unlock()
		return
	}
	a.appsLoading = true
	a.appsLoaded = false
	a.appsError = ""
	a.mu.Unlock()
	go func() {
		apps, err := installedApps()
		a.mu.Lock()
		a.apps = apps
		a.appsLoading = false
		a.appsLoaded = true
		if err != nil {
			a.appsError = err.Error()
		}
		a.mu.Unlock()
	}()
}
func (a *App) connectBest() error {
	a.mu.Lock()
	rows := a.rowsLocked(a.disk.Filter)
	a.mu.Unlock()
	if len(rows) == 0 {
		return errors.New("پروکسی سالمی با فیلتر فعلی نیست")
	}
	err := a.connect(rows[0].key())
	if err != nil {
		a.mu.Lock()
		a.err = err.Error()
		a.mu.Unlock()
	}
	return err
}
func (a *App) quickToggle(auto bool) error {
	a.mu.Lock()
	busy := a.vpnState != "off"
	scanning := a.scanRunning
	fetching := a.fetching
	a.mu.Unlock()
	if busy {
		return a.act(Action{Action: "disconnect"})
	}
	if scanning {
		return a.act(Action{Action: "stop"})
	}
	if fetching {
		return errors.New("ابتدا دریافت لیست را تمام کن")
	}
	if !auto {
		return a.connectBest()
	}
	a.mu.Lock()
	a.autoConnect = true
	a.userDisconnect = false
	a.mu.Unlock()
	if err := a.startScan("scan"); err != nil {
		a.mu.Lock()
		a.autoConnect = false
		a.mu.Unlock()
		return err
	}
	return nil
}
func (a *App) sampleTrafficLocked(down, up int64, at time.Time) {
	d, u := down-a.trafficDown, up-a.trafficUp
	if d < 0 {
		d = down
	}
	if u < 0 {
		u = up
	}
	a.disk.Traffic.Down += d
	a.disk.Traffic.Up += u
	dt := at.Sub(a.trafficAt).Seconds()
	a.disk.Traffic.DownRate = 0
	a.disk.Traffic.UpRate = 0
	if !a.trafficAt.IsZero() && dt > 0 {
		a.disk.Traffic.DownRate = float64(d) / dt
		a.disk.Traffic.UpRate = float64(u) / dt
	}
	a.trafficDown = down
	a.trafficUp = up
	a.trafficAt = at
}
func (a *App) pollTraffic(done <-chan struct{}) {
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	client := &http.Client{Timeout: 800 * time.Millisecond, Transport: &http.Transport{Proxy: nil}}
	for {
		select {
		case <-done:
			return
		case at := <-ticker.C:
			a.mu.Lock()
			port, cmd := a.statsPort, a.core
			token := a.token
			on := a.vpnState == "on"
			a.mu.Unlock()
			if !on || port == 0 {
				continue
			}
			req, _ := http.NewRequest("GET", fmt.Sprintf("http://127.0.0.1:%d/connections", port), nil)
			req.Header.Set("Authorization", "Bearer "+token)
			resp, err := client.Do(req)
			if err != nil {
				a.mu.Lock()
				a.disk.Traffic.DownRate = 0
				a.disk.Traffic.UpRate = 0
				a.mu.Unlock()
				continue
			}
			var data struct {
				Down int64 `json:"downloadTotal"`
				Up   int64 `json:"uploadTotal"`
			}
			err = json.NewDecoder(io.LimitReader(resp.Body, 4_000_000)).Decode(&data)
			resp.Body.Close()
			if err == nil && resp.StatusCode == 200 && data.Down >= 0 && data.Up >= 0 {
				a.mu.Lock()
				if cmd == a.core && port == a.statsPort {
					a.sampleTrafficLocked(data.Down, data.Up, at)
				}
				a.mu.Unlock()
			}
		}
	}
}

// Native WM_CLOSE is authoritative. Hiding the UI never owns backend cancellation.
func (a *App) windowClosed() {
	a.mu.Lock()
	exit := a.disk.Settings.ExitOnClose
	shutdown := a.shutdown
	message := "پنجره بسته شد؛ برنامه در پس‌زمینه ادامه دارد"
	if exit {
		message = "خروج کامل با بستن پنجره"
	}
	a.recordLocked(message)
	a.mu.Unlock()
	if exit && shutdown != nil {
		go shutdown()
	}
}

func countryAccepts(country, filter string) bool {
	return filter == "" || (filter == "UNKNOWN" && country == "") || country == filter
}
func validateCountry(c string) error {
	if c != "" && c != "UNKNOWN" && (len(c) != 2 || c[0] < 'A' || c[0] > 'Z' || c[1] < 'A' || c[1] > 'Z') {
		return errors.New("کشور نامعتبر است")
	}
	return nil
}
