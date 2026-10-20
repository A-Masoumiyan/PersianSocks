package main

import (
	"encoding/json"
	"errors"
	"path"
	"regexp"
	"sort"
	"strings"
)

var serviceDomains = map[string][]string{
	"telegram": {"telegram.org", "t.me", "telegram.me", "telegra.ph", "telesco.pe", "tdesktop.com", "cdn-telegram.org"},
	"youtube":  {"youtube.com", "youtu.be", "googlevideo.com", "ytimg.com", "youtube-nocookie.com", "youtubei.googleapis.com", "youtube.googleapis.com"},
	// Core/login/files plus specific shared dependencies from OpenAI's network guidance.
	"openai": {"chatgpt.com", "openai.com", "oaistatic.com", "oaiusercontent.com", "oaistatsig.com", "cdn.openaimerge.com", "cdn.workos.com", "forwarder.workos.com", "frontend-apps-multi-region.workos.com", "images.workoscdn.com", "setup.workos.com", "workos.imgix.net", "challenges.cloudflare.com", "intercom.io", "intercomcdn.com", "js.stripe.com", "o207216.ingest.sentry.io", "o33249.ingest.sentry.io", "rum.browser-intake-datadoghq.com"},
}
var telegramCIDRs = []string{"91.108.56.0/22", "91.108.4.0/22", "91.108.8.0/22", "91.108.16.0/22", "91.108.12.0/22", "149.154.160.0/20", "91.105.192.0/23", "91.108.20.0/22", "185.76.151.0/24", "2001:b28:f23d::/48", "2001:b28:f23f::/48", "2001:67c:4e8::/48", "2001:b28:f23c::/48", "2a0a:f280::/32"}

func routeDomains(s Settings) ([]string, error) {
	seen := map[string]bool{}
	presets := s.RoutePresets
	if s.RoutingMode == "chatgpt" {
		presets = []string{"openai"}
	}
	for _, p := range presets {
		ds, ok := serviceDomains[p]
		if !ok {
			return nil, errors.New("سرویس مسیر‌دهی نامعتبر است")
		}
		for _, d := range ds {
			seen[d] = true
		}
	}
	if s.RoutingMode != "chatgpt" {
		for _, line := range strings.FieldsFunc(s.RouteDomains, func(r rune) bool { return r == '\n' || r == '\r' || r == ',' || r == ' ' || r == '\t' }) {
			d := strings.TrimSuffix(strings.TrimPrefix(strings.ToLower(line), "*."), ".")
			if !validPublicHostname(d) {
				return nil, errors.New("برای مسیر فقط دامنه وارد کن؛ بدون نشانی صفحه، پورت یا IP")
			}
			seen[d] = true
			if len(seen) > 200 {
				return nil, errors.New("حداکثر ۲۰۰ دامنه")
			}
		}
	}
	out := []string{}
	for d := range seen {
		out = append(out, d)
	}
	sort.Strings(out)
	return out, nil
}
func validateRouting(s Settings) error {
	switch s.RoutingMode {
	case "", "apps", "domains", "chatgpt":
	default:
		return errors.New("حالت مسیر‌دهی نامعتبر است")
	}
	ds, err := routeDomains(s)
	if err != nil {
		return err
	}
	if s.RoutingMode == "domains" && len(ds) == 0 {
		return errors.New("حداقل یک سرویس یا دامنه برای تونل انتخاب کن")
	}
	return nil
}
func routingSignature(s Settings) string {
	b, _ := json.Marshal([]any{s.KillSwitchOff, s.RoutingMode, s.RoutePresets, s.RouteDomains, s.SplitMode, s.SplitApps, s.SplitExact})
	return string(b)
}
func restrictedRouting(s Settings) bool {
	return s.RoutingMode == "domains" || s.RoutingMode == "chatgpt" || s.SplitMode == "only"
}

// Match helpers in an app-specific installation folder, never a shared browser runtime
// or a common directory such as Program Files, Downloads, System32, or a drive root.
func appFamilyPatterns(executable string) []string {
	normalized := strings.ReplaceAll(executable, `\`, "/")
	dir := path.Dir(normalized)
	stem := strings.ToLower(strings.TrimSuffix(path.Base(normalized), path.Ext(normalized)))
	root := ""
	for d, n := dir, 0; n < 3 && d != "." && d != "/"; d, n = path.Dir(d), n+1 {
		base := strings.ToLower(path.Base(d))
		if len(stem) >= 3 && (base == stem || strings.HasPrefix(base, stem+" ") || strings.HasPrefix(base, stem+"-")) {
			root = d
			break
		}
	}
	slashPattern := func(s string) string { return strings.ReplaceAll(regexp.QuoteMeta(s), "/", `[\\/]`) }
	patterns := []string{}
	if root != "" {
		patterns = append(patterns, `(?i)^`+slashPattern(root)+`[\\/](?:[^\\/]+[\\/])*[^\\/]+\.exe$`)
	}
	// MSIX package versions move on update; keep the package identity and publisher scoped.
	if idx := strings.Index(strings.ToLower(dir), "/windowsapps/"); idx >= 0 {
		tail := dir[idx+13:]
		pkg := strings.SplitN(tail, "/", 2)[0]
		parts := strings.SplitN(pkg, "_", 2)
		if len(parts) == 2 && strings.Contains(parts[1], "__") {
			publisher := pkg[strings.LastIndex(pkg, "__")+2:]
			patterns = append(patterns, `(?i)^`+slashPattern(dir[:idx+13])+regexp.QuoteMeta(parts[0])+`_[^\\/]+__`+regexp.QuoteMeta(publisher)+`[\\/](?:[^\\/]+[\\/])*[^\\/]+\.exe$`)
		}
	}
	return patterns
}
func scopedRules(s Settings) ([]any, error) {
	if s.RoutingMode != "domains" && s.RoutingMode != "chatgpt" {
		return splitRules(s)
	}
	ds, err := routeDomains(s)
	if err != nil {
		return nil, err
	}
	out := []any{}
	if s.RoutingMode == "chatgpt" {
		out = append(out, map[string]any{"inbound": "tun", "process_path_regex": []string{`(?i)(?:^|[\\/])ChatGPT\.exe$`, `(?i)[\\/]WindowsApps[\\/]OpenAI\.ChatGPT_[^\\/]+[\\/].*\.exe$`, `(?i)[\\/]ChatGPT[\\/].*\.exe$`}, "action": "route", "outbound": "proxy"})
	}
	out = append(out, map[string]any{"inbound": "tun", "domain_suffix": ds, "action": "route", "outbound": "proxy"})
	if s.RoutingMode == "chatgpt" && killEnabled(s) {
		out = append(out, map[string]any{"inbound": "tun", "process_path_regex": []string{`(?i)(?:^|[\\/])msedgewebview2\.exe$`}, "action": "reject"})
	}
	for _, preset := range s.RoutePresets {
		if s.RoutingMode == "domains" && preset == "telegram" {
			out = append(out, map[string]any{"inbound": "tun", "ip_cidr": telegramCIDRs, "action": "route", "outbound": "proxy"})
		}
	}
	return append(out, map[string]any{"inbound": "tun", "action": "route", "outbound": "direct"}), nil
}
