# reset-driver.ps1
#
# Removes the Zadig-installed WinUSB binding (and any other stray
# third-party driver) for the Mabu device, putting it back to a
# clean "no driver claimed" state. After this, install-android-driver
# can install the Google ADB driver cleanly.
#
# Why this is needed: Windows' "Update Driver" flow often says
# "the best drivers are already installed" if a non-default driver
# (like Zadig's WinUSB) is bound. Forcibly removing the OEM INF
# from the driver store breaks that loop.
#
# Requires admin (pnputil /delete-driver and Disable/Enable-PnpDevice).
# Self-elevates if not already.

$ErrorActionPreference = 'Stop'

$TargetVid = '2207'
$TargetPid = '0006'

# Self-elevate if not admin.
$isAdmin = ([Security.Principal.WindowsPrincipal] [Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Write-Host 'Re-launching as Administrator...' -ForegroundColor Yellow
    $args = "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`""
    Start-Process -FilePath 'powershell.exe' -ArgumentList $args -Verb RunAs
    exit
}

Write-Host '=== Locating device ===' -ForegroundColor Cyan
$dev = Get-PnpDevice -PresentOnly | Where-Object { $_.InstanceId -match "VID_${TargetVid}&PID_${TargetPid}" }
if ($dev) {
    Write-Host "  Device: $($dev.FriendlyName)  $($dev.InstanceId)" -ForegroundColor Green
    $currentInf  = (Get-PnpDeviceProperty -InstanceId $dev.InstanceId -KeyName 'DEVPKEY_Device_DriverInfPath' -ErrorAction SilentlyContinue).Data
    $currentSvc  = (Get-PnpDeviceProperty -InstanceId $dev.InstanceId -KeyName 'DEVPKEY_Device_Service'      -ErrorAction SilentlyContinue).Data
    Write-Host "  Currently bound to:  service=$currentSvc  inf=$currentInf"
} else {
    Write-Host '  Device not currently enumerated. (We can still purge any stale OEM INFs.)' -ForegroundColor Yellow
}

Write-Host ''
Write-Host '=== Listing third-party INFs that match VID_2207 ===' -ForegroundColor Cyan
$pnputilOut = & pnputil /enum-drivers 2>&1
# pnputil output is multi-line per driver: Published Name / Original Name / Provider / Class / etc.
# Group into records by blank-line separators.
$records = ($pnputilOut -join "`n") -split "`r?`n`r?`n"
$matchingInfs = @()
foreach ($r in $records) {
    if ($r -match "VID_${TargetVid}" -or $r -match 'libwdi' -or $r -match 'WinUSB Devices' -or $r -match 'androidwinusb') {
        if ($r -match 'Published Name:\s*(\S+)') {
            $oemName = $Matches[1]
            $matchingInfs += $oemName
            Write-Host "  match: $oemName" -ForegroundColor Yellow
            ($r -split "`n") | Where-Object { $_ -match 'Original Name|Provider|Class' } | ForEach-Object { Write-Host "    $_" -ForegroundColor DarkGray }
        }
    }
}
if (-not $matchingInfs) {
    Write-Host '  None found. Nothing to purge.' -ForegroundColor Green
}

Write-Host ''
Write-Host '=== Purging matching OEM INFs ===' -ForegroundColor Cyan
foreach ($oem in $matchingInfs) {
    Write-Host "  pnputil /delete-driver $oem /uninstall /force"
    & pnputil /delete-driver $oem /uninstall /force 2>&1 | ForEach-Object { Write-Host "    $_" -ForegroundColor DarkGray }
}

if ($dev) {
    Write-Host ''
    Write-Host '=== Bouncing device ===' -ForegroundColor Cyan
    try {
        Disable-PnpDevice -InstanceId $dev.InstanceId -Confirm:$false -ErrorAction Stop
        Start-Sleep -Seconds 1
        Enable-PnpDevice  -InstanceId $dev.InstanceId -Confirm:$false -ErrorAction Stop
        Write-Host '  Disable/Enable cycle complete.' -ForegroundColor Green
    } catch {
        Write-Host "  Could not bounce automatically ($($_.Exception.Message)). Unplug + replug instead." -ForegroundColor Yellow
    }
    Start-Sleep -Seconds 2
}

Write-Host ''
Write-Host '=== Final state ===' -ForegroundColor Cyan
$dev2 = Get-PnpDevice -PresentOnly | Where-Object { $_.InstanceId -match "VID_${TargetVid}&PID_${TargetPid}" }
if ($dev2) {
    $svc = (Get-PnpDeviceProperty -InstanceId $dev2.InstanceId -KeyName 'DEVPKEY_Device_Service'      -ErrorAction SilentlyContinue).Data
    $inf = (Get-PnpDeviceProperty -InstanceId $dev2.InstanceId -KeyName 'DEVPKEY_Device_DriverInfPath' -ErrorAction SilentlyContinue).Data
    Write-Host "  Class: $($dev2.Class)   Status: $($dev2.Status)"
    Write-Host "  Now bound to: service=$svc  inf=$inf"
    if ($svc -eq 'WinUSB' -and $inf -like 'oem*') {
        Write-Host '  Still bound to a third-party WinUSB binding. You may need to unplug + replug.' -ForegroundColor Yellow
    } elseif (-not $svc -or $svc -eq 'usbccgp') {
        Write-Host '  Clean state - no specific driver claimed. Ready to install the Google ADB driver.' -ForegroundColor Green
    }
} else {
    Write-Host '  Device not present. Plug it in and run install-android-driver.ps1.' -ForegroundColor Yellow
}

Write-Host ''
Write-Host 'Next: re-run scripts\install-android-driver.ps1, then go through Device Manager.' -ForegroundColor White
Write-Host 'In Device Manager, the critical path is:' -ForegroundColor White
Write-Host '  Update driver -> Browse my computer -> Let me pick -> Have Disk' -ForegroundColor White
Write-Host '  -> select tools\google-usb-driver\android_winusb.inf' -ForegroundColor White
Write-Host '  -> pick "Android ADB Interface" -> install anyway when warned about signature.' -ForegroundColor White

# Hold the elevated window open if launched via runas.
if ($Host.Name -eq 'ConsoleHost' -and $env:RESET_DRIVER_AUTOCLOSE -ne '1') {
    Write-Host ''
    try { Read-Host 'Press Enter to close' | Out-Null } catch {}
}
