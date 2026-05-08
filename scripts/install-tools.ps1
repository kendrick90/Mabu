# install-tools.ps1
#
# Bootstraps the Windows tooling needed to talk to the Mabu's Rockchip
# rockusb gadget (VID 0x2207 / PID 0x0006).
#
# What this does:
#   1. Installs Zadig (used to replace the default Windows driver on the
#      rockusb device with WinUSB, so libusb-based tools can open it).
#   2. Sets up tools\rkdeveloptool\ as a drop-in location for the
#      rkdeveloptool CLI binary (manual download — see notes below).
#   3. Verifies whatever is in place.
#
# What this does NOT do:
#   - Auto-download rkdeveloptool. There is no canonical pre-built Windows
#     binary URL I trust to hardcode (upstream is Linux-source-only;
#     Windows builds come from various forks of varying quality).
#     The script tells you where to drop the binary and verifies it.
#   - Auto-bind WinUSB. Zadig is GUI-driven by design (driver replacement
#     is a security-sensitive operation). The script launches it and tells
#     you exactly which device to pick.
#
# Idempotent: re-running is safe and just verifies state.

$ErrorActionPreference = 'Stop'

$RepoRoot   = Split-Path -Parent $PSScriptRoot
$ToolsDir   = Join-Path $RepoRoot 'tools'
$RkDir      = Join-Path $ToolsDir 'rkdeveloptool'
$RkExe      = Join-Path $RkDir   'rkdeveloptool.exe'

function Write-Step($msg)  { Write-Host "`n=== $msg ===" -ForegroundColor Cyan }
function Write-OK($msg)    { Write-Host "  [OK]   $msg"  -ForegroundColor Green }
function Write-Note($msg)  { Write-Host "  [note] $msg"  -ForegroundColor Yellow }
function Write-Warn($msg)  { Write-Host "  [warn] $msg"  -ForegroundColor DarkYellow }

# ---------------------------------------------------------------------------
# 1. Zadig
# ---------------------------------------------------------------------------
Write-Step 'Zadig'

function Get-ZadigPath {
    # Try common install locations from the package managers below first.
    $candidates = @(
        "$env:USERPROFILE\scoop\apps\zadig\current\zadig.exe",
        "$env:USERPROFILE\scoop\shims\zadig.exe",
        "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\akeo.ie.Zadig*\zadig*.exe",
        "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\Akeo.Zadig*\zadig*.exe",
        "$env:ProgramFiles\Zadig\zadig.exe",
        "${env:ProgramFiles(x86)}\Zadig\zadig.exe"
    )
    foreach ($pat in $candidates) {
        $hit = Get-ChildItem -Path $pat -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($hit) { return $hit.FullName }
    }
    $cmd = Get-Command zadig -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    return $null
}

$zadig = Get-ZadigPath
if ($zadig) {
    Write-OK "Zadig already installed: $zadig"
} else {
    $installed = $false

    # Prefer winget if available.
    if (Get-Command winget -ErrorAction SilentlyContinue) {
        Write-Note 'Installing Zadig via winget...'
        $p = Start-Process winget `
            -ArgumentList 'install','--id','akeo.ie.Zadig','-e','--accept-source-agreements','--accept-package-agreements' `
            -NoNewWindow -Wait -PassThru
        if ($p.ExitCode -eq 0) {
            $installed = $true
        } else {
            Write-Warn "winget exit code $($p.ExitCode); will try fallback."
        }
    }

    # Fallback: scoop.
    if (-not $installed -and (Get-Command scoop -ErrorAction SilentlyContinue)) {
        Write-Note 'Installing Zadig via scoop (extras bucket)...'
        try {
            scoop bucket add extras 2>$null | Out-Null
            scoop install zadig
            $installed = $true
        } catch {
            Write-Warn "scoop install failed: $_"
        }
    }

    if ($installed) {
        $zadig = Get-ZadigPath
        if ($zadig) { Write-OK "Zadig installed: $zadig" }
    } else {
        Write-Warn 'Could not auto-install Zadig.'
        Write-Note 'Manual install: download from https://zadig.akeo.ie/ (single .exe, no installer needed).'
        Write-Note "Place at: $RkDir\..\zadig.exe  -or-  anywhere on PATH."
    }
}

# ---------------------------------------------------------------------------
# 2. rkdeveloptool drop-in directory
# ---------------------------------------------------------------------------
Write-Step 'rkdeveloptool (manual placement)'

