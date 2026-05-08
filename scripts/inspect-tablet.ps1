# inspect-tablet.ps1
#
# Runs once adb is up. Pulls a curated set of diagnostic information from
# the booted Android system over ADB and saves it to notes\tablet-info.md.
# This is the read-only fact-finding pass - no shell modifications, no
# partition dumps, just `getprop`, `/proc`, and `/sys` reads.
#
# Useful for figuring out:
#   - exact SoC (ro.board.platform, /proc/cpuinfo)
#   - Android version, build fingerprint, security patch level
#   - device serial number, model name as the OEM set it
#   - which partitions exist and how they're mounted
#   - what packages are installed (curious about Mabu's app stack)
#
# Usage:
#   .\inspect-tablet.ps1            # write to notes\tablet-info.md
#   .\inspect-tablet.ps1 -ToConsole # also dump everything to stdout

[CmdletBinding()]
param(
    [switch]$ToConsole
)

$ErrorActionPreference = 'Stop'

$RepoRoot = Split-Path -Parent $PSScriptRoot
$NotesDir = Join-Path $RepoRoot 'notes'
$Out      = Join-Path $NotesDir 'tablet-info.md'

# Locate adb.
$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) {
    $adb = (Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\Google.PlatformTools_*\platform-tools\adb.exe" -ErrorAction SilentlyContinue | Select-Object -First 1).FullName
}
if (-not $adb) {
    Write-Host 'adb not found. Run scripts\install-android-driver.ps1 setup first, or install platform-tools.' -ForegroundColor Red
    exit 1
}

# Make sure a device is actually attached.
$devLines = & $adb devices 2>&1
$serial = ($devLines | Select-String -Pattern '^\S+\s+device$' | Select-Object -First 1).Matches.Value -split '\s+' | Select-Object -First 1
if (-not $serial) {
    Write-Host 'adb sees no device in "device" state.' -ForegroundColor Red
    Write-Host '  - Plug in the tablet'
    Write-Host '  - Make sure the patched Google USB driver is installed (scripts\install-android-driver.ps1)'
    Write-Host '  - If state is "unauthorized", accept the RSA key prompt on the tablet itself'
    & $adb devices -l
    exit 1
}
Write-Host "Found device: $serial" -ForegroundColor Green

if (-not (Test-Path $NotesDir)) { New-Item -ItemType Directory -Path $NotesDir -Force | Out-Null }

function Run-Adb {
    param([string]$Title, [string]$Cmd)
    Write-Host ("  [info] {0}" -f $Title) -ForegroundColor DarkGray
    $output = & $adb -s $serial shell $Cmd 2>&1 | Out-String
    return @"
## $Title

``````
$($output.TrimEnd())
``````

"@
}

# Curated set of read-only commands. Order is rough: identity -> hardware -> partitions -> apps.
$sections = @(
    @{ T='adb devices -l';       C=$null },
    @{ T='getprop (all properties)';     C='getprop' },
    @{ T='ro.board.platform';            C='getprop ro.board.platform' },
    @{ T='ro.hardware';                  C='getprop ro.hardware' },
    @{ T='ro.product.model / brand / device'; C='getprop ro.product.model; getprop ro.product.brand; getprop ro.product.device; getprop ro.product.manufacturer' },
    @{ T='ro.build.fingerprint';         C='getprop ro.build.fingerprint' },
    @{ T='ro.build.version.release';     C='getprop ro.build.version.release; getprop ro.build.version.sdk' },
    @{ T='ro.serialno';                  C='getprop ro.serialno' },
    @{ T='ro.adb.secure / ro.secure / ro.debuggable'; C='getprop ro.adb.secure; getprop ro.secure; getprop ro.debuggable' },
    @{ T='id (running as)';              C='id' },
    @{ T='uname -a';                     C='uname -a' },
    @{ T='/proc/cpuinfo';                C='cat /proc/cpuinfo' },
    @{ T='/proc/version';                C='cat /proc/version' },
    @{ T='/proc/meminfo (head)';         C='head -n 5 /proc/meminfo' },
    @{ T='/proc/cmdline (kernel boot args)'; C='cat /proc/cmdline' },
    @{ T='df -h';                        C='df -h' },
    @{ T='mount';                        C='mount' },
    @{ T='/dev/block/by-name (partition table mapping)'; C='ls -la /dev/block/by-name 2>/dev/null || ls -la /dev/block/platform/*/by-name 2>/dev/null' },
    @{ T='/sys/class/block summary';     C='for d in /sys/class/block/*; do echo $d $(cat $d/size 2>/dev/null) $(cat $d/device/type 2>/dev/null); done' },
    @{ T='top-level /system listing';    C='ls -la /system' },
    @{ T='/system/etc/permissions (OEM features)'; C='ls /system/etc/permissions' },
    @{ T='installed packages';           C='pm list packages -f' },
    @{ T='installed third-party packages only'; C='pm list packages -f -3' },
    @{ T='dumpsys battery';              C='dumpsys battery' }
)

# Build the report.
$report = @()
$report += "# Mabu tablet info"
$report += ""
$report += "Captured: $(Get-Date -Format o)"
$report += "Device:   $serial"
$report += ""

foreach ($s in $sections) {
    if ($null -eq $s.C) {
        # Special-case the very first line, which is the host-side adb call.
        $output = & $adb devices -l 2>&1 | Out-String
        $report += "## adb devices -l"
        $report += ''
        $report += '```'
        $report += $output.TrimEnd()
        $report += '```'
        $report += ''
    } else {
        $report += Run-Adb -Title $s.T -Cmd $s.C
    }
}

$text = $report -join "`n"
[System.IO.File]::WriteAllText($Out, $text, [System.Text.UTF8Encoding]::new($false))
Write-Host ''
Write-Host "Wrote $Out" -ForegroundColor Green

if ($ToConsole) { Write-Host ''; $text }

# Headline call-out: SoC family and Android version.
$plat = & $adb -s $serial shell getprop ro.board.platform 2>$null
$rel  = & $adb -s $serial shell getprop ro.build.version.release 2>$null
$model = & $adb -s $serial shell getprop ro.product.model 2>$null
Write-Host ''
Write-Host '=== Headline ===' -ForegroundColor Cyan
Write-Host ("  SoC platform:    {0}" -f $plat.Trim())
Write-Host ("  Android version: {0}" -f $rel.Trim())
Write-Host ("  Product model:   {0}" -f $model.Trim())
