package main

import (
	"context"
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
	"time"
)

func TestNativeNetworkSelectionSupportsPPPAndVirtualUplinks(t *testing.T) {
	cs := []networkCandidate{
		{Physical: Physical{Index: 1, Name: "PersianSocks-TUN", IP: "172.29.251.1"}, Metric: 1, Up: true},
		{Physical: Physical{Index: 2, Name: "Wi-Fi", IP: "169.254.1.2"}, Metric: 5, Up: true},
		{Physical: Physical{Index: 3, Name: "Broadband Connection", IP: "100.72.10.5"}, Metric: 10, Up: true, Kind: 23},
		{Physical: Physical{Index: 4, Name: "vEthernet (External)", IP: "192.168.1.2", DNS: []string{"192.168.1.1"}}, Metric: 20, Up: true},
	}
	p, err := selectNetwork(cs)
	if err != nil || p.Index != 3 || len(p.DNS) == 0 {
		t.Fatalf("PPP/DNS-less uplink rejected: %+v %v", p, err)
	}
	cs[2].Up = false
	p, err = selectNetwork(cs)
	if err != nil || p.Index != 4 {
		t.Fatalf("Hyper-V external uplink rejected: %+v %v", p, err)
	}
	cs[3].Up = false
	if _, err = selectNetwork(cs); err == nil {
		t.Fatal("own TUN or APIPA selected as Internet")
	}
}
func TestAppFamilyScopingAndStoreUpdate(t *testing.T) {
	for _, tc := range []struct{ path, helper, unrelated string }{
		{`C:\Users\Ali\AppData\Local\Programs\ChatGPT\ChatGPT.exe`, `C:\Users\Ali\AppData\Local\Programs\ChatGPT\resources\helper.exe`, `C:\Program Files\Microsoft\EdgeWebView\msedgewebview2.exe`},
		{`C:\Program Files\WindowsApps\OpenAI.ChatGPT_1.0.0_x64__publisher\app\ChatGPT.exe`, `C:\Program Files\WindowsApps\OpenAI.ChatGPT_2.0.0_x64__publisher\app\helper.exe`, `C:\Program Files\WindowsApps\Other.App_2.0.0_x64__publisher\app\helper.exe`},
	} {
		match := false
		for _, p := range appFamilyPatterns(tc.path) {
			re := regexp.MustCompile(p)
			match = match || re.MatchString(tc.helper)
			if re.MatchString(tc.unrelated) {
				t.Fatal("unrelated helper matched", p)
			}
		}
		if !match {
			t.Fatal("helper/update not matched", tc.path, appFamilyPatterns(tc.path))
		}
	}
	for _, path := range []string{`C:\Windows\System32\notepad.exe`, `C:\Users\Ali\Downloads\Example.exe`, `C:\Program Files\Example.exe`, `C:\app.exe`} {
		if len(appFamilyPatterns(path)) != 0 {
			t.Fatal("common directory broadened", path)
		}
	}
}
func TestDomainRoutingAndChatGPTRemainScoped(t *testing.T) {
	for _, mode := range []string{"domains", "chatgpt"} {
		s := defaultSettings()
		s.RoutingMode = mode
		s.RoutePresets = []string{"telegram", "youtube", "openai"}
		s.RouteDomains = "*.example.com\napi.example.net"
		cfg, err := makeTunnelConfig(Endpoint{Host: "8.8.8.8", Port: 1080, Type: "SOCKS5"}, Physical{}, 12345, 0, "", s)
		if err != nil {
			t.Fatal(err)
		}
		rr := cfg["route"].(map[string]any)["rules"].([]any)
		if last := rr[len(rr)-1].(map[string]any); last["outbound"] != "direct" {
			t.Fatal("unrelated traffic not direct")
		}
		dns := cfg["dns"].(map[string]any)
		if dns["final"] != "local-dns" || len(dns["rules"].([]any)) < 2 {
			t.Fatal("unrelated DNS tunneled or domain recovery missing")
		}
		ds, _ := routeDomains(s)
		joined := strings.Join(ds, ",")
		if !strings.Contains(joined, "chatgpt.com") || strings.Contains(joined, "google.com") {
			t.Fatal(joined)
		}
		if mode == "chatgpt" && strings.Contains(joined, "youtube.com") {
			t.Fatal("ChatGPT preset inherited unrelated service")
		}
		b, _ := json.Marshal(cfg)
		if strings.Contains(string(b), `"process_name":["msedgewebview2.exe"]`) {
			t.Fatal("all shared WebViews tunneled")
		}
	}
	for _, bad := range []string{"https://example.com/path", "8.8.8.8", "example.com:443", "bad"} {
		s := defaultSettings()
		s.RoutingMode = "domains"
		s.RouteDomains = bad
		if validateRouting(s) == nil {
			t.Fatal(bad)
		}
	}
}
func TestRoutingSettingsPersistAndPresetAPI(t *testing.T) {
	dir := t.TempDir()
	a := newApp(dir)
	if err := a.act(Action{Action: "chatgptOnly"}); err != nil {
		t.Fatal(err)
	}
	if s := newApp(dir).disk.Settings; s.RoutingMode != "chatgpt" {
		t.Fatal("preset not persisted")
	}
}
func TestScopedConfigsAcceptedByCore(t *testing.T) {
	bin := os.Getenv("PERSIANSOCKS_TEST_CORE")
	if bin == "" {
		t.Skip("native core not staged")
	}
	for _, mode := range []string{"domains", "chatgpt", "apps"} {
		t.Run(mode, func(t *testing.T) {
			s := defaultSettings()
			s.RoutingMode = mode
			s.RoutePresets = []string{"telegram", "youtube", "openai"}
			if mode == "apps" {
				f := filepath.Join(t.TempDir(), "Example.exe")
				os.WriteFile(f, []byte("fixture"), 0600)
				s.SplitMode = "only"
				s.SplitApps = []string{f}
			}
			cfg, err := makeTunnelConfig(Endpoint{Host: "8.8.8.8", Port: 1080, Type: "SOCKS5"}, Physical{}, 12345, 0, "", s)
			if err != nil {
				t.Fatal(err)
			}
			b, _ := json.Marshal(cfg)
			f := filepath.Join(t.TempDir(), "config.json")
			os.WriteFile(f, b, 0600)
			ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
			defer cancel()
			out, err := exec.CommandContext(ctx, bin, "check", "-c", f).CombinedOutput()
			if err != nil {
				t.Fatalf("%v %s", err, out)
			}
		})
	}
}
