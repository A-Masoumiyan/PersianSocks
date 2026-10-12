//go:build !windows

package main

import "errors"

func installedApps() ([]InstalledApp, error) { return []InstalledApp{}, nil }
func chooseExecutable() (string, error) {
	return "", errors.New("انتخاب فایل فقط در ویندوز در دسترس است")
}
func startQuickControls(a *App, done <-chan struct{}) {}
func showQuickWidget() error {
	return errors.New("ویجت فقط در ویندوز در دسترس است")
}
