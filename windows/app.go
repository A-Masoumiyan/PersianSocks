package main

import (
	"bufio"
	"context"
	"crypto/rand"
	"embed"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

//go:embed assets/* ui.html
var bundle embed.FS
var geo []byte

const version = "0.8.0"
const scanEngineVersion = 2
const maxScanWorkers = 48

type Source struct {
	Name     string `json:"name"`
	URL      string `json:"url"`
	Fallback string `json:"fallback"`
	Type     string `json:"type"`
}
type Settings struct {
	KillSwitchOff bool     `json:"killSwitchOff"`
	RoutingMode   string   `json:"routingMode"`
	RoutePresets  []string `json:"routePresets"`
	RouteDomains  string   `json:"routeDomains"`
	SplitExact    bool     `json:"splitExact"`
	ScanCountry   string   `json:"scanCountry"`
	ExitOnClose   bool     `json:"exitOnClose"`
	Workers       int      `json:"workers"`
	Timeout       int      `json:"timeout"`
	Max           int      `json:"max"`
	TLSOnly       bool     `json:"tlsOnly"`
	Failover      bool     `json:"failover"`
	CustomSources string   `json:"customSources"`
	CustomTargets string   `json:"customTargets"`
	ScanTypes     []string `json:"scanTypes"`
	Slow          bool     `json:"slow"`
	SplitMode     string   `json:"splitMode"`
	SplitApps     []string `json:"splitApps"`
	SplitShowAll  bool     `json:"splitShowAll"`
}
type Checkpoint struct {
	Input    []Endpoint `json:"input"`
	Done     []bool     `json:"done"`
	Settings Settings   `json:"settings"`
	Targets  []Target   `json:"targets"`
}
type Disk struct {
	Favorites  map[string]Endpoint   `json:"favorites,omitempty"`
	ScanEngine int                   `json:"scanEngine"`
	Raw        []Endpoint            `json:"raw"`
	Results    map[string]Endpoint   `json:"results"`
	Checkpoint *Checkpoint           `json:"checkpoint,omitempty"`
	Settings   Settings              `json:"settings"`
	Filter     Filter                `json:"filter"`
	Selected   string                `json:"selected"`
	Tests      map[string]TestResult `json:"tests"`
	Traffic    Traffic               `json:"traffic"`
}
type App struct {
	firewall          killFirewall
	guardCloser       func()
	protectionStarted bool
	killArmed         bool
	killError         string
	guardMode         bool
	corePhysical      Physical
	closing           bool
	mu                sync.Mutex
	saveMu            sync.Mutex
	disk              Disk
	dir               string
	physical          Physical
	scanCancel        context.CancelFunc
	scanRunning       bool
	fetching          bool
	message           string
	err               string
	scanError         string
	sourceReport      []string
	test              map[string]bool
	vpnState          string
	connected         *Endpoint
	connectCancel     context.CancelFunc
	core              *exec.Cmd
	coreDone          chan error
	coreLog           *logTail
	job               uintptr
	token             string
	origin            string
	lastSeen          time.Time
	speedBatchCancel  context.CancelFunc
	speedBatchDone    int
	speedBatchTotal   int
	speedBatch        SpeedBatch
	speedRunner       speedProbe
	shutdown          func()
	connectMu         sync.Mutex
	attemptVersion    map[string]uint64
	sequence          uint64
	lastDone          int
	lastTotal         int
	fetchCancel       context.CancelFunc
	tasks             map[string]*TestTask
	report            []string
	apps              []InstalledApp
	appsLoading       bool
	appsLoaded        bool
	appsError         string
	corePort          int
	statsPort         int
	trafficDown       int64
	trafficUp         int64
	trafficAt         time.Time
	activeSettings    Settings
	cooldown          map[string]time.Time
	recovering        bool
	autoConnect       bool
	userDisconnect    bool
	controlGeneration uint64
	fastScan          bool
}

func defaultSettings() Settings {
	return Settings{Workers: 24, Timeout: 8, Max: 5000, Failover: true, ScanTypes: []string{"SOCKS5", "HTTP", "HTTPS"}, SplitMode: "all"}
}
func validateSettings(s Settings) error {
	if err := validateRouting(s); err != nil {
		return err
	}
	if err := validateCountry(s.ScanCountry); err != nil {
		return err
	}
	if s.Workers < 1 || s.Workers > maxScanWorkers || s.Timeout < 3 || s.Timeout > 30 || s.Max < 0 || s.Max > 100000 {
		return errors.New("تعداد کارگر ۱ تا ۴۸، مهلت ۳ تا ۳۰ ثانیه و سقف ۰ تا ۱۰۰۰۰۰ است؛ صفر یعنی همه")
	}
	if _, err := customTargets(s.CustomTargets); err != nil {
		return err
	}
	if _, err := parseCustomSources(s.CustomSources); err != nil {
		return err
	}
	if s.SplitMode != "" && s.SplitMode != "all" && s.SplitMode != "only" && s.SplitMode != "except" {
		return errors.New("حالت اسپلیت تونل نامعتبر است")
	}
	if s.SplitMode == "only" && len(s.SplitApps) == 0 {
		return errors.New("حداقل یک برنامه انتخاب کن")
	}
	for _, path := range s.SplitApps {
		if !validExecutablePath(path) {
			return errors.New("مسیر فایل exe باید کامل و معتبر باشد")
		}
	}
	for _, typ := range s.ScanTypes {
		if typ != "SOCKS5" && typ != "HTTP" && typ != "HTTPS" {
			return errors.New("نوع اسکن نامعتبر است")
		}
	}
	return nil
}
func customTargets(raw string) ([]Target, error) {
	ts := append([]Target(nil), targets...)
	seen := map[string]bool{}
	for _, line := range strings.Split(raw, "\n") {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		name, u := "", line
		if parts := strings.SplitN(line, "|", 2); len(parts) == 2 {
			name = strings.TrimSpace(parts[0])
			u = strings.TrimSpace(parts[1])
		}
		if !strings.Contains(u, "://") {
			u = "https://" + u
		}
		r, err := http.NewRequest("GET", u, nil)
		if err != nil || r.URL.Scheme != "https" || r.URL.User != nil || !validPublicHostname(r.URL.Hostname()) || (r.URL.Port() != "" && r.URL.Port() != "443") {
			return nil, errors.New("مقصد باید دامنهٔ عمومی HTTPS باشد")
		}
		u = r.URL.String()
		if seen[u] {
			continue
		}
		seen[u] = true
		if len(seen) > 8 {
			return nil, errors.New("حداکثر ۸ مقصد دلخواه")
		}
		if name == "" {
			name = r.URL.Hostname()
		}
		if len([]rune(name)) > 30 {
			return nil, errors.New("نام مقصد حداکثر ۳۰ حرف است")
		}
		ts = append(ts, Target{ID: u, Name: name, URL: u})
	}
	return ts, nil
}
func newApp(dir string) *App {
	a := &App{dir: dir, disk: Disk{ScanEngine: scanEngineVersion, Results: map[string]Endpoint{}, Settings: defaultSettings()}, test: map[string]bool{}, attemptVersion: map[string]uint64{}, vpnState: "off", lastSeen: time.Now()}
	os.MkdirAll(dir, 0700)
	if b, err := os.ReadFile(filepath.Join(dir, "state.json")); err == nil && len(b) < 512_000_000 {
		var d Disk
		if json.Unmarshal(b, &d) == nil && d.Results != nil && validateSettings(d.Settings) == nil {
			a.disk = d
		}
	}
	migrated := a.disk.ScanEngine < scanEngineVersion
	if migrated {
		a.disk.Settings.Workers = min(maxScanWorkers, a.disk.Settings.Workers*2)
		if cp := a.disk.Checkpoint; cp != nil {
			cp.Settings.Workers = min(maxScanWorkers, cp.Settings.Workers*2)
		}
		a.disk.ScanEngine = scanEngineVersion
	}
	cp := a.disk.Checkpoint
	if cp != nil && (len(cp.Input) != len(cp.Done) || validateSettings(cp.Settings) != nil || len(cp.Targets) == 0) {
		a.disk.Checkpoint = nil
	}
	if a.disk.Favorites == nil {
		a.disk.Favorites = map[string]Endpoint{}
	}
	if a.disk.Tests == nil {
		a.disk.Tests = map[string]TestResult{}
	}
	if len(a.disk.Settings.ScanTypes) == 0 {
		a.disk.Settings.ScanTypes = defaultSettings().ScanTypes
	}
	if a.disk.Settings.SplitMode == "" {
		a.disk.Settings.SplitMode = "all"
	}
	a.disk.Traffic.DownRate = 0
	a.disk.Traffic.UpRate = 0
	a.tasks = map[string]*TestTask{}
	a.cooldown = map[string]time.Time{}
	if migrated {
		a.saveChecked()
	}
	return a
}
func (a *App) save() error {
	a.saveMu.Lock()
	defer a.saveMu.Unlock()
	a.mu.Lock()
	for k := range a.disk.Favorites {
		if e, ok := a.disk.Results[k]; ok {
			a.disk.Favorites[k] = e
		}
	}
	b, err := json.Marshal(a.disk)
	a.mu.Unlock()
	if err != nil {
		return err
	}
	p := filepath.Join(a.dir, "state.json")
	if err = os.WriteFile(p+".tmp", b, 0600); err == nil {
		err = os.Rename(p+".tmp", p)
	}
	return err
}
func (a *App) saveChecked() {
	if err := a.save(); err != nil {
		a.mu.Lock()
		a.err = "ذخیرهٔ نتایج ناموفق بود: " + err.Error()
		a.mu.Unlock()
	}
}
func balanced(groups [][]Endpoint) []Endpoint {
	out := make([]Endpoint, 0)
	seen := map[string]bool{}
	for i := 0; ; i++ {
		added := false
		for _, g := range groups {
			if i >= len(g) {
				continue
			}
			added = true
			e := g[i]
			if !seen[e.key()] {
				seen[e.key()] = true
				out = append(out, e)

			}
		}
		if !added {
			return out
		}
	}
}
func parseList(raw, typ, source string) []Endpoint {
	out := []Endpoint{}
	seen := map[string]bool{}
	for _, line := range strings.Split(raw, "\n") {
		if e, ok := parseEndpoint(line, typ, source); ok && !seen[e.key()] {
			e.Country = country(e.Host)
			seen[e.key()] = true
			out = append(out, e)

		}
	}
	return out
}
func (a *App) fetch() error {
	a.mu.Lock()
	if !physicalReady(a.physical) {
		a.mu.Unlock()
		return errors.New("کارت شبکهٔ اصلی هنوز آماده نیست")
	}
	if a.fetching {
		a.mu.Unlock()
		return errors.New("دریافت لیست در حال اجراست")
	}
	ctx, cancel := context.WithCancel(context.Background())
	a.fetchCancel = cancel
	a.fetching = true
	a.message = "در حال دریافت منابع"
	a.err = ""
	s := a.disk.Settings
	p := a.physical
	old := append([]Endpoint(nil), a.disk.Raw...)
	a.mu.Unlock()
	go func() {
		defer func() { cancel(); a.mu.Lock(); a.fetching = false; a.fetchCancel = nil; a.mu.Unlock() }()
		var sources []Source
		b, _ := bundle.ReadFile("assets/sources.json")
		json.Unmarshal(b, &sources)
		custom, _ := parseCustomSources(s.CustomSources)
		sources = append(sources, custom...)
		groups := make([][]Endpoint, len(sources)+4)
		reports := make([]string, len(sources))
		sem := make(chan struct{}, 6)
		var wg sync.WaitGroup
		tr := &http.Transport{DialContext: p.dial, Proxy: nil, TLSHandshakeTimeout: 5 * time.Second, MaxIdleConns: 6, DisableKeepAlives: true}
		defer tr.CloseIdleConnections()
		client := &http.Client{Transport: tr, Timeout: 30 * time.Second, CheckRedirect: func(r *http.Request, via []*http.Request) error {
			if len(via) > 3 || r.URL.Scheme != "https" {
				return errors.New("تغییر مسیر نامعتبر")
			}
			return nil
		}}
		for i, src := range sources {
			wg.Add(1)
			go func(i int, src Source) {
				defer wg.Done()
				select {
				case sem <- struct{}{}:
				case <-ctx.Done():
					return
				}
				defer func() { <-sem }()
				for _, u := range []string{src.URL, src.Fallback} {
					if u == "" {
						continue
					}
					req, _ := http.NewRequestWithContext(ctx, "GET", u, nil)
					r, err := client.Do(req)
					if err != nil {
						continue
					}
					if r.StatusCode == 200 {
						scanner := bufio.NewScanner(io.LimitReader(r.Body, 16<<20))
						scanner.Buffer(make([]byte, 4096), 65536)
						seen := map[string]bool{}
						for scanner.Scan() {
							if ctx.Err() != nil {
								break
							}
							if e, ok := parseEndpoint(scanner.Text(), src.Type, src.Name); ok && !seen[e.key()] {
								seen[e.key()] = true
								e.Country = country(e.Host)
								groups[i] = append(groups[i], e)
							}
						}
					}
					r.Body.Close()
					if len(groups[i]) > 0 {
						break
					}
				}
				reports[i] = fmt.Sprintf("%s: %d", src.Name, len(groups[i]))
			}(i, src)
		}
		for i, typ := range []string{"SOCKS5", "HTTP", "HTTPS"} {
			raw, _ := bundle.ReadFile("assets/bootstrap-" + strings.ToLower(typ) + ".txt")
			groups[len(sources)+i] = parseList(string(raw), typ, "نمونهٔ داخلی")
		}
		groups[len(sources)+3] = old
		wg.Wait()
		raw := balanced(groups)
		a.mu.Lock()
		a.disk.Raw = raw
		ir, irSOCKS := 0, 0
		for _, e := range raw {
			if e.Country == "IR" {
				ir++
				if e.Type == "SOCKS5" {
					irSOCKS++
				}
			}
		}
		reports = append(reports, fmt.Sprintf("ایران: %d نامزد یکتا؛ %d SOCKS5؛ سلامت هنوز تست نشده", ir, irSOCKS))
		a.sourceReport = reports
		a.message = fmt.Sprintf("%d پروکسی یکتا دریافت شد؛ اسکن را شروع کن", len(raw))
		if ctx.Err() != nil {
			a.message = "دریافت متوقف شد؛ نتیجه‌های دریافت‌شده ذخیره شدند"
		}
		a.recordLocked(a.message)
		a.mu.Unlock()
		a.saveChecked()
	}()
	return nil
}
func (a *App) startScan(mode string) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	if !physicalReady(a.physical) {
		return errors.New("کارت شبکهٔ اصلی شناسایی نشده؛ شبکه را از تنظیمات تازه‌سازی کن")
	}
	if a.scanRunning {
		return errors.New("ابتدا اسکن فعلی را متوقف کن")
	}
	s := a.disk.Settings
	if mode == "resume" {
		cp := a.disk.Checkpoint
		if cp == nil {
			return errors.New("اسکن ناتمامی وجود ندارد")
		}
		if cp.Settings.CustomTargets != s.CustomTargets || cp.Settings.TLSOnly != s.TLSOnly {
			return errors.New("تنظیمات تست تغییر کرده؛ اسکن را از ابتدا شروع کن")
		}
		s = cp.Settings
	} else {
		inputs := []Endpoint{}
		if mode == "saved" {
			for _, e := range a.disk.Favorites {
				if scanAccepts(e, s) {
					inputs = append(inputs, e)
				}
			}
		} else if mode == "healthy" {
			for _, e := range a.disk.Results {
				if scanAccepts(e, s) {
					inputs = append(inputs, e)
				}
			}
		} else {
			for _, e := range a.disk.Raw {
				if scanAccepts(e, s) {
					inputs = append(inputs, e)
				}
			}
			if s.Max > 0 && len(inputs) > s.Max {
				inputs = inputs[:s.Max]
			}
		}
		if len(inputs) == 0 {
			return errors.New("ابتدا لیست را دریافت یا پروکسی وارد کن")
		}
		ts, err := customTargets(s.CustomTargets)
		if err != nil {
			return err
		}
		if s.Slow && s.Timeout < 20 {
			s.Timeout = 20
		}
		a.disk.Checkpoint = &Checkpoint{Input: inputs, Done: make([]bool, len(inputs)), Settings: s, Targets: ts}
	}
	ctx, cancel := context.WithCancel(context.Background())
	a.scanCancel = cancel
	a.scanRunning = true
	a.fastScan = false
	a.err = ""
	a.scanError = ""
	a.message = "اسکن روی اینترنت مستقیم"
	cp := a.disk.Checkpoint
	p := a.physical
	go func() {
		jobs := make(chan int)
		var wg sync.WaitGroup
		for n := 0; n < s.Workers; n++ {
			wg.Add(1)
			go func() {
				defer wg.Done()
				for i := range jobs {
					if ctx.Err() != nil {
						continue
					}
					a.mu.Lock()
					a.sequence++
					claim := a.sequence
					key := cp.Input[i].key()
					a.attemptVersion[key] = claim
					a.mu.Unlock()
					e, err := (Probe{Physical: p}).inspect(ctx, cp.Input[i], s.Timeout, cp.Targets)
					a.mu.Lock()
					if ctx.Err() == nil {
						cp.Done[i] = true
						if a.attemptVersion[key] != claim {
							a.mu.Unlock()
							continue
						}
						if err == nil {
							old := a.disk.Results[e.key()]
							e.Speed = old.Speed
							a.disk.Results[e.key()] = e
						} else {
							delete(a.disk.Results, cp.Input[i].key())
						}
					}
					a.mu.Unlock()
				}
			}()
		}
	loop:
		for i := range cp.Input {
			a.mu.Lock()
			done := cp.Done[i]
			a.mu.Unlock()
			if done {
				continue
			}
			select {
			case jobs <- i:
			case <-ctx.Done():
				break loop
			}
		}
		close(jobs)
		wg.Wait()
		a.mu.Lock()
		a.scanRunning = false
		a.scanCancel = nil
		a.message = "اسکن متوقف شد؛ ادامهٔ اسکن ذخیره است"
		all := true
		for _, done := range cp.Done {
			if !done {
				all = false
				break
			}
		}
		if all {
			a.lastDone = len(cp.Input)
			a.lastTotal = len(cp.Input)
			a.disk.Checkpoint = nil
			a.message = "اسکن تمام شد"
		}
		a.recordLocked(a.message)
		auto := a.autoConnect && ctx.Err() == nil && all
		a.autoConnect = false
		a.mu.Unlock()
		a.saveChecked()
		if auto {
			a.connectBest()
		}
	}()
	return nil
}
func (a *App) testOne(key, kind string) error { return a.runSingle(key, kind) }
func (a *App) snapshot(r *http.Request) any {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.lastSeen = time.Now()
	q := r.URL.Query()
	f := a.disk.Filter
	if q.Has("country") {
		f.Country = q.Get("country")
	}
	if q.Has("type") {
		f.Type = q.Get("type")
	}
	if q.Has("service") {
		f.Service = q.Get("service")
	}
	if q.Has("sort") {
		f.Sort = q.Get("sort")
	}
	if q.Has("ping") {
		fmt.Sscanf(q.Get("ping"), "%d", &f.Ping)
	}
	rows := a.rowsLocked(f)
	count := len(rows)
	page := 0
	fmt.Sscanf(q.Get("page"), "%d", &page)
	if page < 0 {
		page = 0
	}
	start := page * 100
	if start > count {
		start = count
	}
	end := start + 100
	if end > count {
		end = count
	}
	rows = rows[start:end]
	done, total := a.lastDone, a.lastTotal
	cp := a.disk.Checkpoint
	if cp != nil && !a.fastScan {
		done = 0
		total = len(cp.Input)
		for _, b := range cp.Done {
			if b {
				done++
			}
		}
	}
	key := a.disk.Selected
	if q.Has("selected") {
		key = q.Get("selected")
	}
	var selected *Endpoint
	if e, ok := a.endpointLocked(key); ok {
		selected = &e
	}
	ts, _ := customTargets(a.disk.Settings.CustomTargets)
	tasks := map[string]TestTask{}
	testing := map[string]bool{}
	for key, task := range a.tasks {
		tasks[key] = *task
		testing[key] = true
	}
	measurements := map[string]TestResult{}
	for _, e := range rows {
		if result, ok := a.disk.Tests[e.key()]; ok {
			measurements[e.key()] = result
		}
	}
	if result, ok := a.disk.Tests[key]; ok {
		measurements[key] = result
	}
	batch := a.speedBatch
	batch.Done = a.speedBatchDone
	batch.Total = a.speedBatchTotal
	favoriteKeys := map[string]bool{}
	for k := range a.disk.Favorites {
		favoriteKeys[k] = true
	}
	countries := map[string]int{}
	for _, e := range a.disk.Raw {
		c := e.Country
		if c == "" {
			c = "UNKNOWN"
		}
		countries[c]++
	}
	for _, e := range a.disk.Favorites {
		c := e.Country
		if c == "" {
			c = "UNKNOWN"
		}
		if countries[c] == 0 {
			countries[c] = 1
		}
	}
	return map[string]any{"killSwitch": map[string]any{"enabled": killEnabled(a.disk.Settings), "started": a.protectionStarted, "armed": a.killArmed, "error": a.killError, "guard": a.guardMode}, "favoriteKeys": favoriteKeys, "favoriteCount": len(favoriteKeys), "countries": countries, "desktop": desktopState(), "speedBatch": map[string]any{"running": a.speedBatchCancel != nil, "done": batch.Done, "total": batch.Total, "bytes": batch.Bytes, "failed": batch.Failed, "skipped": batch.Skipped, "current": batch.Current, "filter": batch.Filter, "canceled": batch.Canceled}, "version": version, "raw": len(a.disk.Raw), "healthy": len(a.disk.Results), "filtered": count, "rows": rows, "done": done, "total": total, "running": a.scanRunning, "fetching": a.fetching, "resume": cp != nil, "message": a.message, "error": a.err, "settings": a.disk.Settings, "filter": a.disk.Filter, "vpn": a.vpnState, "connected": a.connected, "selectedKey": key, "selectedResult": selected, "testing": testing, "tasks": tasks, "measurements": measurements, "traffic": a.disk.Traffic, "physical": a.physical, "networkReady": physicalReady(a.physical), "sources": a.sourceReport, "targets": ts}
}

