package main

import (
	"encoding/json"
	"net/http/httptest"
	"testing"
)

func TestSavedSurvivesRemovalAndRestart(t *testing.T) {
	dir := t.TempDir()
	a := newApp(dir)
	e := Endpoint{Host: "8.8.8.8", Port: 1080, Type: "SOCKS5", Country: "IR", Ping: 80}
	a.disk.Results[e.key()] = e
	if err := a.toggleFavorite(e.key()); err != nil {
		t.Fatal(err)
	}
	delete(a.disk.Results, e.key())
	if err := a.save(); err != nil {
		t.Fatal(err)
	}
	a = newApp(dir)
	if len(a.disk.Results) != 0 || len(a.disk.Favorites) != 1 {
		t.Fatal("saved endpoint lost or incorrectly restored as healthy")
	}
	a.mu.Lock()
	rows := a.rowsLocked(Filter{Favorites: true, Country: "IR", Type: "SOCKS5"})
	_, selectable := a.endpointLocked(e.key())
	a.mu.Unlock()
	if len(rows) != 1 || !selectable {
		t.Fatal("saved endpoint unavailable")
	}
	if err := a.act(Action{Action: "select", Key: e.key()}); err != nil {
		t.Fatal(err)
	}
	a.snapshot(httptest.NewRequest("GET", "/api/state", nil))
	if err := a.toggleFavorite(e.key()); err != nil {
		t.Fatal(err)
	}
	if len(newApp(dir).disk.Favorites) != 0 {
		t.Fatal("removal not persisted")
	}
}

func TestCountryFiltersComposeAndScanScope(t *testing.T) {
	a := newApp(t.TempDir())
	ir := Endpoint{Host: "8.8.8.8", Port: 1080, Type: "SOCKS5", Country: "IR", Ping: 80, Services: []string{targets[0].ID}}
	us := ir
	us.Host = "1.1.1.1"
	us.Country = "US"
	a.disk.Results[ir.key()] = ir
	a.disk.Results[us.key()] = us
	a.disk.Favorites[ir.key()] = ir
	a.disk.Favorites[us.key()] = us
	if rows := a.rowsLocked(Filter{Favorites: true, Country: "IR", Type: "SOCKS5", Ping: 500, Service: targets[0].ID}); len(rows) != 1 || rows[0].Country != "IR" {
		t.Fatal(rows)
	}
	if len(a.rowsLocked(Filter{Country: "UNKNOWN"})) != 0 {
		t.Fatal("known country matched unknown")
	}
	s := defaultSettings()
	s.ScanCountry = "IR"
	if !scanAccepts(ir, s) || scanAccepts(us, s) {
		t.Fatal("scan scope ignored")
	}
	for _, c := range []string{"iR", "Iran", "I", "<>", "IR\n"} {
		if validateCountry(c) == nil {
			t.Fatal(c)
		}
	}
}

func TestPublicCatalogAndMixedIranProtocols(t *testing.T) {
	b, err := bundle.ReadFile("assets/sources.json")
	if err != nil {
		t.Fatal(err)
	}
	var sources []Source
	if err = json.Unmarshal(b, &sources); err != nil {
		t.Fatal(err)
	}
	found := false
	for _, s := range sources {
		if s.Name == "Proxifly Iran" {
			found = true
		}
	}
	if !found || len(sources) < 28 {
		t.Fatal("expanded catalog missing")
	}
	rows := parseList("socks4://8.8.8.8:1080\nsocks5://8.8.8.8:1080\nhttp://1.1.1.1:80\n", "SOCKS5", "IR public list")
	if len(rows) != 2 || rows[0].Type != "SOCKS5" || rows[1].Type != "HTTP" {
		t.Fatal("mixed protocols mislabeled", rows)
	}
}