if (-not (Test-Path $RkDir)) { New-Item -ItemType Directory -Path $RkDir -Force | Out-Null }

$readmePath = Join-Path $RkDir 'README.md'
if (-not (Test-Path $readmePath)) {
    @'
# rkdeveloptool — drop the Windows binary here

Place `rkdeveloptool.exe` (and any DLLs it links against — typically
`libusb-1.0.dll` / `libwinpthread-1.dll`) directly in this directory.

## Where to obtain a Windows build

There is no Rockchip-blessed canonical Windows build. Reasonable options,
in rough order of trustworthiness:

1. **Build from source yourself** under MSYS2 / mingw-w64 from upstream:
     https://github.com/rockchip-linux/rkdeveloptool
   This is the cleanest option if you have a build environment.

2. **A community fork that publishes signed releases.** Several forks on
   GitHub publish Windows binaries; verify the source repo is reputable
   and prefer ones with reproducible builds and a release SHA you can pin.

3. **Use the GUI alternative** — Rockchip's official `RKDevTool` (a.k.a.
   "AndroidTool") for Windows. It bundles all dependencies. Good for
   one-shot dumping; less scriptable than the CLI.

After dropping the binary, run `scripts\install-tools.ps1` again to verify.

## Notes

- This directory is in `.gitignore` (the binary isn't committed).
- `rkdeveloptool` requires WinUSB to be bound to the Rockchip device via
  Zadig before it can open the device. See `scripts\bind-winusb.ps1`
  (or run Zadig manually).
'@ | Set-Content -Path $readmePath -Encoding UTF8
    Write-OK "Wrote $readmePath"
}

if (Test-Path $RkExe) {
    Write-OK "Found $RkExe"
    try {
        $ver = & $RkExe -v 2>&1 | Select-Object -First 1
        Write-OK "Reports: $ver"
    } catch {
        Write-Warn "rkdeveloptool.exe present but failed to run: $_"
    }
} else {
    Write-Note "rkdeveloptool.exe not yet placed. See $readmePath for instructions."
    Write-Note "Expected location: $RkExe"
}

# ---------------------------------------------------------------------------
# 3. Device check
# ---------------------------------------------------------------------------
Write-Step 'Device state'

$rk = Get-PnpDevice -PresentOnly | Where-Object { $_.InstanceId -match 'VID_2207' }
if (-not $rk) {
    Write-Warn 'No Rockchip device (VID 0x2207) currently enumerated.'
    Write-Note 'Plug the Mabu USB header in and re-run.'
} else {
    foreach ($d in $rk) {
        Write-OK "$($d.FriendlyName)  -  $($d.InstanceId)  -  Status: $($d.Status)  -  Class: $($d.Class)"

        # If it's still bound to a Microsoft USB driver (Class=USBDevice/USB), Zadig hasn't run yet.
        if ($d.Class -in @('USB','USBDevice','Unknown') -or -not $d.Class) {
            Write-Note 'Driver looks like the default Windows USB stack — libusb-based tools will fail to open the device.'
            Write-Note 'Run Zadig and replace the driver for this device with WinUSB. See scripts\bind-winusb.ps1.'
        } elseif ($d.Class -eq 'libusb-win32 devices' -or $d.Class -match 'WinUSB') {
            Write-OK 'Driver looks like a libusb/WinUSB binding — rkdeveloptool should be able to open it.'
        } else {
            Write-Note "Driver class is '$($d.Class)' — not sure if libusb can open this; try rkdeveloptool ld and see."
        }
    }
}

Write-Step 'Next step'
if (-not $zadig) {
    Write-Host '  Install Zadig (see notes above).' -ForegroundColor White
} elseif (-not (Test-Path $RkExe)) {
    Write-Host '  1. Run scripts\bind-winusb.ps1 to bind WinUSB to the rockusb device.' -ForegroundColor White
    Write-Host '  2. Drop rkdeveloptool.exe into tools\rkdeveloptool\ (see its README).' -ForegroundColor White
    Write-Host '  3. Re-run this script to verify.' -ForegroundColor White
} else {
    Write-Host '  Try:  tools\rkdeveloptool\rkdeveloptool.exe ld' -ForegroundColor White
    Write-Host '  Then: tools\rkdeveloptool\rkdeveloptool.exe rci    (read chip info)' -ForegroundColor White
    Write-Host '  Then: tools\rkdeveloptool\rkdeveloptool.exe ppt    (print partition table)' -ForegroundColor White
}
