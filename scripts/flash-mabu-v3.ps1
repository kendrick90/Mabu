# flash-mabu-v3.ps1
#
# Liberation procedure with pre-wipe data capture. (Magisk root attempt
# is preserved as an opt-in for future hardware revisions, but the
# default skips it -- Magisk v30.7 and v27.0 both produce boot images
# that hang at "no command" recovery on this RK3288 H7R Mabu boot.img
# layout. See notes/magisk-incompatibility.md.)
#
# See notes/v3-procedure-outline.md for the design rationale.
#
# Default phase order (with -TryMagisk OFF, the default):
#   1. Catch Loader (poll for it; you power-cycle or hit recovery combo)
#   2. Apply parameter + adbd patches ONLY (no EOCD nukes, no /data wipe)
#   3. Reboot to Android (Esper kiosk still active but ADB works)
#   4. Pre-wipe shell-uid capture: /data/app APKs, /sdcard, dumpsys
#   5. Destructive patches: 3x EOCD nukes, init.esper.rc + sdo.sh zeros
#   6. Wipe /data head 96 MB
#   7. Reboot to Android
#   8. User sets up WiFi on touch UI (creds wiped)
#   9. Install apps (F-Droid + Lawnchair + factorymode + OpenCV Manager)
#  10. Push animation CSVs + nuance assets + sound.raw
#
# With -TryMagisk: inserts the magisk + capture-root phases between 4 and 5.
# Don't expect it to work on RK3288 8.1 -- documented as a known failure.
# Use only if you've prepared the recovery flow (power off + vol-up + plug-in
# to enter Loader to restore firmware/originals/boot.img).
#
# Usage:
#   .\scripts\flash-mabu-v3.ps1                       # default: no Magisk
#   .\scripts\flash-mabu-v3.ps1 -TryMagisk            # attempt Magisk root (known to fail on H7R)
#   .\scripts\flash-mabu-v3.ps1 -SkipCapture          # don't capture pre-wipe
#   .\scripts\flash-mabu-v3.ps1 -ResumeFrom <phase>   # restart at a phase

[CmdletBinding()]
param(
    [switch] $TryMagisk,
    [switch] $SkipCapture,
    [switch] $SkipFinalApps,
    [int]    $WipeMB = 96,
    [string] $WifiIp,
    [string] $UsbSerial,
    [ValidateSet('catch','patches','capture-shell','magisk','capture-root','destructive','wipe','apps','done')]
    [string] $ResumeFrom = 'catch'
)

