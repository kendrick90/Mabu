# pre-wipe-capture.ps1
#
# Pulls everything reachable from a freshly-liberated-but-not-wiped Mabu,
# storing under pre-wipe-archive/unit-<serial>/. Run after the
# parameter+adbd patches have been applied (so ADB works) but BEFORE the
# /data wipe (so per-unit data still exists).
#
# Two passes:
#   shell  — works without root. Pulls /data/app/* APKs, /sdcard, dumpsys
#            outputs, partition images via Loader.
#   root   — only runs if -WithRoot is passed AND the device has Magisk's
#            su available. Captures /data/data/com.catalia.factorymode
#            (per-unit calibration), /data/system, etc.
#
# Usage:
#   .\pre-wipe-capture.ps1                    # shell-only pass
#   .\pre-wipe-capture.ps1 -WithRoot          # after Magisk-rooting

[CmdletBinding()]
param(
    [switch] $WithRoot,
    [string] $UsbSerial,
    [string] $WifiIp
)

# NOTE: do NOT use 'Stop' here -- adb push/pull emit progress on stderr,
# and PowerShell treats native-command stderr as terminating errors when
# ErrorActionPreference=Stop. Use 'Continue' and check exit codes ourselves.
$ErrorActionPreference = 'Continue'
$ProgressPreference    = 'SilentlyContinue'
$Root = (Resolve-Path '.').Path
$ADB = (Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\Google.PlatformTools_*\platform-tools\adb.exe" | Select-Object -First 1).FullName
if (-not $ADB) { throw "adb.exe not found" }

function Section($m) { Write-Host ""; Write-Host "==== $m ====" -ForegroundColor Cyan }
function Ok($m)      { Write-Host "  $m" -ForegroundColor Green }
function Info($m)    { Write-Host "  $m" -ForegroundColor Gray }
function Warn($m)    { Write-Host "  $m" -ForegroundColor Yellow }
function Fail($m)    { Write-Host "  $m" -ForegroundColor Red }

# Resolve device
function Find-Device {
    if ($UsbSerial) { return $UsbSerial }
    if ($WifiIp) { & $ADB connect "${WifiIp}:5555" | Out-Null; return "${WifiIp}:5555" }
    $usb = @(& $ADB devices 2>&1 | Where-Object { $_ -match '^\d+\s+device$' })
    if ($usb.Count -gt 0) { return ($usb[0] -split '\s+')[0] }
    return $null
}
$D = Find-Device
if (-not $D) { Fail "No adb device. Apply patches first, then re-run."; exit 1 }
Ok "Using device: $D"

$serial = (& $ADB -s $D shell 'getprop ro.serialno' 2>&1).Trim()
if (-not $serial) { Fail 'Could not read serial.'; exit 1 }
$OutDir = Join-Path $Root "pre-wipe-archive/unit-$serial"
New-Item -ItemType Directory -Force -Path $OutDir,"$OutDir/apks" | Out-Null
Ok "Archive: $OutDir"

# ---- runtime state dumpsys ------------------------------------------------
Section 'Dumpsys / state snapshot'
& $ADB -s $D shell 'getprop'                                   > "$OutDir/getprop.txt" 2>&1
& $ADB -s $D shell 'pm list packages -f'                       > "$OutDir/pm-list.txt" 2>&1
& $ADB -s $D shell 'dumpsys device_policy'                     > "$OutDir/dumpsys-device_policy.txt" 2>&1
& $ADB -s $D shell 'dumpsys user'                              > "$OutDir/dumpsys-user.txt" 2>&1
& $ADB -s $D shell 'dumpsys package'                           > "$OutDir/dumpsys-package.txt" 2>&1
& $ADB -s $D shell 'cmd appops get com.catalia.factorymode'    > "$OutDir/appops-catalia.txt" 2>&1
Ok 'state dumps written'

# ---- APKs from /data/app ---------------------------------------------------
Section '/data/app APKs (shell uid)'
# Parse each line of `pm list packages -f -3`. Format:
#   package:/data/app/<dir>/base.apk=<package.name>
$pmOut = & $ADB -s $D shell 'pm list packages -f -3' 2>&1
foreach ($line in ($pmOut -split "`n")) {
    $line = $line.Trim()
    if (-not $line.StartsWith('package:')) { continue }
    # Format: package:<apkPath>=<packageName>
    # The apkPath ends in "/base.apk" (or "/split_*.apk"). The package
    # directory often contains "==" base64 padding, so we can't split on
    # the first "=". Use the last "=" instead.
    $body = $line.Substring(8)
    $lastEq = $body.LastIndexOf('=')
    if ($lastEq -lt 0) { continue }
    $apkPath = $body.Substring(0, $lastEq)
    $pkgName = $body.Substring($lastEq + 1)
    if ($apkPath -notmatch '\.apk$') { continue }
    $dest = Join-Path "$OutDir/apks" "$pkgName.apk"
    Info "  pull $pkgName"
    & $ADB -s $D pull $apkPath $dest 2>&1 | Out-Null
    if (Test-Path $dest) {
        $mb = [math]::Round((Get-Item $dest).Length/1MB,1)
        Ok "    $mb MB <- $apkPath"
    } else {
        Warn '    pull failed'
    }
}

# ---- /sdcard via adb pull -a -----------------------------------------------
# Use adb's recursive pull rather than tar -- /sdcard is a FUSE mount and
# tar from / treats /sdcard as just the directory entry (~1 KB output).
# adb pull -a walks the tree using the shell-uid-readable FUSE view.
Section '/sdcard (adb pull -a)'
$sdDest = "$OutDir/sdcard"
New-Item -ItemType Directory -Force -Path $sdDest | Out-Null
& $ADB -s $D pull -a /sdcard/ $sdDest 2>&1 | Out-Null
$sdSize = (Get-ChildItem $sdDest -Recurse -File -ErrorAction SilentlyContinue | Measure-Object Length -Sum).Sum
if ($sdSize) {
    Ok "$([math]::Round($sdSize/1MB,1)) MB of /sdcard content"
} else {
    Warn 'no /sdcard content captured'
}

# ---- Root-only captures ----------------------------------------------------
if ($WithRoot) {
    Section 'Root-required captures'
    $rid = & $ADB -s $D shell 'su -c id 2>&1' 2>&1
    if ($rid -notmatch 'uid=0') {
        Fail "su not available (got: $rid). Skip -WithRoot or finish Magisk root first."
    } else {
        Ok "su available: $rid"
        # Calibration
        & $ADB -s $D shell 'su -c "tar cf /sdcard/.pwc-fm.tar -C /data/data com.catalia.factorymode 2>/dev/null; chmod 644 /sdcard/.pwc-fm.tar"' 2>&1 | Out-Null
        & $ADB -s $D pull /sdcard/.pwc-fm.tar "$OutDir/factorymode-data.tar" 2>&1 | Out-Null
        & $ADB -s $D shell 'rm -f /sdcard/.pwc-fm.tar' | Out-Null
        if (Test-Path "$OutDir/factorymode-data.tar") {
            Ok "calibration: $([math]::Round((Get-Item "$OutDir/factorymode-data.tar").Length/1KB,0)) KB"
        }
        # /data/system
        & $ADB -s $D shell 'su -c "tar cf /sdcard/.pwc-ds.tar -C /data system 2>/dev/null; chmod 644 /sdcard/.pwc-ds.tar"' 2>&1 | Out-Null
        & $ADB -s $D pull /sdcard/.pwc-ds.tar "$OutDir/data-system.tar" 2>&1 | Out-Null
        & $ADB -s $D shell 'rm -f /sdcard/.pwc-ds.tar' | Out-Null
        if (Test-Path "$OutDir/data-system.tar") {
            Ok "data-system: $([math]::Round((Get-Item "$OutDir/data-system.tar").Length/1MB,1)) MB"
        }
    }
}

Section 'Pre-wipe capture done'
Info "Archive at: $OutDir"
Info ''
Info 'Suggested next phases:'
Info '  - if not yet rooted: .\scripts\magisk-patch-boot.ps1 then re-run with -WithRoot'
Info '  - else: .\scripts\flash-mabu.ps1 -WipeData -RestoreMabu  (the rest of the liberation)'
