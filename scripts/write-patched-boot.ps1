# write-patched-boot.ps1
# Writes the patched boot.img to the boot partition (sector 0x20000) via
# rkdeveloptool, then prompts for confirmation before sending a reset.
#
# Run after `patch-bootimg.py patch dumps\boot.img dumps\boot-patched.img`.
# Tablet must be in Rockchip Loader mode (VID 2207 PID 320A) with WinUSB
# bound (so rkdeveloptool's libusb backend can talk to it).

$ErrorActionPreference = 'Stop'
$RkExe   = 'C:\Users\User\Documents\GitHub\Mabu\tools\rkdeveloptool\rkdeveloptool.exe'
$Patched = 'C:\Users\User\Documents\GitHub\Mabu\dumps\boot-patched.img'

if (-not (Test-Path $RkExe))   { Write-Host "rkdeveloptool not found at $RkExe" -ForegroundColor Red; exit 1 }
if (-not (Test-Path $Patched)) { Write-Host "Patched image not found at $Patched" -ForegroundColor Red; exit 1 }

$size = (Get-Item $Patched).Length
Write-Host "=== Patched boot.img is $('{0:N0}' -f $size) bytes ===" -ForegroundColor Cyan

Write-Host ""
Write-Host "=== Confirming Loader is responsive ===" -ForegroundColor Cyan
$rfi = & $RkExe rfi 2>&1
$rfi | Select-Object -First 8
if (-not ($rfi -match 'Flash Info|Flash Size|Sectors')) {
    Write-Host "Loader not responsive - aborting." -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "=== Writing boot.img to sector 0x20000 ===" -ForegroundColor Cyan
$sw = [System.Diagnostics.Stopwatch]::StartNew()
$writeOut = & $RkExe wl 0x20000 $Patched 2>&1
$sw.Stop()
$writeOut | Out-String
Write-Host ("Write took {0:N1}s" -f $sw.Elapsed.TotalSeconds) -ForegroundColor Green

if ($writeOut -match 'fail|error') {
    Write-Host "WRITE LIKELY FAILED. DO NOT RESET. Inspect output above." -ForegroundColor Red
    Write-Host "Press Enter to close..." -ForegroundColor Yellow
    [void](Read-Host)
    exit 1
}

Write-Host ""
Write-Host "=== Verifying first sector after write ===" -ForegroundColor Cyan
$verifyFile = 'C:\Users\User\Documents\GitHub\Mabu\dumps\verify-readback.bin'
if (Test-Path $verifyFile) { Remove-Item $verifyFile }
& $RkExe rl 0x20000 4 $verifyFile 2>&1 | Out-Null
$verifyBytes = [System.IO.File]::ReadAllBytes($verifyFile)
$magic = [System.Text.Encoding]::ASCII.GetString($verifyBytes, 0, 8)
Write-Host "First 8 bytes of boot partition: '$magic'"
if ($magic -eq 'ANDROID!') {
    Write-Host "Boot header magic OK - write succeeded" -ForegroundColor Green
} else {
    Write-Host "Boot header magic WRONG - write may have failed" -ForegroundColor Red
}

Write-Host ""
Write-Host "=== Ready to reset tablet ===" -ForegroundColor Yellow
Write-Host "Tablet will reboot and try to use the patched boot.img." -ForegroundColor White
Write-Host "Press Enter to send reset (or Ctrl-C to abort)..." -ForegroundColor Yellow
[void](Read-Host)

Write-Host ""
Write-Host "=== Sending reset ===" -ForegroundColor Cyan
& $RkExe rd 2>&1

Write-Host ""
Write-Host "Reset sent. Watch the tablet screen - it should boot normally." -ForegroundColor Green
Write-Host "Within ~30 seconds, run 'adb devices' in another window to see if it shows up as 'device' (no auth)." -ForegroundColor White
Write-Host ""
Write-Host "Press Enter to close this window..." -ForegroundColor Yellow
[void](Read-Host)
