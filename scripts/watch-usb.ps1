# watch-usb.ps1
#
# Polls the USB subsystem and prints any change in state. Useful while
# experimenting with: holding pads to GND for MaskROM, button combos
# for recovery boot, ADKEY voltages, USB cable swaps, etc. You see in
# real time what enumeration each action produces.
#
# Tracks:
#   - Any device with VID 0x2207 (Rockchip)
#   - Any device with the Android ADB compatible ID
#   - Any device in error state (descriptor request failed, etc.)
#   - New / removed / state-changed USB devices
#
# Usage:
#   .\watch-usb.ps1                  # poll forever, default 1s
#   .\watch-usb.ps1 -Interval 0.5    # faster polling
#   Ctrl-C to stop.

[CmdletBinding()]
param(
    [double]$Interval = 1.0
)

function Get-Snapshot {
    Get-PnpDevice -PresentOnly | Where-Object {
        $_.InstanceId -match 'VID_2207' -or
        $_.Status -ne 'OK' -or
        $_.Class -eq 'Unknown' -or
        $_.FriendlyName -match 'Android|ADB|MTP|Composite|Recovery|Bootloader' -or
        ((Get-PnpDeviceProperty -InstanceId $_.InstanceId -KeyName 'DEVPKEY_Device_CompatibleIds' -ErrorAction SilentlyContinue).Data -join ' ') -match 'Class_FF&SubClass_42'
    } | ForEach-Object {
        [PSCustomObject]@{
            InstanceId   = $_.InstanceId
            FriendlyName = $_.FriendlyName
            Class        = $_.Class
            Status       = $_.Status
        }
    }
}

function Format-Row($r) {
    "{0,-12}  {1,-22}  {2,-7}  {3}" -f $r.Class, $r.FriendlyName, $r.Status, $r.InstanceId
}

Write-Host "Watching USB. Ctrl-C to stop." -ForegroundColor Cyan
Write-Host ""

$prev = @{}
foreach ($d in (Get-Snapshot)) { $prev[$d.InstanceId] = $d }

# Print baseline.
Write-Host "[baseline $(Get-Date -Format HH:mm:ss)]" -ForegroundColor Yellow
if ($prev.Count -eq 0) {
    Write-Host "  (no relevant devices)" -ForegroundColor DarkGray
} else {
    $prev.Values | ForEach-Object { Write-Host ("  " + (Format-Row $_)) -ForegroundColor DarkGray }
}
Write-Host ""

while ($true) {
    Start-Sleep -Seconds $Interval
    $now = @{}
    foreach ($d in (Get-Snapshot)) { $now[$d.InstanceId] = $d }

    $added   = $now.Keys   | Where-Object { -not $prev.ContainsKey($_) }
    $removed = $prev.Keys  | Where-Object { -not $now.ContainsKey($_) }
    $changed = $now.Keys   | Where-Object {
        $prev.ContainsKey($_) -and (
            $prev[$_].Status        -ne $now[$_].Status        -or
            $prev[$_].Class         -ne $now[$_].Class         -or
            $prev[$_].FriendlyName  -ne $now[$_].FriendlyName
        )
    }

    if ($added -or $removed -or $changed) {
        $ts = Get-Date -Format HH:mm:ss
        foreach ($id in $added)   { Write-Host ("[$ts +ADD ] " + (Format-Row $now[$id]))                   -ForegroundColor Green }
        foreach ($id in $removed) { Write-Host ("[$ts -DEL ] " + (Format-Row $prev[$id]))                  -ForegroundColor Red   }
        foreach ($id in $changed) {
            Write-Host ("[$ts ~CHG ] " + (Format-Row $now[$id]))                                            -ForegroundColor Yellow
            Write-Host ("            was: " + (Format-Row $prev[$id]))                                      -ForegroundColor DarkYellow
        }
    }

    $prev = $now
}
