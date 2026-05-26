# catch-loader-and-dump.ps1
#
# Watches for the Rockchip Loader (VID 2207 PID 320A) to appear, sends
# a keepalive command IMMEDIATELY to lock it in Loader mode (otherwise
# u-boot auto-continues to kernel boot after ~10 sec), then performs
# chunked partition dumps.
#
# Background: cpebit's rkdeveloptool sends one CBW per `rl` command. If
# the requested data length exceeds what the loader can handle in a
# single transfer (~8 MB seems to be the safe ceiling), the read hangs
# and corrupts the loader state. We dump in 4 MB chunks.
#
# Usage:
#   .\catch-loader-and-dump.ps1                   # default: parameter, misc, boot, recovery
#   .\catch-loader-and-dump.ps1 -Names misc,boot
#   .\catch-loader-and-dump.ps1 -UserdataToo      # also dump the 11 GB userdata
#
# After power-cycling the tablet, this script catches the loader within
# ~10 seconds, locks it in place, then dumps the requested partitions.

[CmdletBinding()]
param(
    [string[]]$Names = @('parameter','misc','boot','recovery'),
    [switch]$UserdataToo,
    [int]$ChunkSectors = 8192,       # 4 MB chunks - confirmed safe
    [int]$WatchSeconds = 60
)

$ErrorActionPreference = 'Stop'
$Rk = 'C:\Users\User\Documents\GitHub\Mabu\tools\rkdeveloptool\rkdeveloptool.exe'
$DumpDir = 'C:\Users\User\Documents\GitHub\Mabu\dumps'
if (-not (Test-Path $DumpDir)) { New-Item -ItemType Directory -Path $DumpDir -Force | Out-Null }

# Partition layout extracted from parameter file at sector 0
$Partitions = [ordered]@{
    'parameter' = @{ Start = 0;         Count = 16        }   # PARM file at sector 0
    'uboot'     = @{ Start = 0x2000;    Count = 0x2000    }   # 4 MB
    'trust'     = @{ Start = 0x4000;    Count = 0x2000    }
    'misc'      = @{ Start = 0x6000;    Count = 0x2000    }
    'resource'  = @{ Start = 0x8000;    Count = 0x8000    }   # 16 MB
    'kernel'    = @{ Start = 0x10000;   Count = 0x10000   }   # 32 MB
    'boot'      = @{ Start = 0x20000;   Count = 0x10000   }   # 32 MB
    'recovery'  = @{ Start = 0x30000;   Count = 0x20000   }   # 64 MB
    'security'  = @{ Start = 0x88000;   Count = 0x2000    }
    'metadata'  = @{ Start = 0x58a000;  Count = 0x8000    }
    'vendor'    = @{ Start = 0x592000;  Count = 0x80000   }   # 256 MB
    'oem'       = @{ Start = 0x612000;  Count = 0x80000   }   # 256 MB
    'system'    = @{ Start = 0x18a000;  Count = 0x400000  }   # 2 GB
    'cache'     = @{ Start = 0x8a000;   Count = 0x100000  }   # 512 MB
    'userdata'  = @{ Start = 0x692400;  Count = 23420928  }   # ~11.2 GB - calculated
}

if ($UserdataToo -and ($Names -notcontains 'userdata')) {
    $Names = @($Names) + 'userdata'
}

function Test-Loader {
    $out = & $Rk ld 2>&1
    return ($out -match 'Vid=0x2207,Pid=0x320a.*Loader')
}
function Test-LoaderResponsive {
    # rfi is small, fast, and works on a healthy loader
    $j = Start-Job -ScriptBlock {
        & 'C:\Users\User\Documents\GitHub\Mabu\tools\rkdeveloptool\rkdeveloptool.exe' rfi 2>&1
    }
    $done = Wait-Job $j -Timeout 5
    if ($done) {
        $out = Receive-Job $j
        Remove-Job $j -Force
        return ($out -match 'Flash Info|Flash Size|Sectors')
    } else {
        Stop-Job $j; Remove-Job $j -Force
        Get-Process rkdeveloptool -ErrorAction SilentlyContinue | Stop-Process -Force
        return $false
    }
}

