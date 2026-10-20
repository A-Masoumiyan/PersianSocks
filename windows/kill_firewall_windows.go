//go:build windows

package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"syscall"
	"unsafe"

	"golang.org/x/sys/windows"
	wf "persiansocks/internal/wfp"
)

var killSublayer = wf.SublayerID{0x8539c5d4, 0xd729, 0x431e, [8]byte{0x95, 0x2d, 0xd7, 0x52, 0x72, 0xb1, 0x08, 0x80}}

type windowsKillFirewall struct {
	session  *wf.Session
	sublayer wf.SublayerID
	// Tests restrict the otherwise identical block rules to isolated child EXEs.
	scope         []string
	blockLoopback bool
	signature     string
}

func newKillFirewall() (killFirewall, error) { return openKillFirewall(killSublayer, nil, false) }
func openKillFirewall(id wf.SublayerID, scope []string, blockLoopback bool) (*windowsKillFirewall, error) {
	s, err := wf.New(&wf.Options{Name: "PersianSocks fail-closed guard", Dynamic: false})
	if err != nil {
		return nil, err
	}
	f := &windowsKillFirewall{session: s, sublayer: id, scope: scope, blockLoopback: blockLoopback}
	return f, nil
}
func (f *windowsKillFirewall) owned() ([]*wf.Rule, error) {
	rs, e := f.session.Rules()
	if e != nil {
		return nil, e
	}
	out := []*wf.Rule{}
	for _, r := range rs {
		if r.Sublayer == f.sublayer {
			out = append(out, r)
		}
	}
	return out, nil
}
func tunLUID(name string) uint64 {
	i, err := net.InterfaceByName(name)
	if err != nil {
		return 0
	}
	var luid uint64
	r, _, _ := syscall.NewLazyDLL("iphlpapi.dll").NewProc("ConvertInterfaceIndexToLuid").Call(uintptr(i.Index), uintptr(unsafe.Pointer(&luid)))
	if r != 0 {
		return 0
	}
	return luid
}
func (f *windowsKillFirewall) Arm(allowed []string, tunName string) error {
	existsKey := []any{}
	for _, p := range allowed {
		_, e := os.Stat(p)
		existsKey = append(existsKey, []any{p, e == nil})
	}
	sigBytes, _ := json.Marshal([]any{existsKey, tunLUID(tunName), f.scope, f.blockLoopback})
	sig := string(sigBytes)
	if f.signature == sig {
		return nil
	}
	layers, err := f.session.Sublayers()
	if err != nil {
		return err
	}
	exists := false
	for _, s := range layers {
		if s.ID == f.sublayer {
			exists = true
		}
	}
	if !exists {
		if err = f.session.AddSublayer(&wf.Sublayer{ID: f.sublayer, Name: "PersianSocks Kill Switch", Weight: 0xf000}); err != nil {
			return err
		}
	}
	old, err := f.owned()
	if err != nil {
		return err
	}
	added := []wf.RuleID{}
	add := func(layer wf.LayerID, name string, weight uint64, action wf.Action, conditions ...*wf.Match) error {
		id, er := windows.GenerateGUID()
		if er != nil {
			return er
		}
		r := &wf.Rule{ID: wf.RuleID(id), Name: "PersianSocks: " + name, Layer: layer, Sublayer: f.sublayer, Weight: weight, Action: action, Conditions: conditions}
		if er = f.session.AddRule(r); er != nil {
			return fmt.Errorf("%s: %w", name, er)
		}
		added = append(added, r.ID)
		return nil
	}
	match := func(field wf.FieldID, value any) *wf.Match {
		return &wf.Match{Field: field, Op: wf.MatchTypeEqual, Value: value}
	}
	appIDs := []string{}
	for _, exe := range allowed {
		if _, e := os.Stat(exe); e != nil {
			continue
		}
		id, e := wf.AppID(exe)
		if e != nil {
			return e
		}
		appIDs = append(appIDs, id)
	}
	luid := tunLUID(tunName)
	for _, layer := range []wf.LayerID{wf.LayerALEAuthConnectV4, wf.LayerALEAuthConnectV6} {
		for _, id := range appIDs {
			if err = add(layer, "controller/core direct transport", 100, wf.ActionPermit, match(wf.FieldALEAppID, id)); err != nil {
				break
			}
		}
		if err != nil {
			break
		}
		if !f.blockLoopback {
			err = add(layer, "local IPC", 95, wf.ActionPermit, &wf.Match{Field: wf.FieldFlags, Op: wf.MatchTypeFlagsAllSet, Value: wf.ConditionFlagIsLoopback})
			if err != nil {
				break
			}
		}
		if luid != 0 {
			if err = add(layer, "traffic entering protected TUN", 90, wf.ActionPermit, match(wf.FieldIPLocalInterface, luid)); err != nil {
				break
			}
		}
		// Preserve address assignment; these ports cannot carry ordinary application requests.
		local, remote := uint16(68), uint16(67)
		if layer == wf.LayerALEAuthConnectV6 {
			local, remote = 546, 547
		}
		if err = add(layer, "DHCP", 80, wf.ActionPermit, match(wf.FieldIPProtocol, wf.IPProtoUDP), match(wf.FieldIPLocalPort, local), match(wf.FieldIPRemotePort, remote)); err != nil {
			break
		}
		if len(f.scope) == 0 {
			err = add(layer, "block direct fallback", 1, wf.ActionBlock)
		} else {
			for _, exe := range f.scope {
				id, e := wf.AppID(exe)
				if e != nil {
					err = e
					break
				}
				if err = add(layer, "block isolated test application", 1, wf.ActionBlock, match(wf.FieldALEAppID, id)); err != nil {
					break
				}
			}
		}
		if err != nil {
			break
		}
	}
	if err != nil {
		// Keep any old complete policy. The UI must not report protection after an error.
		if len(old) > 0 {
			for _, id := range added {
				_ = f.session.DeleteRule(id)
			}
		}
		return err
	}
	// Install replacement blocks before deleting the old set: updates can fail
	// closed, but cannot leave a direct-traffic opening between configurations.
	for _, r := range old {
		if e := f.session.DeleteRule(r.ID); e != nil {
			err = errors.Join(err, e)
		}
	}
	if err == nil {
		f.signature = sig
	}
	return err
}
func (f *windowsKillFirewall) Release() error {
	f.signature = ""
	rs, err := f.owned()
	if err != nil {
		return err
	}
	for _, r := range rs {
		if e := f.session.DeleteRule(r.ID); e != nil {
			err = errors.Join(err, e)
		}
	}
	if err == nil {
		sl, e := f.session.Sublayers()
		if e != nil {
			return e
		}
		for _, v := range sl {
			if v.ID == f.sublayer {
				return f.session.DeleteSublayer(f.sublayer)
			}
		}
	}
	return err
}
func (f *windowsKillFirewall) Close() error { return f.session.Close() }
