package main

import (
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

const version = "0.1.2"

type Source struct {
	Name     string `json:"name"`
	URL      string `json:"url"`
	Fallback string `json:"fallback"`
	Type     string `json:"type"`
}
type Settings struct {
	Workers       int    `json:"workers"`
	Timeout       int    `json:"timeout"`
	Max           int    `json:"max"`
	TLSOnly       bool   `json:"tlsOnly"`
	Failover      bool   `json:"failover"`
	CustomSources string `json:"customSources"`
	CustomTargets string `json:"customTargets"`
}
type Checkpoint struct {
	Input    []Endpoint `json:"input"`
	Done     []bool     `json:"done"`
	Settings Settings   `json:"settings"`
	Targets  []Target   `json:"targets"`
}
type Disk struct {
	Raw        []Endpoint          `json:"raw"`
	Results    map[string]Endpoint `json:"results"`
	Checkpoint *Checkpoint         `json:"checkpoint,omitempty"`
	Settings   Settings            `json:"settings"`
}
type App struct {
	mu             sync.Mutex
	saveMu         sync.Mutex
	disk           Disk
	dir            string
	physical       Physical
	scanCancel     context.CancelFunc
	scanRunning    bool
	fetching       bool
	message        string
	err            string
	scanError      string
	sourceReport   []string
	test           map[string]bool
	vpnState       string
	connected      *Endpoint
	connectCancel  context.CancelFunc
	core           *exec.Cmd
	coreDone       chan error
	coreLog        *logTail
	job            uintptr
	token          string
	origin         string
	lastSeen       time.Time
	shutdown       func()
	connectMu      sync.Mutex
	attemptVersion map[string]uint64
	sequence       uint64
	lastDone       int
	lastTotal      int
}

func defaultSettings() Settings { return Settings{Workers: 12, Timeout: 8, Max: 1000} }
func validateSettings(s Settings) error {
	if s.Workers < 1 || s.Workers > 24 || s.Timeout < 3 || s.Timeout > 30 || s.Max < 0 || s.Max > 100000 {
		return errors.New("تعداد کارگر ۱ تا ۲۴، مهلت ۳ تا ۳۰ ثانیه و سقف ۰ تا ۱۰۰۰۰۰ است؛ صفر یعنی همه")
	}
	_, err := customTargets(s.CustomTargets)
	return err
}
func customTargets(raw string) ([]Target, error) {
	ts := append([]Target(nil), targets...)
	i := 0
	for _, line := range strings.Split(raw, "\n") {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		i++
		if i > 8 {
			return nil, errors.New("حداکثر ۸ مقصد دلخواه")
		}
		if !strings.Contains(line, "://") {
			line = "https://" + line
		}
		r, err := http.NewRequest("GET", line, nil)
		if err != nil || r.URL.Scheme != "https" || r.URL.User != nil || r.URL.Hostname() == "" || (r.URL.Port() != "" && r.URL.Port() != "443") {
			return nil, errors.New("مقصد باید نشانی HTTPS معتبر باشد")
		}
		ts = append(ts, Target{ID: line, Name: r.URL.Hostname(), URL: line})
	}
	return ts, nil
}
func newApp(dir string) *App {
	a := &App{dir: dir, disk: Disk{Results: map[string]Endpoint{}, Settings: defaultSettings()}, test: map[string]bool{}, attemptVersion: map[string]uint64{}, vpnState: "off", lastSeen: time.Now()}
	os.MkdirAll(dir, 0700)
	if b, err := os.ReadFile(filepath.Join(dir, "state.json")); err == nil && len(b) < 100_000_000 {
		var d Disk
		if json.Unmarshal(b, &d) == nil && d.Results != nil && validateSettings(d.Settings) == nil {
			a.disk = d
		}
	}
	cp := a.disk.Checkpoint
	if cp != nil && (len(cp.Input) != len(cp.Done) || validateSettings(cp.Settings) != nil || len(cp.Targets) == 0) {
		a.disk.Checkpoint = nil
	}
	var err error
	a.physical, err = physicalNetwork()
	if err != nil {
		a.err = err.Error()
	}
	return a
}
func (a *App) save() error {
	a.saveMu.Lock()
	defer a.saveMu.Unlock()
	a.mu.Lock()
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
				if len(out) >= 100000 {
					return out
				}
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
			if len(out) >= 50000 {
				break
			}
		}
	}
	return out
}
func (a *App) fetch() error {
	a.mu.Lock()
	if a.fetching {
		a.mu.Unlock()
		return errors.New("دریافت لیست در حال اجراست")
	}
	a.fetching = true
	a.message = "در حال دریافت منابع"
	a.err = ""
	s := a.disk.Settings
	p := a.physical
	old := append([]Endpoint(nil), a.disk.Raw...)
	a.mu.Unlock()
	go func() {
		defer func() { a.mu.Lock(); a.fetching = false; a.mu.Unlock() }()
		var sources []Source
		b, _ := bundle.ReadFile("assets/sources.json")
		json.Unmarshal(b, &sources)
		for i, line := range strings.Split(s.CustomSources, "\n") {
			fields := strings.Fields(line)
			if len(fields) == 0 {
				continue
			}
			typ, u := "SOCKS5", fields[0]
			if len(fields) == 2 {
				typ, u = strings.ToUpper(fields[0]), fields[1]
			}
			r, err := http.NewRequest("GET", u, nil)
			if err == nil && r.URL.Scheme == "https" && r.URL.User == nil && (typ == "SOCKS5" || typ == "HTTP" || typ == "HTTPS") {
				sources = append(sources, Source{fmt.Sprintf("دلخواه %d", i+1), u, "", typ})
			}
		}
		groups := make([][]Endpoint, len(sources)+4)
		reports := make([]string, len(sources))
		sem := make(chan struct{}, 6)
		var wg sync.WaitGroup
		tr := &http.Transport{DialContext: p.dial, Proxy: nil, TLSHandshakeTimeout: 5 * time.Second, MaxIdleConns: 6, DisableKeepAlives: true}
		defer tr.CloseIdleConnections()
		client := &http.Client{Transport: tr, Timeout: 12 * time.Second, CheckRedirect: func(r *http.Request, via []*http.Request) error {
			if len(via) > 3 || r.URL.Scheme != "https" {
				return errors.New("تغییر مسیر نامعتبر")
			}
			return nil
		}}
		for i, src := range sources {
			wg.Add(1)
			go func(i int, src Source) {
				defer wg.Done()
				sem <- struct{}{}
				defer func() { <-sem }()
				for _, u := range []string{src.URL, src.Fallback} {
					if u == "" {
						continue
					}
					r, err := client.Get(u)
					if err != nil {
						continue
					}
					raw, err := io.ReadAll(io.LimitReader(r.Body, 2_000_001))
					r.Body.Close()
					if err == nil && r.StatusCode == 200 && len(raw) <= 2_000_000 {
						groups[i] = parseList(string(raw), src.Type, src.Name)
						if len(groups[i]) > 0 {
							break
						}
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
		a.sourceReport = reports
		a.message = fmt.Sprintf("%d پروکسی یکتا دریافت شد؛ اسکن را شروع کن", len(raw))
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
		s = cp.Settings
	} else {
		inputs := []Endpoint{}
		if mode == "healthy" {
			for _, e := range a.disk.Results {
				inputs = append(inputs, e)
			}
		} else {
			for _, e := range a.disk.Raw {
				if !s.TLSOnly || e.Type == "HTTPS" {
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
		a.disk.Checkpoint = &Checkpoint{Input: inputs, Done: make([]bool, len(inputs)), Settings: s, Targets: ts}
	}
	ctx, cancel := context.WithCancel(context.Background())
	a.scanCancel = cancel
	a.scanRunning = true
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
		a.mu.Unlock()
		a.saveChecked()
	}()
	return nil
}
func (a *App) testOne(key, kind string) error {
	a.mu.Lock()
	e, ok := a.disk.Results[key]
	if !ok {
		a.mu.Unlock()
		return errors.New("پروکسی در فهرست سالم‌ها نیست")
	}
	if a.test[key] {
		a.mu.Unlock()
		return errors.New("تست این پروکسی در حال اجراست")
	}
	a.test[key] = true
	claim := uint64(0)
	if kind != "speed" {
		a.sequence++
		claim = a.sequence
		a.attemptVersion[key] = claim
	}
	p, s := a.physical, a.disk.Settings
	a.mu.Unlock()
	go func() {
		defer func() { a.mu.Lock(); delete(a.test, key); a.mu.Unlock(); a.saveChecked() }()
		if kind == "speed" {
			speed, _, err := (Probe{Physical: p}).speed(context.Background(), e)
			a.mu.Lock()
			if current, ok := a.disk.Results[key]; ok {
				if err != nil {
					current.Note = "تست سرعت ناموفق: " + err.Error()
				} else {
					current.Speed = speed
					current.Note = ""
				}
				a.disk.Results[key] = current
			}
			a.mu.Unlock()
			return
		}
		ts, _ := customTargets(s.CustomTargets)
		fresh, err := (Probe{Physical: p}).inspect(context.Background(), e, s.Timeout, ts)
		a.mu.Lock()
		defer a.mu.Unlock()
		if a.attemptVersion[key] != claim {
			return
		}
		if err != nil {
			delete(a.disk.Results, key)
			a.err = "پروکسی نامعتبر از سالم‌ها حذف شد: " + err.Error()
		} else {
			if current, ok := a.disk.Results[key]; ok {
				fresh.Speed = current.Speed
			}
			a.disk.Results[key] = fresh
		}
	}()
	return nil
}
func (a *App) snapshot(r *http.Request) any {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.lastSeen = time.Now()
	rows := []Endpoint{}
	q := r.URL.Query()
	typ, service := q.Get("type"), q.Get("service")
	max := 0
	fmt.Sscanf(q.Get("ping"), "%d", &max)
	for _, e := range a.disk.Results {
		if typ != "" && e.Type != typ {
			continue
		}
		if max > 0 && (e.Ping <= 0 || e.Ping >= int64(max)) {
			continue
		}
		if service != "" {
			found := false
			for _, s := range e.Services {
				if s == service {
					found = true
				}
			}
			if !found {
				continue
			}
		}
		rows = append(rows, e)
	}
	sort.Slice(rows, func(i, j int) bool {
		if q.Get("sort") == "speed" && rows[i].Speed != rows[j].Speed {
			return rows[i].Speed > rows[j].Speed
		}
		if rows[i].Ping != rows[j].Ping {
			return rows[i].Ping < rows[j].Ping
		}
		return rows[i].key() < rows[j].key()
	})
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
	if cp := a.disk.Checkpoint; cp != nil {
		done = 0
		total = len(cp.Input)
		for _, b := range cp.Done {
			if b {
				done++
			}
		}
	}
	cp := a.disk.Checkpoint
	var ts []Target
	ts, _ = customTargets(a.disk.Settings.CustomTargets)
	var selected *Endpoint
	if e, ok := a.disk.Results[q.Get("selected")]; ok {
		selected = &e
	}
	testing := make(map[string]bool, len(a.test))
	for key, value := range a.test {
		testing[key] = value
	}
	return map[string]any{"version": version, "raw": len(a.disk.Raw), "healthy": len(a.disk.Results), "filtered": count, "rows": rows, "done": done, "total": total, "running": a.scanRunning, "fetching": a.fetching, "resume": cp != nil, "message": a.message, "error": a.err, "settings": a.disk.Settings, "vpn": a.vpnState, "connected": a.connected, "selectedResult": selected, "testing": testing, "physical": a.physical, "sources": a.sourceReport, "targets": ts}
}

type Action struct {
	Action   string   `json:"action"`
	Key      string   `json:"key"`
	Text     string   `json:"text"`
	Type     string   `json:"type"`
	Settings Settings `json:"settings"`
}

func (a *App) act(v Action) error {
	switch v.Action {
	case "fetch":
		return a.fetch()
	case "scan", "resume", "healthy":
		return a.startScan(v.Action)
	case "stop":
		a.mu.Lock()
		if a.scanCancel != nil {
			a.scanCancel()
		}
		a.mu.Unlock()
	case "test", "speed":
		return a.testOne(v.Key, v.Action)
	case "connect":
		return a.connect(v.Key)
	case "disconnect":
		a.mu.Lock()
		if a.connectCancel != nil {
			a.connectCancel()
		}
		a.mu.Unlock()
		go a.disconnect()
	case "settings":
		if err := validateSettings(v.Settings); err != nil {
			return err
		}
		a.mu.Lock()
		a.disk.Settings = v.Settings
		a.mu.Unlock()
		a.saveChecked()
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
		case "/api/export":
			a.mu.Lock()
			rows := []Endpoint{}
			for _, e := range a.disk.Results {
				rows = append(rows, e)
			}
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
			if a.scanCancel != nil {
				a.scanCancel()
			}
			if a.connectCancel != nil {
				a.connectCancel()
			}
			a.mu.Unlock()
			a.disconnect()
			a.saveChecked()
			os.Remove(filepath.Join(a.dir, "UI.url"))
			server.Close()
			close(done)
		})
	}
	go server.Serve(listener)
	go a.monitor(done)
	if os.Getenv("PERSIANSOCKS_NO_BROWSER") == "" {
		openUI(a.origin + "/#" + a.token)
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
			running := a.scanRunning
			expired := time.Since(a.lastSeen) > 10*time.Minute
			a.mu.Unlock()
			if running {
				a.saveChecked()
			}
			if expired {
				a.shutdown()
			}
		}
	}
}
