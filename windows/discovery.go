package main

import (
	"bufio"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"strconv"
	"strings"
)

const rapidScanMultiplier = 8
const maxRapidScanWorkers = maxScanWorkers * rapidScanMultiplier

func scanWorkerCount(s Settings, rapid bool) int {
	n := max(1, min(maxScanWorkers, s.Workers))
	if rapid {
		n *= rapidScanMultiplier
	}
	return min(maxRapidScanWorkers, n)
}

// Retry after a failed/cancelled initial download on the next launch, without
// restarting a download the user has just stopped in this session.
func (a *App) claimInitialFetch() bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.closing || a.disk.InitialFetchDone || a.initialFetchAttempted || a.fetching || !physicalReady(a.physical) {
		return false
	}
	a.initialFetchAttempted = true
	return true
}
func (a *App) maybeInitialFetch() {
	if os.Getenv("PERSIANSOCKS_DESKTOP_SMOKE") == "1" {
		return
	}
	if a.claimInitialFetch() {
		_ = a.fetch()
	}
}

func prioritizeIran(input []Endpoint) []Endpoint {
	out := make([]Endpoint, 0, len(input))
	for _, e := range input {
		if e.Country == "IR" {
			out = append(out, e)
		}
	}
	for _, e := range input {
		if e.Country != "IR" {
			out = append(out, e)
		}
	}
	return out
}

// Source documents are data only. Never execute a collector from an upstream repo.
func readSource(r io.Reader, src Source) ([]Endpoint, error) {
	const limit = 16 << 20
	b, err := io.ReadAll(io.LimitReader(r, limit+1))
	if err != nil {
		return nil, err
	}
	if len(b) > limit {
		return nil, errors.New("source exceeds 16 MiB")
	}
	out := []Endpoint{}
	seen := map[string]bool{}
	add := func(address, typ string) {
		e, ok := parseEndpoint(address, typ, src.Name)
		if !ok || seen[e.key()] {
			return
		}
		e.Country = country(e.Host)
		if src.Country != "" && e.Country != src.Country {
			return
		}
		seen[e.key()] = true
		out = append(out, e)
	}
	if src.Format == "json" {
		var root any
		if err = json.Unmarshal(b, &root); err != nil {
			return nil, err
		}
		rows, ok := root.([]any)
		if !ok {
			if obj, yes := root.(map[string]any); yes {
				rows, ok = obj["data"].([]any)
			}
		}
		if !ok {
			return nil, errors.New("invalid proxy JSON array")
		}
		for _, raw := range rows {
			row, ok := raw.(map[string]any)
			if !ok {
				continue
			}
			text := func(k string) string {
				v, ok := row[k].(string)
				if ok {
					return v
				}
				if v, ok := row[k].(float64); ok && v >= 1 && v <= 65535 && v == float64(int(v)) {
					return strconv.Itoa(int(v))
				}
				return ""
			}
			address := text("proxy")
			if address == "" {
				host := text("host")
				if host == "" {
					host = text("ip")
				}
				address = net.JoinHostPort(host, text("port"))
			}
			protocols := []string{text("protocol")}
			if list, yes := row["protocols"].([]any); yes {
				protocols = nil
				for _, v := range list {
					if t, yes := v.(string); yes {
						protocols = append(protocols, t)
					}
				}
			}
			for _, typ := range protocols {
				if typ == "" {
					typ = src.Type
				}
				add(address, strings.ToUpper(typ))
			}
		}
	} else {
		scan := bufio.NewScanner(strings.NewReader(string(b)))
		scan.Buffer(make([]byte, 4096), 65536)
		for scan.Scan() {
			add(scan.Text(), src.Type)
		}
		if err = scan.Err(); err != nil {
			return nil, err
		}
	}
	if len(out) == 0 {
		return out, fmt.Errorf("no supported public proxies in source")
	}
	return out, nil
}
