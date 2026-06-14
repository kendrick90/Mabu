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

$ErrorActionPreference = 'Stop'
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
# Get path-per-package, then pull each
$lines = & $ADB -s $D shell "pm list packages -f -3 | sed -e 's/^package://' -e 's/=.*//'" 2>&1
foreach ($p in $lines) {
    $p = $p.Trim()
    if (-not $p) { continue }
    if ($p -notmatch '^/data/app/.+/base\.apk$') { continue }
    $pkg = (& $ADB -s $D shell "pm list packages -f | grep $([regex]::Escape($p))").Trim()
    if ($pkg -match '=([^=]+)$') { $pkgName = $matches[1].Trim() } else { $pkgName = 'unknown' }
    $dest = Join-Path "$OutDir/apks" "$pkgName.apk"
    Info "  pull $pkgName <- $p"
    & $ADB -s $D pull $p $dest 2>&1 | Out-Null
    if (Test-Path $dest) { Ok "    $(([math]::Round((Get-Item $dest).Length/1MB,1))) MB" } else { Warn '    pull failed' }
}

# ---- /sdcard tarball -------------------------------------------------------
Section '/sdcard tarball'
$null = & $ADB -s $D shell 'tar cf /sdcard/.pre-wipe-sdcard.tar -C / sdcard 2>/dev/null' 2>&1
& $ADB -s $D pull /sdcard/.pre-wipe-sdcard.tar "$OutDir/sdcard.tar" 2>&1 | Out-Null
& $ADB -s $D shell 'rm -f /sdcard/.pre-wipe-sdcard.tar' 2>&1 | Out-Null
if (Test-Path "$OutDir/sdcard.tar") {
    Ok "$([math]::Round((Get-Item "$OutDir/sdcard.tar").Length/1MB,1)) MB"
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
