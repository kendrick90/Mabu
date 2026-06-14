# magisk-patch-boot.ps1
#
# Roots a liberated Mabu by patching its boot.img with Magisk.
# Manual touch-UI step in the middle (Magisk's "Select and Patch a File"
# flow inside the Magisk app, the only Magisk-official method that
# preserves the AVB structure correctly).
#
# Prereqs:
#   - parameter+adbd patches already applied (so ADB works)
#   - device booted to Android (NOT Loader)
#   - apks/Magisk.apk present in repo (committed)
#
# Flow:
#   1. Install Magisk app
#   2. Reboot to Loader
#   3. Dump current boot partition -> firmware/scratch/boot-<serial>.img
#   4. Reboot to Android
#   5. Push boot.img to /sdcard/Download/
#   6. PAUSE -- you tap through Magisk app to patch
#   7. Wait for magisk_patched_*.img to appear in /sdcard/Download/
#   8. Pull patched.img to host
#   9. Reboot to Loader
#  10. Write patched.img to boot partition
#  11. Reboot to Android
#  12. Verify su works

[CmdletBinding()]
param(
    [string] $UsbSerial,
    [string] $WifiIp
)

$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path '.').Path
$RK = Join-Path $Root 'tools\rkdeveloptool\rkdeveloptool.exe'
$ADB = (Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\Google.PlatformTools_*\platform-tools\adb.exe" | Select-Object -First 1).FullName
if (-not $ADB) { throw "adb.exe not found" }
$MagiskApk = Join-Path $Root 'apks\Magisk.apk'
if (-not (Test-Path $MagiskApk)) { throw "Missing $MagiskApk -- commit it first" }

function Section($m) { Write-Host ""; Write-Host "==== $m ====" -ForegroundColor Cyan }
function Ok($m)      { Write-Host "  $m" -ForegroundColor Green }
function Info($m)    { Write-Host "  $m" -ForegroundColor Gray }
function Warn($m)    { Write-Host "  $m" -ForegroundColor Yellow }
function Fail($m)    { Write-Host "  $m" -ForegroundColor Red }
function Test-Loader { (& $RK ld 2>&1) -match 'Vid=0x2207,Pid=0x320a.*Loader' }

function Find-Device {
    if ($UsbSerial) { return $UsbSerial }
    if ($WifiIp) { & $ADB connect "${WifiIp}:5555" | Out-Null; return "${WifiIp}:5555" }
    $usb = @(& $ADB devices 2>&1 | Where-Object { $_ -match '^\d+\s+device$' })
    if ($usb.Count -gt 0) { return ($usb[0] -split '\s+')[0] }
    return $null
}

function Wait-Android($timeoutSec = 180) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        $d = Find-Device
        if ($d) {
            $ok = & $ADB -s $d shell 'getprop sys.boot_completed' 2>&1
            if ($ok.Trim() -eq '1') { return $d }
        }
        Start-Sleep -Seconds 3
    }
    return $null
}

function Wait-Loader($timeoutSec = 60) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        if (Test-Loader) { return $true }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

# --- Find device ------------------------------------------------------------
Section 'Detect device'
$D = Find-Device
if (-not $D) { Fail 'No adb device. Make sure liberate-mabu has been run and device is booted.'; exit 1 }
Ok "Device: $D"
$serial = (& $ADB -s $D shell 'getprop ro.serialno' 2>&1).Trim()
Ok "Serial: $serial"

# --- Install Magisk APK -----------------------------------------------------
Section 'Install Magisk app'
$installed = (& $ADB -s $D shell 'pm list packages | grep com.topjohnwu.magisk' 2>&1).Trim()
if ($installed -match 'com.topjohnwu.magisk') {
    Ok 'Magisk already installed.'
} else {
    Info "Installing $MagiskApk"
    $out = & $ADB -s $D install $MagiskApk 2>&1 | Select-Object -Last 1
    if ($out -notmatch 'Success') {
        Fail "Install failed: $out"
        Info 'Magisk v30 needs Android 9+. If this fails, try an older Magisk (e.g. v27 supports A8.1).'
        exit 1
    }
    Ok 'Installed.'
}

# --- Reboot to Loader to dump boot partition --------------------------------
Section 'Dump current boot.img'
$bootFile = Join-Path $Root "firmware/scratch/boot-$serial.img"
New-Item -ItemType Directory -Force -Path (Split-Path $bootFile) | Out-Null
if (Test-Path $bootFile) { Remove-Item $bootFile -Force }

Info 'Rebooting to Loader...'
& $ADB -s $D shell reboot loader 2>&1 | Out-Null
if (-not (Wait-Loader 60)) { Fail 'Loader did not appear.'; exit 1 }
Ok 'Loader caught.'

