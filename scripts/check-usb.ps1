# check-usb.ps1
# Lists problem-state and Rockchip-VID USB devices.
# Use this every time you re-plug the Mabu's USB header to confirm
# what Windows sees.
#
# Expected good state: a device with VID_2207 (Rockchip), Status OK.
# Expected bad state:  VID_0000&PID_0002 (descriptor request failed)
#                      -> wiring / signal-integrity issue, not a driver issue.

Write-Host "=== Problem-state devices ===" -ForegroundColor Yellow
Get-PnpDevice -PresentOnly |
    Where-Object { $_.Status -ne 'OK' } |
    Select-Object FriendlyName, Status, Class, InstanceId |
    Format-Table -AutoSize -Wrap

Write-Host "`n=== Rockchip-VID devices (VID_2207) ===" -ForegroundColor Yellow
$rk = Get-PnpDevice -PresentOnly |
    Where-Object { $_.InstanceId -match 'VID_2207' }
if ($rk) {
    $rk | Select-Object FriendlyName, Status, Class, InstanceId |
        Format-Table -AutoSize -Wrap
} else {
    Write-Host "  (none found)" -ForegroundColor DarkGray
}

Write-Host "`n=== Recently arrived USB devices (last 5 min) ===" -ForegroundColor Yellow
$cutoff = (Get-Date).AddMinutes(-5)
Get-PnpDevice -PresentOnly | ForEach-Object {
    $arrival = (Get-PnpDeviceProperty -InstanceId $_.InstanceId `
        -KeyName 'DEVPKEY_Device_LastArrivalDate' -ErrorAction SilentlyContinue).Data
    if ($arrival -and $arrival -gt $cutoff) {
        [PSCustomObject]@{
            Arrived    = $arrival
            Name       = $_.FriendlyName
            Class      = $_.Class
            Status     = $_.Status
            InstanceId = $_.InstanceId
        }
    }
} | Sort-Object Arrived -Descending | Format-Table -AutoSize -Wrap