# If a wedged loader is already present, wait for it to disappear first
# (user must power-cycle the tablet). Only then accept a *fresh* loader.
$initialPresent = Test-Loader
if ($initialPresent) {
    Write-Host "Loader currently present - checking if it's responsive..." -ForegroundColor Yellow
    if (Test-LoaderResponsive) {
        Write-Host "Loader is alive and responding. Proceeding." -ForegroundColor Green
    } else {
        Write-Host "Loader present but UNRESPONSIVE (wedged from earlier failed read)." -ForegroundColor Red
        Write-Host "Power-cycle the tablet now. Script will wait for it to disappear, then re-appear fresh." -ForegroundColor Yellow
        # Wait for disappearance
        $start = Get-Date
        while ((Test-Loader) -and (((Get-Date) - $start).TotalSeconds -lt $WatchSeconds)) {
            Start-Sleep -Milliseconds 300
        }
        if (Test-Loader) {
            Write-Host "Device still present after $WatchSeconds s. Aborting - manually power-cycle and re-run." -ForegroundColor Red
            exit 1
        }
        Write-Host "Device disappeared. Waiting for fresh loader..." -ForegroundColor Cyan
    }
}

# Wait for fresh loader to appear and respond
$start = Get-Date
$ready = $false
while (((Get-Date) - $start).TotalSeconds -lt $WatchSeconds) {
    if (Test-Loader) {
        $appearedAt = ((Get-Date) - $start).TotalSeconds
        Write-Host ("[+$([math]::Round($appearedAt,1))s] Loader detected. Testing responsiveness with rfi...") -ForegroundColor Green
        if (Test-LoaderResponsive) {
            Write-Host "  responsive - locking in." -ForegroundColor Green
            $ready = $true
            break
        } else {
            Write-Host "  not responding - might still be settling, retrying..." -ForegroundColor Yellow
            Start-Sleep -Seconds 1
        }
    }
    Start-Sleep -Milliseconds 300
}
if (-not $ready) {
    Write-Host "Never got a responsive loader within $WatchSeconds s. Power-cycle and try again." -ForegroundColor Red
    exit 1
}

# Chunked dump function
function Dump-Partition {
    param([string]$Name, [int]$Start, [int]$Count)
    $out = Join-Path $DumpDir "$Name.img"
    $expected = [int64]$Count * 512
    if ((Test-Path $out) -and ((Get-Item $out).Length -eq $expected)) {
        Write-Host "[skip] $Name already at $expected bytes" -ForegroundColor DarkGray
        return $true
    }
    Write-Host ("[dump] {0,-12}  start=0x{1:X}  sectors={2}  size={3:N0} bytes ({4:N2} MB)" -f $Name, $Start, $Count, $expected, ($expected/1MB)) -ForegroundColor Cyan

    $tmpChunk = "$env:TEMP\rk-chunk-$([guid]::NewGuid().ToString('N')).bin"
    $stream = [System.IO.File]::Create($out)
    try {
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        $sec = $Start
        $remaining = $Count
        while ($remaining -gt 0) {
            $chunk = [Math]::Min($remaining, $ChunkSectors)
            $chunkOut = & $Rk rl $sec $chunk $tmpChunk 2>&1
            if (-not (Test-Path $tmpChunk)) {
                Write-Host "  FAILED at sector 0x$('{0:X}' -f $sec): $chunkOut" -ForegroundColor Red
                return $false
            }
            $bytes = [System.IO.File]::ReadAllBytes($tmpChunk)
            $stream.Write($bytes, 0, $bytes.Length)
            $sec += $chunk
            $remaining -= $chunk
            Remove-Item $tmpChunk -ErrorAction SilentlyContinue
            if (($sec - $Start) % ($ChunkSectors * 16) -eq 0) {
                $pct = [math]::Round((($sec - $Start) * 100.0 / $Count), 1)
                $elapsed = $sw.Elapsed.TotalSeconds
                $mbs = ($stream.Position / 1MB) / $elapsed
                Write-Host ("       {0}%  {1:N0} bytes  {2:N1}s  {3:N2} MB/s" -f $pct, $stream.Position, $elapsed, $mbs) -ForegroundColor DarkGray
            }
        }
        $sw.Stop()
        Write-Host ("       done {0:N1}s  {1:N0} bytes  avg {2:N2} MB/s" -f $sw.Elapsed.TotalSeconds, $stream.Position, ($stream.Position / 1MB / $sw.Elapsed.TotalSeconds)) -ForegroundColor Green
        return $true
    } finally {
        $stream.Close()
    }
}

foreach ($name in $Names) {
    if (-not $Partitions.Contains($name)) {
        Write-Host "Unknown partition: $name" -ForegroundColor Yellow
        continue
    }
    $p = $Partitions[$name]
    $ok = Dump-Partition -Name $name -Start $p.Start -Count $p.Count
    if (-not $ok) {
        Write-Host "Aborting due to failure on $name" -ForegroundColor Red
        break
    }
}

Write-Host ""
Write-Host "=== summary ===" -ForegroundColor Cyan
Get-ChildItem $DumpDir -Filter '*.img' | Select-Object Name, @{N='Size';E={'{0:N0} bytes' -f $_.Length}}, LastWriteTime | Format-Table -AutoSize
