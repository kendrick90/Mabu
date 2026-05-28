# dump-range.ps1
#
# Dumps a contiguous range of LBA sectors from an already-locked Rockchip
# Loader (PID 320A). Designed for surgical reads under the Loader's per-
# session wedge limit (~30 MB observed). Writes output to firmware/scratch/<name>.img.
#
# Usage:
#   .\dump-range.ps1 -Name etc-region `
#                    -PartitionStartLBA 0x18A000 `
#                    -StartByte 230MB `
#                    -LengthBytes 14MB
#
# StartByte and LengthBytes can be plain integers or use PowerShell unit
# suffixes (MB, KB). PartitionStartLBA can be hex (0x18A000) or decimal.
#
# Output file is opened in append mode if it exists; otherwise created.

[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $Name,
    [Parameter(Mandatory)] [int]    $PartitionStartLBA,
    [Parameter(Mandatory)] [int64]  $StartByte,
    [Parameter(Mandatory)] [int64]  $LengthBytes,
    [int] $ChunkSectors = 8192,    # 4 MB per chunk
    [int] $ChunkTimeoutSec = 45,   # per-chunk wedge threshold
    [switch] $AppendOk
)

$ErrorActionPreference = 'Stop'
$Rk = 'C:\Users\User\Documents\GitHub\Mabu\tools\rkdeveloptool\rkdeveloptool.exe'
$DumpDir = 'C:\Users\User\Documents\GitHub\Mabu\firmware\scratch'
if (-not (Test-Path $DumpDir)) { New-Item -ItemType Directory -Path $DumpDir -Force | Out-Null }

if (($StartByte % 512) -ne 0) { throw "StartByte must be sector-aligned (multiple of 512)" }
if (($LengthBytes % 512) -ne 0) { throw "LengthBytes must be sector-aligned (multiple of 512)" }

$startSecInPart = [int64]($StartByte / 512)
$totalSec       = [int64]($LengthBytes / 512)
$absStartLBA    = [int64]$PartitionStartLBA + $startSecInPart

$out = Join-Path $DumpDir "$Name.img"
$expected = $LengthBytes
Write-Host ("[range] {0,-16}  abs_start=0x{1:X}  sectors={2}  bytes={3:N0} ({4:N2} MB)" -f $Name, $absStartLBA, $totalSec, $expected, ($expected/1MB)) -ForegroundColor Cyan
Write-Host ("        partition_start=0x{0:X}  offset_in_part=0x{1:X} ({2:N0} bytes)" -f $PartitionStartLBA, $startSecInPart, $StartByte) -ForegroundColor DarkGray

# Quick liveness probe so we fail fast if loader is wedged
$out0 = & $Rk ld 2>&1
if ($out0 -notmatch 'Vid=0x2207,Pid=0x320a.*Loader') {
    Write-Host "Loader not present. Aborting." -ForegroundColor Red
    exit 1
}

if ((Test-Path $out) -and (-not $AppendOk)) {
    Write-Host "Output file exists. Pass -AppendOk to append, or rename/remove." -ForegroundColor Yellow
    exit 1
}

$mode = if (Test-Path $out) { [System.IO.FileMode]::Append } else { [System.IO.FileMode]::Create }
$stream = New-Object System.IO.FileStream($out, $mode)
$tmpChunk = "$env:TEMP\rk-chunk-$([guid]::NewGuid().ToString('N')).bin"

$sw = [System.Diagnostics.Stopwatch]::StartNew()
$sec = $absStartLBA
$remaining = $totalSec
$failed = $false
try {
    while ($remaining -gt 0) {
        $chunk = [Math]::Min($remaining, $ChunkSectors)
        # Run rkdeveloptool with a hard timeout — kill if it wedges.
        $proc = Start-Process -FilePath $Rk -ArgumentList @('rl', $sec, $chunk, $tmpChunk) `
                              -NoNewWindow -PassThru -RedirectStandardOutput "$tmpChunk.out" -RedirectStandardError "$tmpChunk.err"
        $finished = $proc.WaitForExit($ChunkTimeoutSec * 1000)
        if (-not $finished) {
            Write-Host ("  WEDGED at abs LBA 0x{0:X} (sector {0:N0}) after {1}s -- killing rkdeveloptool" -f $sec, $ChunkTimeoutSec) -ForegroundColor Red
            try { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue } catch {}
            Get-Process rkdeveloptool -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
            $failed = $true
            break
        }
        if ($proc.ExitCode -ne 0 -or -not (Test-Path $tmpChunk) -or ((Get-Item $tmpChunk).Length -eq 0)) {
            $errOut = if (Test-Path "$tmpChunk.err") { Get-Content "$tmpChunk.err" -Raw } else { '' }
            Write-Host ("  FAILED at abs LBA 0x{0:X} exit={1}: {2}" -f $sec, $proc.ExitCode, $errOut) -ForegroundColor Red
            $failed = $true
            break
        }
        Remove-Item "$tmpChunk.out","$tmpChunk.err" -ErrorAction SilentlyContinue
        $bytes = [System.IO.File]::ReadAllBytes($tmpChunk)
        $stream.Write($bytes, 0, $bytes.Length)
        $sec       += $chunk
        $remaining -= $chunk
        Remove-Item $tmpChunk -ErrorAction SilentlyContinue
        $done = $totalSec - $remaining
        $pct = [math]::Round($done * 100.0 / $totalSec, 1)
        $elapsed = $sw.Elapsed.TotalSeconds
        $bytesDone = $done * 512
        $mbs = ($bytesDone / 1MB) / [Math]::Max($elapsed, 0.001)
        Write-Host ("       {0,5:N1}%  {1,12:N0} bytes  {2,6:N1}s  {3,5:N2} MB/s" -f $pct, $bytesDone, $elapsed, $mbs) -ForegroundColor DarkGray
    }
    $sw.Stop()
} finally {
    $stream.Close()
    Remove-Item $tmpChunk,"$tmpChunk.out","$tmpChunk.err" -ErrorAction SilentlyContinue
}

if (-not $failed) {
    Write-Host ("       done {0:N1}s  total={1:N0} bytes  -> {2}" -f $sw.Elapsed.TotalSeconds, (Get-Item $out).Length, $out) -ForegroundColor Green
} else {
    Write-Host ("       partial dump written: {0:N0} bytes -> {1}" -f (Get-Item $out).Length, $out) -ForegroundColor Yellow
}