$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path '.').Path
$RK = Join-Path $Root 'tools\rkdeveloptool\rkdeveloptool.exe'
$ADB = (Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\Google.PlatformTools_*\platform-tools\adb.exe" | Select-Object -First 1).FullName
if (-not $ADB) { throw "adb.exe not found" }

function Section($m) { Write-Host ""; Write-Host "==== $m ====" -ForegroundColor Cyan }
function Phase($m)   { Write-Host ""; Write-Host "########## PHASE: $m ##########" -ForegroundColor Magenta }
function Ok($m)      { Write-Host "  $m" -ForegroundColor Green }
function Info($m)    { Write-Host "  $m" -ForegroundColor Gray }
function Warn($m)    { Write-Host "  $m" -ForegroundColor Yellow }
function Fail($m)    { Write-Host "  $m" -ForegroundColor Red }
function Test-Loader { (& $RK ld 2>&1) -match 'Vid=0x2207,Pid=0x320a.*Loader' }

function Find-Device {
    if ($UsbSerial) { return $UsbSerial }
    if ($WifiIp) {
        & $ADB connect "${WifiIp}:5555" 2>&1 | Out-Null
        $ok = & $ADB -s "${WifiIp}:5555" shell echo ok 2>&1
        if ($ok -match '^ok') { return "${WifiIp}:5555" }
    }
    $usb = @(& $ADB devices 2>&1 | Where-Object { $_ -match '^\d+\s+device$' })
    if ($usb.Count -gt 0) { return ($usb[0] -split '\s+')[0] }
    return $null
}

function Wait-Android($timeout = 240) {
    $deadline = (Get-Date).AddSeconds($timeout)
    while ((Get-Date) -lt $deadline) {
        $d = Find-Device
        if ($d) {
            $b = & $ADB -s $d shell 'getprop sys.boot_completed' 2>&1
            if ($b.Trim() -eq '1') { return $d }
        }
        Start-Sleep -Seconds 4
    }
    return $null
}

function Wait-Loader($timeout = 90) {
    $deadline = (Get-Date).AddSeconds($timeout)
    while ((Get-Date) -lt $deadline) {
        if (Test-Loader) { return $true }
        Start-Sleep -Milliseconds 300
    }
    return $false
}

function Apply-NonDestructivePatches {
    # Only parameter + 2x adbd. Skip EOCD nukes + init zeros until after capture.
    $items = @(
        @{ Name='parameter';        Lba=0;       File='firmware/patches/parameter-patched.img' },
        @{ Name='adbd-authreq';     Lba=1696240; File='firmware/patches/adbd-authreq-patched.bin' },
        @{ Name='adbd-authinit';    Lba=1694778; File='firmware/patches/adbd-authinit-patched.bin' }
    )
    foreach ($i in $items) {
        $p = Join-Path $Root $i.File
        if (-not (Test-Path $p)) { throw "Missing: $p" }
        Info "patch $($i.Name) @ LBA $($i.Lba)"
        $r = & $RK wl $i.Lba $p 2>&1
        if ($r -notmatch '100%') { throw "Write failed: $r" }
    }
    Ok 'parameter + adbd patches applied (non-destructive)'
}

function Apply-DestructivePatches {
    # Now write the EOCD nukes and the init script zeros.
    $items = @(
        @{ Name='espersupervisor-eocd'; Lba=1851238; File='firmware/patches/espersupervisor-apk-eocd-patched.bin' },
        @{ Name='esperdpc-eocd';        Lba=1981802; File='firmware/patches/esperdpc-apk-eocd-patched.bin' },
        @{ Name='esperhelper-eocd';     Lba=2063565; File='firmware/patches/esperhelper-apk-eocd-patched.bin' },
        @{ Name='set-device-owner.sh';  Lba=1691408; File='firmware/patches/zeros-4k.bin' },
        @{ Name='init.esper.rc';        Lba=2076672; File='firmware/patches/zeros-4k.bin' }
    )
    foreach ($i in $items) {
        $p = Join-Path $Root $i.File
        if (-not (Test-Path $p)) { throw "Missing: $p" }
        Info "patch $($i.Name) @ LBA $($i.Lba)"
        $r = & $RK wl $i.Lba $p 2>&1
        if ($r -notmatch '100%') { throw "Write failed: $r" }
    }
    Ok 'EOCD nukes + init script zeros written'
}

$phaseOrder = @('catch','patches','capture-shell','magisk','capture-root','destructive','wipe','apps','done')
$startIdx = [array]::IndexOf($phaseOrder, $ResumeFrom)
if ($startIdx -lt 0) { throw "Bad ResumeFrom: $ResumeFrom" }
function Should-Run($phase) {
    $idx = [array]::IndexOf($phaseOrder, $phase)
    return $idx -ge $startIdx
}

# =========================================================================
# PHASE: catch Loader
# =========================================================================
if (Should-Run 'catch') {
    Phase 'Catch Loader'
    if (Test-Loader) {
        Ok 'Loader already present.'
    } else {
        $d = Find-Device
        if ($d) {
            Info "Found adb device $d. Sending reboot loader."
            & $ADB -s $d shell reboot loader 2>&1 | Out-Null
        } else {
            Warn 'No adb device. POWER-CYCLE the tablet now.'
            Warn '(Recovery button combo is reliable: vol-up + power-on.'
            Warn ' Or just unplug + replug power.)'
        }
        Info 'Polling for Loader (up to 120s)...'
        if (-not (Wait-Loader 120)) { Fail 'Loader not caught. Try power-cycling again.'; exit 1 }
        Ok 'Loader caught.'
    }
}

# =========================================================================
# PHASE: non-destructive patches
# =========================================================================
if (Should-Run 'patches') {
    Phase 'Apply parameter + adbd patches'
    if (-not (Test-Loader)) { Fail 'Loader required.'; exit 1 }
    Apply-NonDestructivePatches
    Info 'Resetting to Android...'
    & $RK rd 2>&1 | Out-Null
    Start-Sleep -Seconds 4
    $d = Wait-Android 240
    if (-not $d) { Fail 'Device did not come back. Power-cycle and ResumeFrom capture-shell.'; exit 1 }
    Ok "Back online: $d"
}

# =========================================================================
# PHASE: pre-wipe shell-uid capture
# =========================================================================
if (Should-Run 'capture-shell' -and -not $SkipCapture) {
    Phase 'Pre-wipe capture (shell)'
    & (Join-Path $Root 'scripts/pre-wipe-capture.ps1')
    if ($LASTEXITCODE -ne 0) { Warn 'pre-wipe-capture returned non-zero; continuing anyway.' }
}

# =========================================================================
# PHASE: Magisk root
# =========================================================================
if (Should-Run 'magisk' -and $TryMagisk) {
    Phase 'Magisk root (known to fail on RK3288 H7R)'
    Warn 'Magisk repacks of this boot.img hang at recovery "no command" --'
    Warn 'tested with v30.7 and v27.0 (June 2026). See notes/magisk-incompatibility.md.'
    Warn 'Recovery if device hangs: power off, hold vol-up, plug in --> Loader'
    Warn 'Then: rkdeveloptool wl 0x20000 firmware/originals/boot.img'
    & (Join-Path $Root 'scripts/magisk-patch-boot.ps1')
    if ($LASTEXITCODE -ne 0) { Warn 'Magisk patch returned non-zero. Decide whether to continue.'; }
}

# =========================================================================
# PHASE: pre-wipe root-uid capture
# =========================================================================
if (Should-Run 'capture-root' -and -not $SkipCapture -and $TryMagisk) {
    Phase 'Pre-wipe capture (root)'
    & (Join-Path $Root 'scripts/pre-wipe-capture.ps1') -WithRoot
}

# =========================================================================
# PHASE: destructive patches
# =========================================================================
if (Should-Run 'destructive') {
    Phase 'Destructive patches (EOCD nukes + init zeros)'
    $d = Find-Device
    if (-not $d) { Fail 'No device; cannot reboot to Loader.'; exit 1 }
    Info 'Rebooting to Loader for destructive writes...'
    & $ADB -s $d shell reboot loader 2>&1 | Out-Null
    if (-not (Wait-Loader 90)) { Fail 'Loader not caught.'; exit 1 }
    Apply-DestructivePatches
}

# =========================================================================
# PHASE: /data wipe
# =========================================================================
if (Should-Run 'wipe') {
    Phase "/data wipe ($WipeMB MB)"
    if (-not (Test-Loader)) {
        Warn 'Not in Loader; rebooting via adb.'
        $d = Find-Device
        if (-not $d) { Fail 'No device; reboot to Loader manually.'; exit 1 }
        & $ADB -s $d shell reboot loader 2>&1 | Out-Null
        if (-not (Wait-Loader 90)) { Fail 'Loader not caught.'; exit 1 }
    }
    & (Join-Path $Root 'scripts/wipe-data-head.ps1') -SizeMB $WipeMB -Reset
    Warn '/data wiped. WiFi credentials gone -- set up WiFi on touch UI when device boots.'
    Read-Host 'Press Enter when WiFi is reconnected on the tablet'
}

# =========================================================================
# PHASE: app install
# =========================================================================
if (Should-Run 'apps' -and -not $SkipFinalApps) {
    Phase 'Install user apps + assets'
    $d = Wait-Android 240
    if (-not $d) { Fail 'Device not reachable.'; exit 1 }
    Ok "Online: $d"
    foreach ($apk in @('apks/F-Droid.apk','apks/Lawnchair.apk','mabu-archive/unit-2022010501476/apks/com.catalia.factorymode.apk','mabu-archive/unit-2022010501476/apks/org.opencv.engine.apk')) {
        $p = Join-Path $Root $apk
        if (-not (Test-Path $p)) { Warn "Missing $apk -- skipping"; continue }
        $r = & $ADB -s $d install $p 2>&1 | Select-Object -Last 1
        Info "$apk : $r"
    }
    & $ADB -s $d shell 'cmd package set-home-activity app.lawnchair/.LawnchairLauncher' 2>&1 | Out-Null
    foreach ($p in 'CAMERA','RECORD_AUDIO','READ_PHONE_STATE','READ_EXTERNAL_STORAGE','WRITE_EXTERNAL_STORAGE') {
        & $ADB -s $d shell pm grant com.catalia.factorymode "android.permission.$p" 2>&1 | Out-Null
    }
    $SD = Join-Path $Root 'mabu-archive/unit-2022010501476/sdcard/sdcard'
    Get-ChildItem "$SD/*.csv" | ForEach-Object { & $ADB -s $d push $_.FullName /sdcard/ 2>&1 | Out-Null }
    & $ADB -s $d push "$SD/nuance" /sdcard/ 2>&1 | Out-Null
    & $ADB -s $d push "$SD/sound.raw" /sdcard/ 2>&1 | Out-Null
    Ok 'Apps + assets installed.'
}

Phase 'V3 done'
Ok 'Unit liberated and captured. Root not attempted by default on this hardware (Magisk incompatible).'
Info 'Next: motor calibration on touch UI, optionally restore from pre-wipe-archive.'
