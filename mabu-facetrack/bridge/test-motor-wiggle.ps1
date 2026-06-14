# Quick smoke test: connects to motor-bridge.sh on Mabu and wiggles the eyes.
# Run this from the PC; watch Mabu.

$host_ip = '192.168.0.180'
$port    = 7777

# Motor protocol helpers ------------------------------------------------
function Fletcher8([byte[]]$data) {
    $s1 = 0; $s2 = 0
    foreach ($b in $data) {
        $s1 = ($s1 + $b) % 255
        $s2 = ($s2 + $s1) % 255
    }
    return ,([byte](($s2 -shr 0) -band 0xFF)) + 0  # placeholder
}

function Build-Frame([byte[]]$payload) {
    $header = [byte[]]@(0xFA, 0x00, $payload.Length) + $payload
    $s1 = 0; $s2 = 0
    foreach ($b in $header) { $s1 = ($s1 + $b) % 255; $s2 = ($s2 + $s1) % 255 }
    $hi = [byte]($s2 -band 0xFF)
    $lo = [byte]($s1 -band 0xFF)
    return $header + @($hi, $lo)
}

function Motor-Frame([int]$bitmask, [int]$val0to100) {
    $wire = [int][Math]::Round($val0to100 * 2.55)
    if ($wire -lt 0) { $wire = 0 } elseif ($wire -gt 255) { $wire = 255 }
    return Build-Frame ([byte[]]@(0x01, $bitmask, 0x01, $wire))
}

$ELR = 0x10  # eyes left/right
$EUD = 0x08  # eyes up/down
$NR  = 0x02  # neck rotation

function Send-Bytes([byte[]]$bytes) {
    $c = New-Object System.Net.Sockets.TcpClient
    $c.Connect($host_ip, $port)
    $s = $c.GetStream()
    $s.Write($bytes, 0, $bytes.Length)
    $s.Flush()
    Start-Sleep -Milliseconds 100
    $c.Close()
}

Write-Output "Power-on..."
Send-Bytes ([byte[]]@(0xFA, 0x00, 0x02, 0x4F, 0x7F, 0x0B, 0xCB))
Start-Sleep -Milliseconds 600

Write-Output "Center eyes + neck..."
Send-Bytes (Motor-Frame $ELR 50)
Send-Bytes (Motor-Frame $EUD 50)
Send-Bytes (Motor-Frame $NR  50)
Start-Sleep -Milliseconds 600

foreach ($i in 1..2) {
    Write-Output "Eyes LEFT"
    Send-Bytes (Motor-Frame $ELR 15)
    Start-Sleep -Milliseconds 700
    Write-Output "Eyes RIGHT"
    Send-Bytes (Motor-Frame $ELR 85)
    Start-Sleep -Milliseconds 700
}

Write-Output "Center"
Send-Bytes (Motor-Frame $ELR 50)
Write-Output "Done."
