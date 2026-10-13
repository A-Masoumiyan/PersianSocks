$ErrorActionPreference = 'Stop'
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class WindowSmoke {
 [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr h,uint m,IntPtr w,IntPtr l);
 [DllImport("user32.dll",CharSet=CharSet.Unicode)] public static extern IntPtr FindWindow(string c,string t);
}
'@
$exe=(Resolve-Path windows/release/PersianSocks.exe).Path
$data=Join-Path $env:RUNNER_TEMP 'PersianSocks-desktop-smoke'
$env:PERSIANSOCKS_DATA=$data
Remove-Item Env:PERSIANSOCKS_NO_BROWSER -ErrorAction SilentlyContinue
$p=Start-Process -FilePath $exe -PassThru
function Wait-For($condition,$message){for($i=0;$i -lt 150;$i++){if(&$condition){return};Start-Sleep -Milliseconds 200};throw $message}
try {
 $urlFile=Join-Path $data 'PersianSocks/UI.url'
 Wait-For {Test-Path $urlFile} 'App did not start its controller'
 $raw=[IO.File]::ReadAllText($urlFile);$parts=$raw.Split('#');$base=$parts[0].TrimEnd('/');$headers=@{'X-App-Token'=$parts[1]}
 function Get-State{Invoke-RestMethod "$base/api/state" -Headers $headers}
 function Do-Action($payload){Invoke-RestMethod "$base/api/action" -Method Post -Headers $headers -ContentType 'application/json' -Body ($payload|ConvertTo-Json -Depth 10)|Out-Null}
 Wait-For {(Get-State).desktop.ready} 'Embedded WebView did not initialize'
 $h=[WindowSmoke]::FindWindow('PersianSocksDesktopWindow','PersianSocks')
 if($h -eq [IntPtr]::Zero){throw 'Primary window missing'}
 [WindowSmoke]::PostMessage($h,0x10,[IntPtr]::Zero,[IntPtr]::Zero)|Out-Null
 Wait-For {!((Get-State).desktop.visible)} 'Close did not hide primary window'
 if($p.HasExited){throw 'Default close exited instead of continuing in background'}
 for($attempt=0;$attempt -lt 3;$attempt++) {
  $second=Start-Process -FilePath $exe -PassThru
  Wait-For {(Get-State).desktop.visible} 'Second launch did not restore primary window'
  $second.WaitForExit(5000)|Out-Null
  if(!$second.HasExited){throw 'Second launch created an extra running instance'}
  if([WindowSmoke]::FindWindow('PersianSocksDesktopWindow','PersianSocks') -ne $h){throw 'Window identity changed'}
 }
 $settings=(Get-State).settings;$settings.exitOnClose=$true;Do-Action @{action='settings';settings=$settings}
 [WindowSmoke]::PostMessage($h,0x10,[IntPtr]::Zero,[IntPtr]::Zero)|Out-Null
 Wait-For {$p.Refresh();$p.HasExited} 'Exit-on-close did not stop EXE'
 if(Test-Path $urlFile){throw 'Local UI marker remained after exit'}
 $saved=Get-Content (Join-Path $data 'PersianSocks/state.json') -Raw|ConvertFrom-Json
 if(!$saved.settings.exitOnClose){throw 'Close setting was not saved'}
 Write-Output 'EXE smoke passed: WebView ready, hide, controller alive, single-instance restore, exit, persistence.'
} finally {
 if(!$p.HasExited){$p.Kill()}
 Remove-Item Env:PERSIANSOCKS_DATA -ErrorAction SilentlyContinue
}
