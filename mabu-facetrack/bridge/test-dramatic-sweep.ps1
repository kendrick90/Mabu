# Dramatic motor sweep through the bridge.
# Sends slowly and holds each position so you can watch the movement clearly.

$host_ip = '192.168.0.180'
$port    = 7777

function Build-Frame([byte[]]$payload) {
    $header = [byte[]]@(0xFA, 0x00, $payload.Length) + $payload
    $s1 = 0; $s2 = 0
    foreach ($b in $header) { $s1 = ($s1 + $b) % 255; $s2 = ($s2 + $s1) % 255 }
    return $header + @([byte]$s2, [byte]$s1)
}

function Motor-Frame([int]$bitmask, [int]$val0to100) {
    $wire = [int][Math]::Round($val0to100 * 2.55)
    if ($wire -lt 0) { $wire = 0 } elseif ($wire -gt 255) { $wire = 255 }
    return Build-Frame ([byte[]]@(0x01, $bitmask, 0x01, $wire))
}

$LDL = 0x40
$LDR = 0x20
$ELR = 0x10
$EUD = 0x08
$NE  = 0x04
$NR  = 0x02
$NT  = 0x01

function Send-Bytes([byte[]]$bytes) {
    $c = New-Object System.Net.Sockets.TcpClient
    $c.Connect($host_ip, $port)
    $s = $c.GetStream()
    $s.Write($bytes, 0, $bytes.Length)
    $s.Flush()
    Start-Sleep -Milliseconds 80
    $c.Close()
}

function Go([int]$bitmask, [int]$val, [string]$name) {
    Write-Output "  $name -> $val"
    Send-Bytes (Motor-Frame $bitmask $val)
}

Write-Output "=== POWER ON ==="
Send-Bytes ([byte[]]@(0xFA, 0x00, 0x02, 0x4F, 0x7F, 0x0B, 0xCB))
Start-Sleep -Seconds 1

Write-Output "=== CENTER ALL ==="
Go $ELR 50 'eyes L/R'
Go $EUD 50 'eyes U/D'
Go $NR  50 'neck rot'
Go $NT  50 'neck tilt'
Go $NE  50 'neck elev'
Go $LDL 25 'eyelid L (open)'
Go $LDR 25 'eyelid R (open)'
Start-Sleep -Seconds 2

Write-Output "=== NECK ROTATION SLOW SWEEP ==="
foreach ($v in 50, 30, 10, 30, 50, 70, 90, 70, 50) {
    Go $NR $v 'neck rot'
    Start-Sleep -Milliseconds 800
}

Write-Output "=== EYES LEFT/RIGHT BIG ==="
foreach ($v in 50, 10, 90, 10, 90, 50) {
    Go $ELR $v 'eyes L/R'
    Start-Sleep -Milliseconds 700
}

Write-Output "=== BLINKS (slow) ==="
for ($i = 0; $i -lt 3; $i++) {
    Go $LDL 90 'lid L close'
    Go $LDR 90 'lid R close'
    Start-Sleep -Milliseconds 500
    Go $LDL 25 'lid L open'
    Go $LDR 25 'lid R open'
    Start-Sleep -Milliseconds 700
}

Write-Output "=== REST ==="
Go $ELR 50 'eyes L/R'
Go $EUD 50 'eyes U/D'
Go $NR  50 'neck rot'
Go $NT  50 'neck tilt'
Write-Output "Done."
