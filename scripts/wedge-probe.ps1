param(
    [int] $ChunkSectors = 8192,
    [int] $MaxChunks = 50,
    [int] $InterChunkMs = 0,
    [int] $StartLBA = 0x18A000
)
$ErrorActionPreference = 'Stop'
$RK = 'tools/rkdeveloptool/rkdeveloptool.exe'

# Sanity
$ld = & $RK ld 2>&1
if ($ld -notmatch 'Loader') { Write-Host "No loader: $ld" -ForegroundColor Red; exit 1 }
Write-Host "$ld"
Write-Host "ChunkSectors=$ChunkSectors (=$([math]::Round($ChunkSectors/2048,1)) MB)  InterChunkMs=$InterChunkMs  MaxChunks=$MaxChunks"

$tmp = "$env:TEMP\rk-probe.bin"
$totalBytes = 0
$totalSecsRead = 0
for ($i = 1; $i -le $MaxChunks; $i++) {
    if (Test-Path $tmp) { Remove-Item $tmp -Force }
    $sec = $StartLBA + ($i - 1) * $ChunkSectors
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $p = Start-Process -FilePath $RK -ArgumentList @('rl', $sec, $ChunkSectors, $tmp) -NoNewWindow -PassThru -RedirectStandardOutput "$tmp.out" -RedirectStandardError "$tmp.err"
    $ok = $p.WaitForExit(15000)
    $sw.Stop()
    $rc = if ($ok) { $p.ExitCode } else { -999 }
    $sz = if (Test-Path $tmp) { (Get-Item $tmp).Length } else { 0 }
    $expected = $ChunkSectors * 512
    $err = ''
    if (Test-Path "$tmp.err") { $e = Get-Content "$tmp.err" -Raw; if ($e) { $err = $e.Trim() } }
    $out = ''
    if (Test-Path "$tmp.out") { $o = Get-Content "$tmp.out" -Raw; if ($o) { $out = $o.Trim() } }
    $tag = if ($ok -and $rc -eq 0 -and $sz -eq $expected) { 'OK' } else { 'FAIL' }
    Write-Host ('{0,2}  sec=0x{1:X}  {2,-4}  rc={3,4}  dt={4,5:N2}s  size={5}  cum={6:N0}B  {7}' -f $i, $sec, $tag, $rc, $sw.Elapsed.TotalSeconds, $sz, $totalBytes, ($err + $out).Substring(0, [Math]::Min(60, ($err+$out).Length)))
    if ($tag -ne 'OK') {
        if (-not $ok) {
            try { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue } catch {}
            Get-Process rkdeveloptool -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
        }
        break
    }
    $totalBytes += $sz
    $totalSecsRead += $ChunkSectors
    if ($InterChunkMs -gt 0) { Start-Sleep -Milliseconds $InterChunkMs }
}
Remove-Item $tmp,"$tmp.out","$tmp.err" -ErrorAction SilentlyContinue
Write-Host "Done. Cumulative read: $totalBytes B = $([math]::Round($totalBytes/1MB,1)) MB"
