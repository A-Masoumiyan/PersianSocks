$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$release = Join-Path $PSScriptRoot 'release'
$bin = Join-Path $release 'bin'
New-Item -ItemType Directory -Force $bin | Out-Null
function Get-Verified($Url, $File, $Hash) {
    Invoke-WebRequest -Uri $Url -OutFile $File
    if ((Get-FileHash -Algorithm SHA256 $File).Hash.ToLowerInvariant() -ne $Hash) {
        throw "SHA256 mismatch: $File"
    }
}
Get-Verified 'https://github.com/SagerNet/sing-box/releases/download/v1.14.3/sing-box-1.14.3-windows-amd64.zip' "$release\core.zip" 'd8b86ef58936dba9230ec6a57ffa4243933f3c14ef4809828b6c553440ed1518'
Get-Verified 'https://www.wintun.net/builds/wintun-0.14.1.zip' "$release\wintun.zip" '07c256185d6ee3652e09fa55c0b673e2624b565e02c4b9091c79ca7d2f24ef51'
Expand-Archive -Path "$release\core.zip" -DestinationPath "$release\core" -Force
Expand-Archive -Path "$release\wintun.zip" -DestinationPath "$release\wintun" -Force
Copy-Item "$release\core\sing-box-1.14.3-windows-amd64\sing-box.exe" $bin
Copy-Item "$release\core\sing-box-1.14.3-windows-amd64\libcronet.dll" $bin
Copy-Item "$release\core\sing-box-1.14.3-windows-amd64\LICENSE" "$bin\LICENSE-sing-box.txt"
Copy-Item "$release\wintun\wintun\bin\amd64\wintun.dll" $bin
Copy-Item "$release\wintun\wintun\LICENSE.txt" "$bin\LICENSE-wintun.txt"
# Preserve notices and offer the corresponding upstream core source inside the EXE.
Get-Verified 'https://github.com/SagerNet/sing-box/archive/refs/tags/v1.14.3.tar.gz' "$bin\sing-box-1.14.3-source.tar.gz" '9a8e3712a5f611ad95f1d16874bbdcdffbd3b8eed16dae2d71ce7042a33c3386'
Copy-Item "$PSScriptRoot\LICENSE" "$bin\LICENSE-PersianSocks.txt"
Copy-Item "$(go env GOROOT)\LICENSE" "$bin\LICENSE-Go.txt"
Get-Verified 'https://github.com/lipis/flag-icons/archive/refs/tags/v7.5.0.zip' "$release\flags.zip" '7eafedfc07e16ce640dd53309fbe147be0dcfd36d027646cf3798aea79a18913'
Expand-Archive "$release\flags.zip" "$release\flags" -Force
New-Item -ItemType Directory -Force "$PSScriptRoot\assets\flags" | Out-Null
Copy-Item "$release\flags\flag-icons-7.5.0\flags\4x3\*.svg" "$PSScriptRoot\assets\flags"
Copy-Item "$release\flags\flag-icons-7.5.0\LICENSE" "$PSScriptRoot\assets\FLAGS-LICENSE.txt"
Copy-Item "$PSScriptRoot\assets\*-NOTICE.txt" $bin
Copy-Item "$PSScriptRoot\assets\FLAGS-LICENSE.txt" $bin
Copy-Item "$PSScriptRoot\assets\LICENSE-*.txt" $bin
$manifest = @{}
Get-ChildItem $bin -File | ForEach-Object { $manifest[$_.Name] = (Get-FileHash -Algorithm SHA256 $_.FullName).Hash.ToLowerInvariant() }
$manifest | ConvertTo-Json | Set-Content -Encoding ASCII "$bin\manifest.json"
Compress-Archive -Path "$bin\*" -DestinationPath "$PSScriptRoot\assets\windows-runtime.zip" -Force
go run github.com/akavel/rsrc@v0.10.2 -ico assets/app.ico -arch amd64 -o app_windows_amd64.syso
if ($LASTEXITCODE -ne 0) { throw 'Icon resource failed' }
$env:GOOS='windows'
$env:GOARCH='amd64'
$env:CGO_ENABLED='0'
go build -buildvcs=false -trimpath -ldflags '-s -w -H windowsgui -X main.coreSHA=5c19bb3f8c12d4e85b3d47fe63123a7624b314b58dd7a8e32f51004f1c4d2faf -X main.wintunSHA=e5da8447dc2c320edc0fc52fa01885c103de8c118481f683643cacc3220dafce' -o "$release\PersianSocks.exe" .
if ($LASTEXITCODE -ne 0) { throw 'Build failed' }
Write-Output "Built: $release\PersianSocks.exe"