# Boot partition: start=0x20000, size=0x10000 sectors (32 MB)
Info "Reading boot partition (32 MB) -> $bootFile"
$out = & $RK rl 0x20000 0x10000 $bootFile 2>&1
if (-not (Test-Path $bootFile) -or (Get-Item $bootFile).Length -ne 33554432) {
    Fail "Boot dump failed: $out"
    exit 1
}
Ok "boot.img: 32 MB dumped"

Info 'Resetting back to Android...'
& $RK rd 2>&1 | Out-Null
$D = Wait-Android 180
if (-not $D) { Fail 'Device did not come back online.'; exit 1 }
Ok "Back online: $D"

# --- Push boot.img + manual patch flow --------------------------------------
Section 'Push boot.img to device for Magisk patching'
& $ADB -s $D shell 'mkdir -p /sdcard/Download' | Out-Null
& $ADB -s $D push $bootFile "/sdcard/Download/boot-$serial.img" 2>&1 | Out-Null
Ok "Pushed to /sdcard/Download/boot-$serial.img"

Write-Host ""
Warn 'MANUAL STEP on the touch UI:'
Warn '  1. Open the Magisk app (in app drawer / Lawnchair).'
Warn '  2. Tap the "Install" button (top card).'
Warn '  3. Choose "Select and Patch a File".'
Warn "  4. Navigate to /sdcard/Download/ and pick boot-$serial.img"
Warn '  5. Tap "Let''s Go" (arrow icon).'
Warn '  6. Magisk patches and saves magisk_patched_XXX.img in the same folder.'
Warn '     This may take ~30 seconds. Watch for the "All done!" message.'
Write-Host ''
Read-Host 'Press Enter once Magisk has produced magisk_patched_*.img'

# --- Pull patched.img -------------------------------------------------------
Section 'Pull patched boot.img'
$listing = & $ADB -s $D shell 'ls -t /sdcard/Download/magisk_patched_*.img 2>/dev/null' 2>&1
$patchedRemote = ($listing -split "`n" | Where-Object { $_ -match '^/sdcard/' } | Select-Object -First 1).Trim()
if (-not $patchedRemote) {
    Fail "Could not find magisk_patched_*.img in /sdcard/Download/."
    Info "Listing: $listing"
    exit 1
}
$patchedLocal = Join-Path $Root "firmware/scratch/boot-magisk-$serial.img"
if (Test-Path $patchedLocal) { Remove-Item $patchedLocal -Force }
& $ADB -s $D pull $patchedRemote $patchedLocal 2>&1 | Out-Null
if (-not (Test-Path $patchedLocal)) { Fail 'Pull failed.'; exit 1 }
$sz = (Get-Item $patchedLocal).Length
Ok "Patched boot.img: $sz bytes ($([math]::Round($sz/1MB,1)) MB) at $patchedLocal"

if ($sz -ne 33554432) {
    Warn "Patched image size ($sz) differs from original (33554432). Loader write may complain."
    Warn 'Pad to 32 MB if needed; otherwise continue.'
}

# --- Reboot to Loader, write patched boot, reboot ---------------------------
Section 'Flash patched boot.img'
& $ADB -s $D shell reboot loader 2>&1 | Out-Null
if (-not (Wait-Loader 60)) { Fail 'Loader did not appear.'; exit 1 }
Ok 'Loader caught.'

Info "Writing $patchedLocal to boot partition (LBA 0x20000)..."
$out = & $RK wl 0x20000 $patchedLocal 2>&1
if ($out -notmatch '100%') { Fail "Write failed: $out"; exit 1 }
Ok 'Write OK.'

Info 'Resetting...'
& $RK rd 2>&1 | Out-Null
$D = Wait-Android 240
if (-not $D) {
    Fail 'Device did not boot after Magisk patch.'
    Info 'If unbootable: re-enter Loader and restore with: rkdeveloptool wl 0x20000 ' + $bootFile
    exit 1
}
Ok "Booted: $D"

# --- Verify su --------------------------------------------------------------
Section 'Verify root'
Start-Sleep -Seconds 6   # let Magisk daemon initialize
$idOut = & $ADB -s $D shell 'su -c id 2>&1' 2>&1
if ($idOut -match 'uid=0') {
    Ok "ROOT acquired: $idOut"
} else {
    Warn "su did not return uid=0. Got: $idOut"
    Warn 'Open Magisk app, follow its setup prompts (it may want one more reboot), then re-test:'
    Warn "   adb -s $D shell su -c id"
}

Write-Host ''
Ok "Magisk-patched boot.img stored at: $patchedLocal"
Ok "Original boot.img backup at:        $bootFile"
