package main

import (
	"encoding/json"
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
)

func TestKillSwitchDefaultsOnAndLegacySettings(t *testing.T) {
	a := newApp(t.TempDir())
	if !killEnabled(a.disk.Settings) {
		t.Fatal("kill switch must default on")
	}
	raw := []byte(`{"workers":24,"timeout":8,"max":5000,"scanTypes":["SOCKS5"],"splitMode":"all"}`)
	var s Settings
	if err := json.Unmarshal(raw, &s); err != nil || !killEnabled(s) {
		t.Fatal("legacy settings not protected", err)
	}
	s.KillSwitchOff = true
	a.disk.Settings = s
	if err := a.save(); err != nil {
		t.Fatal(err)
	}
	if killEnabled(newApp(a.dir).disk.Settings) {
		t.Fatal("explicit off choice lost")
	}
}
func TestGuardRejectsProtectedRoutesWithoutProxyFallback(t *testing.T) {
	for _, mode := range []string{"apps", "domains", "chatgpt"} {
		s := defaultSettings()
		s.RoutingMode = mode
		s.RoutePresets = []string{"openai"}
		cfg, err := makeGuardConfig(Physical{}, 12080, s)
		if err != nil {
			t.Fatal(err)
		}
		rules := cfg["route"].(map[string]any)["rules"].([]any)
		for _, r := range rules {
			if r.(map[string]any)["outbound"] == "proxy" {
				t.Fatal("guard routes protected traffic to an outbound instead of rejecting it")
			}
		}
		if rules[len(rules)-1].(map[string]any)["action"] != "reject" {
			t.Fatal("guard final route not closed")
		}
		if bin := os.Getenv("PERSIANSOCKS_TEST_CORE"); bin != "" {
			b, _ := json.Marshal(cfg)
			p := filepath.Join(t.TempDir(), "guard.json")
			os.WriteFile(p, b, 0600)
			out, err := exec.Command(bin, "check", "-c", p).CombinedOutput()
			if err != nil {
				t.Fatalf("%s: %v %s", mode, err, out)
			}
		}
	}
}

type failedKillFirewall struct{}

func (failedKillFirewall) Arm([]string, string) error { return errors.New("test firewall failure") }
func (failedKillFirewall) Release() error             { return nil }
func (failedKillFirewall) Close() error               { return nil }
func TestFirewallFailureNeverReportsProtectedOrConnects(t *testing.T) {
	a := newApp(t.TempDir())
	a.protectionStarted = true
	a.firewall = failedKillFirewall{}
	if a.armKillLocked("missing") == nil || a.killArmed || a.killError == "" {
		t.Fatal("failure reported as protected")
	}
	if a.protectionReady() == nil {
		t.Fatal("connection allowed before firewall was ready")
	}
}
