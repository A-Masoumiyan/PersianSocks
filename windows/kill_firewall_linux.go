//go:build !windows

package main

import "errors"

func newKillFirewall() (killFirewall, error) {
	return nil, errors.New("کیل‌سوییچ بومی فقط در ویندوز در دسترس است")
}
