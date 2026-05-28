# dump-partitions.ps1
#
# Dumps partitions from the Mabu's eMMC via rkdeveloptool, in safe order:
#   1. Reads chip info / flash info (instant) -> firmware\scratch\device-info.txt
#   2. Reads partition table                  -> firmware\scratch\partition-table.txt
#   3. Dumps each partition smallest-first    -> firmware\scratch\<name>.img
#   4. Computes SHA-256 for each dump         -> firmware\scratch\sha256sums.txt
#
# Defaults are intentionally conservative:
#   - userdata, cache, metadata, frp are SKIPPED (huge, PII-bearing,
#     not interesting for firmware analysis). Add -IncludeUserdata to
#     dump userdata.
#   - Existing dumps with matching expected size are SKIPPED (resumable).
#     Delete the file or pass a different -Only/-Skip to force re-dump.
#
# Usage:
#   .\dump-partitions.ps1                   # dump default partition set
#   .\dump-partitions.ps1 -DryRun           # print plan, don't read anything
#   .\dump-partitions.ps1 -Only boot,uboot  # dump only specific partitions
#   .\dump-partitions.ps1 -IncludeUserdata  # also dump userdata (slow, large)
#   .\dump-partitions.ps1 -NoHash           # skip SHA-256 computation
#   .\dump-partitions.ps1 -Force            # don't prompt for confirmation
#
# This script is read-only with respect to the device. It never writes,
# erases, or reboots. The only side effect is creating files under firmware\scratch\.

