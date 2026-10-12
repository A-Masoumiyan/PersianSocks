//go:build windows

package main

import (
	"context"
	"encoding/json"
	"errors"
	"os/exec"
	"strings"
	"time"
)

func installedApps() ([]InstalledApp, error) {
	script := `$ErrorActionPreference='SilentlyContinue';[Console]::OutputEncoding=[System.Text.UTF8Encoding]::new($false);$items=@{};
 function AddApp($name,$path,$sys=$false){if(!$path){return};$path=[Environment]::ExpandEnvironmentVariables($path.Trim([char]34));if($path -notmatch '(?i)\.exe$' -or !(Test-Path -LiteralPath $path -PathType Leaf)){return};$key=$path.ToLowerInvariant();if(!$items.ContainsKey($key)){$items[$key]=@{name=$name;path=$path;system=($sys -or $path.StartsWith($env:WINDIR,[StringComparison]::OrdinalIgnoreCase))}}}
 $shell=New-Object -ComObject WScript.Shell
 @([Environment]::GetFolderPath('Programs'),[Environment]::GetFolderPath('CommonPrograms'))|ForEach-Object{Get-ChildItem -LiteralPath $_ -Filter '*.lnk' -Recurse|ForEach-Object{$lnk=$shell.CreateShortcut($_.FullName);AddApp $_.BaseName $lnk.TargetPath}}
 @('HKCU:\Software\Microsoft\Windows\CurrentVersion\App Paths','HKLM:\Software\Microsoft\Windows\CurrentVersion\App Paths','HKLM:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\App Paths')|ForEach-Object{Get-ChildItem $_|ForEach-Object{$path=$_.GetValue('');AddApp $_.PSChildName $path}}
 @('HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall','HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall','HKLM:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall')|ForEach-Object{Get-ItemProperty "$_\*"|ForEach-Object{if($_.DisplayName -and $_.DisplayIcon){$path=$_.DisplayIcon -replace ',\s*-?\d+$','';AddApp $_.DisplayName $path ($_.SystemComponent -eq 1)}}}
 Get-CimInstance Win32_Process|ForEach-Object{AddApp $_.Name $_.ExecutablePath}
 @($items.Values|Sort-Object name)|ConvertTo-Json -Depth 3 -Compress`
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, "powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script)
	hideCommand(cmd)
	b, err := cmd.Output()
	if err != nil {
		return nil, errors.New("دریافت برنامه‌ها ناموفق بود؛ می‌توانی فایل exe را دستی اضافه کنی")
	}
	var apps []InstalledApp
	if err = json.Unmarshal(b, &apps); err != nil {
		var one InstalledApp
		if json.Unmarshal(b, &one) == nil && one.Path != "" {
			apps = []InstalledApp{one}
		} else {
			return nil, err
		}
	}
	return apps, nil
}
func chooseExecutable() (string, error) {
	script := `[Console]::OutputEncoding=[System.Text.UTF8Encoding]::new($false);Add-Type -AssemblyName System.Windows.Forms;$d=New-Object System.Windows.Forms.OpenFileDialog;$d.Filter='Windows applications (*.exe)|*.exe';$d.Title='PersianSocks';$d.CheckFileExists=$true;if($d.ShowDialog() -eq 'OK'){$d.FileName|ConvertTo-Json -Compress}`
	cmd := exec.Command("powershell.exe", "-NoProfile", "-STA", "-Command", script)
	hideCommand(cmd)
	b, err := cmd.Output()
	if err != nil {
		return "", err
	}
	if strings.TrimSpace(string(b)) == "" {
		return "", nil
	}
	var path string
	err = json.Unmarshal(b, &path)
	return path, err
}
