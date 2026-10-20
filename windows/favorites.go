package main

import "errors"

// Call with a.mu held. Saved endpoints survive removal from healthy results.
func (a *App) endpointLocked(key string) (Endpoint, bool) {
	if e, ok := a.disk.Results[key]; ok {
		return e, true
	}
	e, ok := a.disk.Favorites[key]
	return e, ok
}
func (a *App) rowsLocked(f Filter) []Endpoint {
	if !f.Favorites {
		return filteredRows(a.disk.Results, f, a.disk.Settings.TLSOnly)
	}
	saved := make(map[string]Endpoint, len(a.disk.Favorites))
	for k, e := range a.disk.Favorites {
		if fresh, ok := a.disk.Results[k]; ok {
			e = fresh
		}
		saved[k] = e
	}
	return filteredRows(saved, f, a.disk.Settings.TLSOnly)
}
func (a *App) toggleFavorite(key string) error {
	a.mu.Lock()
	if _, ok := a.disk.Favorites[key]; ok {
		delete(a.disk.Favorites, key)
	} else {
		e, ok := a.disk.Results[key]
		if !ok {
			a.mu.Unlock()
			return errors.New("پروکسی موجود نیست")
		}
		if a.disk.Favorites == nil {
			a.disk.Favorites = map[string]Endpoint{}
		}
		a.disk.Favorites[key] = e
	}
	a.mu.Unlock()
	return a.save()
}