[CmdletBinding()]
param(
    [switch]$DryRun,
    [switch]$IncludeUserdata,
    [string[]]$Only = @(),
    [string[]]$Skip = @(),
    [switch]$NoHash,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'

$RepoRoot = Split-Path -Parent $PSScriptRoot
$RkExe    = Join-Path $RepoRoot 'tools\rkdeveloptool\rkdeveloptool.exe'
$DumpDir  = Join-Path $RepoRoot 'firmware\scratch'

# Always-skip list - append to whatever the user passes via -Skip.
$DefaultSkip = @('userdata','cache','metadata','frp','persist','misc','baseparameter')
if (-not $IncludeUserdata) { $Skip = @($Skip) + $DefaultSkip | Sort-Object -Unique }

# ---------------------------------------------------------------------------
# Pre-flight
# ---------------------------------------------------------------------------
if (-not (Test-Path $RkExe)) {
    Write-Host "rkdeveloptool not found at: $RkExe" -ForegroundColor Red
    Write-Host 'Run scripts\install-tools.ps1 and follow its instructions to place the binary.' -ForegroundColor Yellow
    exit 1
}

if (-not (Test-Path $DumpDir)) { New-Item -ItemType Directory -Path $DumpDir -Force | Out-Null }

function Invoke-Rk {
    param([Parameter(ValueFromRemainingArguments=$true)][string[]]$Args)
    & $RkExe @Args 2>&1
}

# Verify a device is actually visible to rkdeveloptool.
$ldOut = Invoke-Rk ld
if ($ldOut -notmatch 'Loader|Maskrom') {
    Write-Host 'rkdeveloptool ld did not list a Loader/Maskrom device.' -ForegroundColor Red
    Write-Host 'Output was:' -ForegroundColor Yellow
    $ldOut | Write-Host
    Write-Host ''
    Write-Host 'Likely causes:' -ForegroundColor Yellow
    Write-Host '  - Device not connected (re-plug USB header)'
    Write-Host '  - WinUSB not yet bound to VID 2207 (run scripts\bind-winusb.ps1 + Zadig)'
    Write-Host '  - rkdeveloptool.exe placed but missing libusb-1.0.dll'
    exit 1
}
Write-Host "rkdeveloptool sees: $($ldOut -join '; ')" -ForegroundColor Green

# ---------------------------------------------------------------------------
# Device info (saved for posterity)
# ---------------------------------------------------------------------------
$infoFile = Join-Path $DumpDir 'device-info.txt'
$info = @()
$info += "# Captured $(Get-Date -Format o)"
$info += ''
$info += '## ld'
$info += (Invoke-Rk ld) -join "`n"
$info += ''
$info += '## rci (read chip info)'
$info += (Invoke-Rk rci) -join "`n"
$info += ''
$info += '## rid (read flash ID)'
$info += (Invoke-Rk rid) -join "`n"
$info += ''
$info += '## rfi (read flash info)'
$info += (Invoke-Rk rfi) -join "`n"
$info += ''
$info += '## rcb (read capability)'
$info += (Invoke-Rk rcb) -join "`n"
$info -join "`n" | Set-Content -Path $infoFile -Encoding UTF8
Write-Host "Wrote $infoFile" -ForegroundColor Green

# ---------------------------------------------------------------------------
# Partition table
# ---------------------------------------------------------------------------
$pptFile = Join-Path $DumpDir 'partition-table.txt'
$pptOut  = Invoke-Rk ppt
$pptOut -join "`n" | Set-Content -Path $pptFile -Encoding UTF8
Write-Host "Wrote $pptFile" -ForegroundColor Green

# Parse the partition table.
# Expected line format (from upstream rkdeveloptool):
#   NN  0xSTART     0xSIZE      name
# We accept slack on whitespace and on the leading index column.
$partitions = @()
foreach ($line in $pptOut) {
    if ($line -match '^\s*\d+\s+0x([0-9a-fA-F]+)\s+0x([0-9a-fA-F]+)\s+(\S+)') {
        $partitions += [PSCustomObject]@{
            Name  = $Matches[3]
            Start = [Convert]::ToInt64($Matches[1], 16)   # in 512-byte sectors
            Size  = [Convert]::ToInt64($Matches[2], 16)   # in 512-byte sectors
        }
    }
}

if (-not $partitions) {
    Write-Host 'Could not parse any partitions from rkdeveloptool ppt output.' -ForegroundColor Red
    Write-Host "Inspect $pptFile and adjust the parser regex if the format is unusual." -ForegroundColor Yellow
    exit 1
}

# Handle the "rest of disk" sentinel that ppt sometimes uses for the last
# partition (typically userdata): 0xFFFFFFFF -> compute remaining sectors.
$flashTotalSectors = $null
foreach ($line in (Invoke-Rk rfi)) {
    if ($line -match 'Total\s*Size:\s*(\d+)') { $flashTotalSectors = [int64]$Matches[1] }
    elseif ($line -match 'FLASH\s*SIZE:\s*(\d+)\s*(KB|MB|GB)') {
        $n = [int64]$Matches[1]
        switch ($Matches[2]) {
            'KB' { $flashTotalSectors = $n * 2 }
            'MB' { $flashTotalSectors = $n * 2048 }
            'GB' { $flashTotalSectors = $n * 2097152 }
        }
    }
}
foreach ($p in $partitions) {
    if ($p.Size -eq 0xFFFFFFFF -or $p.Size -eq -1) {
        if ($flashTotalSectors) {
            $p.Size = $flashTotalSectors - $p.Start
        } else {
            Write-Host "Partition '$($p.Name)' uses sentinel size and we couldn't infer total flash size." -ForegroundColor Yellow
            Write-Host '  Skipping it; pass -Only to dump it explicitly with a manual size.' -ForegroundColor Yellow
            $p.Size = 0
        }
    }
}

# Filter
$todo = $partitions | Where-Object {
    if ($Only.Count -gt 0) { return $Only -contains $_.Name }
    if ($Skip -contains $_.Name) { return $false }
    if ($_.Size -le 0) { return $false }
    return $true
}

# Order smallest-first.
$todo = $todo | Sort-Object Size

# ---------------------------------------------------------------------------
# Plan
# ---------------------------------------------------------------------------
function Format-Bytes([int64]$n) {
    if ($n -ge 1GB) { return ('{0:N2} GB' -f ($n / 1GB)) }
    if ($n -ge 1MB) { return ('{0:N2} MB' -f ($n / 1MB)) }
    if ($n -ge 1KB) { return ('{0:N2} KB' -f ($n / 1KB)) }
    return "$n B"
}

Write-Host ''
Write-Host '=== Dump plan ===' -ForegroundColor Cyan
$plan = $todo | Select-Object Name,
    @{N='Sectors';   E={ $_.Size }},
    @{N='Size';      E={ Format-Bytes ($_.Size * 512) }},
    @{N='Start LBA'; E={ '0x{0:X}' -f $_.Start }}
$plan | Format-Table -AutoSize

$totalBytes = ($todo | Measure-Object -Property Size -Sum).Sum * 512
Write-Host ("Total to dump: {0} across {1} partition(s)" -f (Format-Bytes $totalBytes), $todo.Count) -ForegroundColor Cyan
Write-Host ("Skipping:      {0}" -f ($Skip -join ', ')) -ForegroundColor DarkGray

if ($DryRun) {
    Write-Host ''
    Write-Host 'Dry run - exiting without reading.' -ForegroundColor Yellow
    exit 0
}

if (-not $Force) {
    Write-Host ''
    $reply = Read-Host 'Proceed? [y/N]'
    if ($reply -notmatch '^[yY]') { Write-Host 'Aborted.'; exit 0 }
}

# ---------------------------------------------------------------------------
# Dump
# ---------------------------------------------------------------------------
$sumsFile = Join-Path $DumpDir 'sha256sums.txt'
$results  = @()

foreach ($p in $todo) {
    $outFile = Join-Path $DumpDir ($p.Name + '.img')
    $expectedBytes = $p.Size * 512

    if ((Test-Path $outFile) -and ((Get-Item $outFile).Length -eq $expectedBytes)) {
        Write-Host ("[skip] {0} already dumped at expected size ({1})" -f $p.Name, (Format-Bytes $expectedBytes)) -ForegroundColor DarkGray
        $results += [PSCustomObject]@{ Name=$p.Name; Status='skip-existing'; Path=$outFile }
        continue
    }

    Write-Host ''
    Write-Host ("[dump] {0}  start=0x{1:X}  sectors={2}  size={3}" -f $p.Name, $p.Start, $p.Size, (Format-Bytes $expectedBytes)) -ForegroundColor Cyan

    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $rkOut = Invoke-Rk rl $p.Start $p.Size $outFile
    $sw.Stop()

    if (-not (Test-Path $outFile) -or (Get-Item $outFile).Length -eq 0) {
        Write-Host ("  FAILED. rkdeveloptool said: " + ($rkOut -join ' | ')) -ForegroundColor Red
        $results += [PSCustomObject]@{ Name=$p.Name; Status='failed'; Path=$outFile }
        continue
    }

    $actualBytes = (Get-Item $outFile).Length
    $mbPerSec = if ($sw.Elapsed.TotalSeconds -gt 0) { ($actualBytes / 1MB) / $sw.Elapsed.TotalSeconds } else { 0 }
    Write-Host ("  done in {0:N1}s ({1:N2} MB/s)" -f $sw.Elapsed.TotalSeconds, $mbPerSec) -ForegroundColor Green

    if (-not $NoHash) {
        $hash = (Get-FileHash -Algorithm SHA256 -Path $outFile).Hash.ToLower()
        ("{0}  {1}" -f $hash, ($p.Name + '.img')) | Add-Content -Path $sumsFile -Encoding UTF8
        Write-Host ("  sha256 {0}" -f $hash) -ForegroundColor DarkGray
    }

    $results += [PSCustomObject]@{ Name=$p.Name; Status='ok'; Path=$outFile; Bytes=$actualBytes }
}

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
Write-Host ''
Write-Host '=== Summary ===' -ForegroundColor Cyan
$results | Format-Table Name, Status, @{N='Size'; E={ if ($_.Bytes) { Format-Bytes $_.Bytes } else { '-' } }}, Path -AutoSize

$failed = ($results | Where-Object Status -eq 'failed').Count
if ($failed -gt 0) {
    Write-Host "$failed partition(s) failed. See output above." -ForegroundColor Red
    exit 2
}

Write-Host 'Done. Useful follow-ups:' -ForegroundColor Green
Write-Host '  - binwalk firmware\scratch\boot.img             (kernel + ramdisk inside)'
Write-Host '  - strings firmware\scratch\uboot.img | less     (u-boot version, build date, env)'
Write-Host '  - simg2img firmware\scratch\system.img system.raw.img  (if it is a sparse image)'
Write-Host '  - file firmware\scratch\*.img                   (identify what each one is)'
