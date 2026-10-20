package main

import (
	"errors"
	"net"
	"sort"
	"strings"
)

type networkCandidate struct {
	Physical
	Metric      uint32
	Up          bool
	Kind        uint32
	Description string
}

func selectNetwork(candidates []networkCandidate) (Physical, error) {
	sort.SliceStable(candidates, func(i, j int) bool { return candidates[i].Metric < candidates[j].Metric })
	for _, c := range candidates {
		ip := net.ParseIP(c.IP)
		name := strings.ToLower(c.Name + " " + c.Description)
		if !c.Up || c.Index <= 0 || c.Name == "" || ip == nil || ip.To4() == nil || ip.IsLoopback() || ip.IsUnspecified() || ip.IsLinkLocalUnicast() || c.Kind == 24 {
			continue
		}
		blocked := false
		for _, v := range []string{"persiansocks", "wintun", "wireguard", "tap-windows", "tailscale", "sing-box"} {
			if strings.Contains(name, v) {
				blocked = true
			}
		}
		if blocked {
			continue
		}
		c.DNS = usableDNS(c.DNS)
		if len(c.DNS) == 0 {
			c.DNS = []string{"1.1.1.1", "8.8.8.8"}
		}
		return c.Physical, nil
	}
	return Physical{}, errors.New("مسیر فعال IPv4 اینترنت پیدا نشد؛ اتصال Ethernet، Wi-Fi، PPPoE یا اشتراک اینترنت گوشی را بررسی و شناسایی شبکه را دوباره اجرا کن")
}
func usableDNS(values []string) []string {
	out := []string{}
	seen := map[string]bool{}
	for _, v := range values {
		ip := net.ParseIP(v)
		if ip == nil || ip.To4() == nil || ip.IsUnspecified() || ip.IsLoopback() || ip.IsLinkLocalUnicast() {
			continue
		}
		v = ip.String()
		if !seen[v] {
			out = append(out, v)
			seen[v] = true
		}
	}
	return out
}

func validNetworkIPv4(s string) bool {
	ip := net.ParseIP(s)
	return ip != nil && ip.To4() != nil && !ip.IsUnspecified() && !ip.IsLoopback() && !ip.IsLinkLocalUnicast()
}

func (a *App) refreshPhysical() {
	a.mu.Lock()
	idle := a.vpnState == "off" && !a.scanRunning && !a.fetching && len(a.tasks) == 0
	a.mu.Unlock()
	if !idle {
		return
	}
	p, err := physicalNetwork()
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.vpnState != "off" || a.scanRunning || a.fetching || len(a.tasks) > 0 {
		return
	}
	wasReady := physicalReady(a.physical)
	a.physical = p
	if err != nil {
		if !wasReady {
			a.err = err.Error()
		}
		return
	}
	if !wasReady {
		a.err = ""
		a.message = "شبکهٔ اینترنت شناسایی شد: " + p.Name
		a.recordLocked(a.message)
	}
}