type Action struct {
	Action   string   `json:"action"`
	Key      string   `json:"key"`
	Text     string   `json:"text"`
	Type     string   `json:"type"`
	Settings Settings `json:"settings"`
	Filter   Filter   `json:"filter"`
}

func (a *App) act(v Action) error {
	switch v.Action {
	case "favorite":
		return a.toggleFavorite(v.Key)
	case "fetch":
		return a.fetch()
	case "scan", "resume", "healthy", "saved":
		return a.startScan(v.Action)
	case "stop":
		a.mu.Lock()
		a.autoConnect = false
		if a.scanCancel != nil {
			a.scanCancel()
		}
		a.mu.Unlock()
	case "speedFiltered":
		return a.startFilteredSpeed()
	case "stopSpeedFiltered":
		a.stopFilteredSpeed()
	case "stopOperations":
		a.stopOperations()
	case "uiClosed":
		a.windowClosed()
	case "test", "speed", "ping":
		return a.testOne(v.Key, v.Action)
	case "connect":
		return a.connect(v.Key)
	case "disconnect":
		a.mu.Lock()
		a.controlGeneration++
		a.userDisconnect = true
		a.autoConnect = false
		for _, t := range a.tasks {
			t.cancel()
		}
		if a.connectCancel != nil {
			a.connectCancel()
		}
		a.mu.Unlock()
		go a.disconnect()
	case "killSwitch":
		a.mu.Lock()
		s := a.disk.Settings
		s.KillSwitchOff = !s.KillSwitchOff
		a.mu.Unlock()
		return a.applySettings(s)
	case "chatgptOnly":
		a.mu.Lock()
		s := a.disk.Settings
		a.mu.Unlock()
		s.RoutingMode = "chatgpt"
		return a.applySettings(s)
	case "settings":
		return a.applySettings(v.Settings)
	case "filter":
		a.mu.Lock()
		settings := a.disk.Settings
		a.mu.Unlock()
		if err := validateFilter(v.Filter, settings); err != nil {
			return err
		}
		a.mu.Lock()
		a.disk.Filter = v.Filter
		a.mu.Unlock()
		go a.saveChecked()
	case "select":
		a.mu.Lock()
		if _, ok := a.endpointLocked(v.Key); !ok {
			a.mu.Unlock()
			return errors.New("پروکسی موجود نیست")
		}
		a.disk.Selected = v.Key
		a.mu.Unlock()
		go a.saveChecked()
	case "stopTest":
		a.mu.Lock()
		if t := a.tasks[v.Key]; t != nil {
			t.cancel()
		}
		a.mu.Unlock()
	case "fastPing":
		return a.fastPing()
	case "stopFetch":
		a.mu.Lock()
		if a.fetchCancel != nil {
			a.fetchCancel()
		}
		a.mu.Unlock()
	case "autoConnect":
		return a.quickToggle(true)
	case "quickToggle":
		return a.quickToggle(false)
	case "apps":
		a.loadApps()
	case "widget":
		return showQuickWidget()
	case "refreshNetwork":
		a.mu.Lock()
		busy := a.scanRunning || a.fetching || a.vpnState != "off"
		a.mu.Unlock()
		if busy {
			return errors.New("برای شناسایی دوبارهٔ شبکه، اسکن و اتصال را متوقف کن")
		}
		p, err := physicalNetwork()
		if err != nil {
			return err
		}
		a.mu.Lock()
		a.physical = p
		a.err = ""
		a.mu.Unlock()
	case "import":
		parsed := parseList(v.Text, v.Type, "ورود دستی")
		if len(parsed) == 0 {
			return errors.New("نشانی IP عمومی و پورت معتبر وارد کن")
		}
		a.mu.Lock()
		a.disk.Raw = balanced([][]Endpoint{parsed, a.disk.Raw})
		a.message = fmt.Sprintf("%d پروکسی اضافه شد؛ اسکن از ابتدا را بزن", len(parsed))
		a.mu.Unlock()
		a.saveChecked()
	case "clearError":
		a.mu.Lock()
		a.err = ""
		a.mu.Unlock()
	case "exit":
		go a.shutdown()
	default:
		return errors.New("عملیات ناشناخته")
	}
	return nil
}
func (a *App) handler() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Referrer-Policy", "no-referrer")
		w.Header().Set("Content-Security-Policy", "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'")
		if r.Host != strings.TrimPrefix(a.origin, "http://") {
			http.Error(w, "Invalid Host", 403)
			return
		}
		if o := r.Header.Get("Origin"); o != "" && o != a.origin {
			http.Error(w, "Invalid Origin", 403)
			return
		}
		if strings.HasPrefix(r.URL.Path, "/flags/") {
			code := strings.TrimSuffix(strings.TrimPrefix(r.URL.Path, "/flags/"), ".svg")
			if len(code) != 2 || code[0] < 'a' || code[0] > 'z' || code[1] < 'a' || code[1] > 'z' {
				http.NotFound(w, r)
				return
			}
			b, err := bundle.ReadFile("assets/flags/" + code + ".svg")
			if err != nil {
				http.NotFound(w, r)
				return
			}
			w.Header().Set("Content-Type", "image/svg+xml")
			w.Write(b)
			return
		}
		if r.URL.Path == "/icon.svg" {
			b, _ := bundle.ReadFile("assets/icon.svg")
			w.Header().Set("Content-Type", "image/svg+xml")
			w.Write(b)
			return
		}
		if r.URL.Path == "/" {
			if r.Method != "GET" {
				http.Error(w, "Method", 405)
				return
			}
			b, _ := bundle.ReadFile("ui.html")
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
			w.Write(b)
			return
		}
		if r.Header.Get("X-App-Token") != a.token {
			http.Error(w, "Unauthorized", 403)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		switch r.URL.Path {
		case "/api/state":
			if r.Method != "GET" {
				http.Error(w, "Method", 405)
				return
			}
			json.NewEncoder(w).Encode(a.snapshot(r))
		case "/api/action":
			if r.Method != "POST" {
				http.Error(w, "Method", 405)
				return
			}
			r.Body = http.MaxBytesReader(w, r.Body, 3_000_000)
			var v Action
			if err := json.NewDecoder(r.Body).Decode(&v); err != nil {
				w.WriteHeader(400)
				json.NewEncoder(w).Encode(map[string]string{"error": "درخواست نامعتبر"})
				return
			}
			if err := a.act(v); err != nil {
				w.WriteHeader(400)
				json.NewEncoder(w).Encode(map[string]string{"error": err.Error()})
			} else {
				w.Write([]byte("{}"))
			}
		case "/api/catalog":
			b, _ := bundle.ReadFile("assets/sources.json")
			w.Write(b)
		case "/api/apps":
			if r.Method != "GET" {
				http.Error(w, "Method", 405)
				return
			}
			a.mu.Lock()
			json.NewEncoder(w).Encode(map[string]any{"apps": a.apps, "loading": a.appsLoading, "loaded": a.appsLoaded, "error": a.appsError})
			a.mu.Unlock()
		case "/api/report":
			json.NewEncoder(w).Encode(map[string]string{"text": a.reportText()})
		case "/api/crash":
			b, _ := os.ReadFile(filepath.Join(a.dir, "crash.txt"))
			json.NewEncoder(w).Encode(map[string]string{"text": string(b)})
		case "/api/browse":
			if r.Method != "POST" {
				http.Error(w, "Method", 405)
				return
			}
			path, err := chooseExecutable()
			msg := ""
			if err != nil {
				msg = err.Error()
			}
			json.NewEncoder(w).Encode(map[string]string{"path": path, "error": msg})
		case "/api/export":
			a.mu.Lock()
			rows := a.rowsLocked(a.disk.Filter)
			a.mu.Unlock()
			sort.Slice(rows, func(i, j int) bool { return rows[i].Ping < rows[j].Ping })
			w.Header().Set("Content-Type", "text/plain; charset=utf-8")
			w.Header().Set("Content-Disposition", "attachment; filename=PersianSocks-healthy.txt")
			for _, e := range rows {
				fmt.Fprintln(w, e.key())
			}
		default:
			http.NotFound(w, r)
		}
	})
}
func main() {
	defer func() {
		if v := recover(); v != nil {
			base, _ := os.UserConfigDir()
			os.MkdirAll(filepath.Join(base, "PersianSocks"), 0700)
			os.WriteFile(filepath.Join(base, "PersianSocks", "crash.txt"), []byte(fmt.Sprint(v)), 0600)
			alert("خطای برنامه؛ گزارش در AppData/PersianSocks/crash.txt ثبت شد")
		}
	}()
	if !preparePlatform() {
		return
	}
	geo, _ = bundle.ReadFile("assets/ip-country.bin")
	base, _ := os.UserConfigDir()
	if custom := os.Getenv("PERSIANSOCKS_DATA"); custom != "" {
		base = custom
	}
	a := newApp(filepath.Join(base, "PersianSocks"))
	if os.Getenv("PERSIANSOCKS_DESKTOP_SMOKE") == "1" {
		a.disk.Settings.KillSwitchOff = true
	} else {
		a.startProtection()
	}
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		alert(err.Error())
		return
	}
	token := make([]byte, 32)
	if _, err = rand.Read(token); err != nil {
		alert(err.Error())
		return
	}
	a.token = hex.EncodeToString(token)
	a.origin = "http://" + listener.Addr().String()
	os.WriteFile(filepath.Join(a.dir, "UI.url"), []byte(a.origin+"/#"+a.token), 0600)
	server := &http.Server{Handler: a.handler(), ReadHeaderTimeout: 5 * time.Second, IdleTimeout: 30 * time.Second}
	done := make(chan struct{})
	var once sync.Once
	a.shutdown = func() {
		once.Do(func() {
			a.mu.Lock()
			a.closing = true
			a.mu.Unlock()
			stopPlatformCommands()
			a.mu.Lock()
			if a.speedBatchCancel != nil {
				a.speedBatchCancel()
			}
			if a.fetchCancel != nil {
				a.fetchCancel()
			}
			for _, task := range a.tasks {
				task.cancel()
			}
			a.userDisconnect = true
			a.controlGeneration++
			if a.scanCancel != nil {
				a.scanCancel()
			}
			if a.connectCancel != nil {
				a.connectCancel()
			}
			a.mu.Unlock()
			a.disconnect()
			if err := a.stopProtection(); err != nil {
				a.mu.Lock()
				a.err = "حذف حفاظت ناموفق بود: " + err.Error()
				a.mu.Unlock()
			}
			a.saveChecked()
			os.Remove(filepath.Join(a.dir, "UI.url"))
			server.Close()
			stopDesktopUI()
			close(done)
		})
	}
	go server.Serve(listener)
	go a.monitor(done)
	go a.pollTraffic(done)
	startQuickControls(a, done)
	go func() {
		p, err := physicalNetwork()
		a.mu.Lock()
		a.physical = p
		if err != nil {
			a.err = err.Error()
		}
		a.mu.Unlock()
	}()
	if os.Getenv("PERSIANSOCKS_NO_BROWSER") == "" {
		startDesktopUI(a, done)
	} else {
		fmt.Println(a.origin + "/#" + a.token)
	}
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, os.Interrupt)
	go func() {
		select {
		case <-sig:
			a.shutdown()
		case <-done:
		}
	}()
	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-done:
			return
		case <-ticker.C:
			a.mu.Lock()
			running := a.scanRunning || a.vpnState == "on"
			expired := false // tray and widget remain available after the UI is closed
			a.mu.Unlock()
			if !running {
				go a.ensureProtection()
				a.refreshPhysical()
			}
			if running {
				a.saveChecked()
			}
			if expired {
				a.shutdown()
			}
		}
	}
}
