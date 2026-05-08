# analyze-dump.ps1
#
# Reads files in dumps\ and identifies what they are. For boot.img,
# parses the Android boot header v0/v1/v2 to extract kernel cmdline,
# OS version, and key offsets - this is usually the most informative
# single artifact for figuring out what SoC / kernel / Android variant
# the device is running.
#
# Pure PowerShell - no external tools required. For deeper analysis
# of non-boot partitions you'll want binwalk / unpack_bootimg / simg2img
# (script tells you where to point them).
#
# Usage:
#   .\analyze-dump.ps1                    # analyze all *.img in dumps\
#   .\analyze-dump.ps1 -Image boot        # analyze a specific one
#   .\analyze-dump.ps1 -WriteNotes        # also append findings to notes\dump-analysis.md

[CmdletBinding()]
param(
    [string]$Image,
    [switch]$WriteNotes
)

$ErrorActionPreference = 'Stop'
$RepoRoot = Split-Path -Parent $PSScriptRoot
$DumpDir  = Join-Path $RepoRoot 'dumps'
$NotesOut = Join-Path $RepoRoot 'notes\dump-analysis.md'

if (-not (Test-Path $DumpDir)) {
    Write-Host "No dumps\ directory yet. Run scripts\dump-partitions.ps1 first." -ForegroundColor Yellow
    exit 1
}

# ---- helpers ---------------------------------------------------------------

function Read-FileBytes {
    param([string]$Path, [int]$Offset = 0, [int]$Count = 4096)
    $fs = [System.IO.File]::OpenRead($Path)
    try {
        $fs.Position = $Offset
        $buf = New-Object byte[] $Count
        $read = $fs.Read($buf, 0, $Count)
        if ($read -lt $Count) { $buf = $buf[0..($read-1)] }
        return ,$buf
    } finally { $fs.Dispose() }
}

function Get-NullTerminatedString {
    param([byte[]]$Bytes, [int]$Offset, [int]$MaxLen)
    $end = [Math]::Min($Bytes.Length, $Offset + $MaxLen)
    $stop = $end
    for ($i = $Offset; $i -lt $end; $i++) {
        if ($Bytes[$i] -eq 0) { $stop = $i; break }
    }
    return [System.Text.Encoding]::UTF8.GetString($Bytes, $Offset, $stop - $Offset)
}

function Get-Uint32LE {
    param([byte[]]$Bytes, [int]$Offset)
    return [BitConverter]::ToUInt32($Bytes, $Offset)
}

function Identify-Magic {
    param([byte[]]$Head)
    if ($Head.Length -lt 8) { return 'too-short' }
    $magic8 = [System.Text.Encoding]::ASCII.GetString($Head, 0, 8)
    if ($magic8 -eq 'ANDROID!') { return 'android-boot' }

    if ($Head.Length -ge 4) {
        $b0,$b1,$b2,$b3 = $Head[0..3]
        # Android sparse: 0x3aff26ed (LE)
        if ($b0 -eq 0xed -and $b1 -eq 0x26 -and $b2 -eq 0xff -and $b3 -eq 0x3a) { return 'android-sparse' }
        # squashfs: 'hsqs'
        if ($b0 -eq 0x68 -and $b1 -eq 0x73 -and $b2 -eq 0x71 -and $b3 -eq 0x73) { return 'squashfs' }
        # gz
        if ($b0 -eq 0x1f -and $b1 -eq 0x8b) { return 'gzip' }
    }

    # ext4 superblock at +0x438, magic 0xEF53 at +0x438+0x38 (i.e. +0x470 from start)
    if ($Head.Length -ge 0x438 + 0x40) {
        $eMagicLo = $Head[0x438 + 0x38]
        $eMagicHi = $Head[0x438 + 0x39]
        if ($eMagicLo -eq 0x53 -and $eMagicHi -eq 0xef) { return 'ext4' }
    }

    # f2fs magic 0xF2F52010 at +0x400
    if ($Head.Length -ge 0x404) {
        $f0 = $Head[0x400]; $f1 = $Head[0x401]; $f2 = $Head[0x402]; $f3 = $Head[0x403]
        if ($f0 -eq 0x10 -and $f1 -eq 0x20 -and $f2 -eq 0xf5 -and $f3 -eq 0xf2) { return 'f2fs' }
    }

    # u-boot legacy uImage magic 0x27051956 at offset 0
    if ($Head.Length -ge 4) {
        if ($Head[0] -eq 0x27 -and $Head[1] -eq 0x05 -and $Head[2] -eq 0x19 -and $Head[3] -eq 0x56) {
            return 'uboot-uImage'
        }
    }

    # Rockchip RKFW header
    $magic4 = [System.Text.Encoding]::ASCII.GetString($Head, 0, 4)
    if ($magic4 -eq 'RKFW' -or $magic4 -eq 'RKAF') { return 'rockchip-firmware' }

    return 'unknown'
}

function Parse-AndroidBootImg {
    param([byte[]]$Head)
    if ($Head.Length -lt 0x660) { return $null }

    $kernelSize  = Get-Uint32LE $Head 0x08
    $kernelAddr  = Get-Uint32LE $Head 0x0C
    $ramdiskSize = Get-Uint32LE $Head 0x10
    $ramdiskAddr = Get-Uint32LE $Head 0x14
    $secondSize  = Get-Uint32LE $Head 0x18
    $tagsAddr    = Get-Uint32LE $Head 0x20
    $pageSize    = Get-Uint32LE $Head 0x24
    $headerVer   = Get-Uint32LE $Head 0x28
    $osVersionRaw = Get-Uint32LE $Head 0x2C
    $name        = Get-NullTerminatedString $Head 0x30 16
    $cmdline     = Get-NullTerminatedString $Head 0x40 512
    $extraCmdline= Get-NullTerminatedString $Head 0x260 1024

    # Decode os_version: top 25 bits = version (a:7,b:7,c:7), low 11 bits = patch_level (year-2000 7 bits + month 4 bits)
    $osMajor = ($osVersionRaw -shr 25) -band 0x7F
    $osMinor = ($osVersionRaw -shr 18) -band 0x7F
    $osPatch = ($osVersionRaw -shr 11) -band 0x7F
    $patchYear  = (($osVersionRaw -shr 4) -band 0x7F) + 2000
    $patchMonth = $osVersionRaw -band 0x0F

    [PSCustomObject]@{
        HeaderVersion = $headerVer
        Name          = $name
        Cmdline       = ($cmdline + $(if ($extraCmdline) { ' ' + $extraCmdline } else { '' })).Trim()
        KernelSize    = $kernelSize
        KernelAddr    = '0x{0:X8}' -f $kernelAddr
        RamdiskSize   = $ramdiskSize
        RamdiskAddr   = '0x{0:X8}' -f $ramdiskAddr
        SecondSize    = $secondSize
        TagsAddr      = '0x{0:X8}' -f $tagsAddr
        PageSize      = $pageSize
        OsVersion     = "$osMajor.$osMinor.$osPatch"
        SecurityPatch = "$patchYear-{0:D2}" -f $patchMonth
    }
}

function Find-PrintableStrings {
    # Mini `strings` impl: ASCII runs of length >= 6.
    param([byte[]]$Bytes, [int]$MinLen = 6)
    $found = New-Object System.Collections.Generic.List[string]
    $sb = New-Object System.Text.StringBuilder
    foreach ($b in $Bytes) {
        if ($b -ge 0x20 -and $b -lt 0x7F) {
            [void]$sb.Append([char]$b)
        } else {
            if ($sb.Length -ge $MinLen) { $found.Add($sb.ToString()) }
            [void]$sb.Clear()
        }
    }
    if ($sb.Length -ge $MinLen) { $found.Add($sb.ToString()) }
    return ,$found
}

# ---- main ------------------------------------------------------------------

$targets = if ($Image) {
    Get-ChildItem $DumpDir -Filter "$Image*.img" -ErrorAction SilentlyContinue
} else {
    Get-ChildItem $DumpDir -Filter '*.img' -ErrorAction SilentlyContinue
}

if (-not $targets) {
    Write-Host "No .img files in $DumpDir." -ForegroundColor Yellow
    exit 0
}

$report = @()
$report += "# Dump analysis"
$report += ""
$report += "Generated: $(Get-Date -Format o)"
$report += ""

foreach ($f in $targets) {
    Write-Host ''
    Write-Host "=== $($f.Name) ($([math]::Round($f.Length/1MB,2)) MB) ===" -ForegroundColor Cyan

    $headSize = [math]::Min(0x10000, $f.Length)
    $head = Read-FileBytes -Path $f.FullName -Offset 0 -Count $headSize
    $kind = Identify-Magic -Head $head

    Write-Host "  type: $kind" -ForegroundColor Yellow

    $report += "## $($f.Name)"
    $report += ""
    $report += "- Size: {0:N0} bytes ({1:N2} MB)" -f $f.Length, ($f.Length/1MB)
    $report += "- Type: $kind"

    switch ($kind) {
        'android-boot' {
            $info = Parse-AndroidBootImg -Head $head
            Write-Host ("  header version: v{0}" -f $info.HeaderVersion)
            Write-Host ("  name:           {0}" -f $info.Name)
            Write-Host ("  os version:     {0} (security patch {1})" -f $info.OsVersion, $info.SecurityPatch)
            Write-Host ("  page size:      {0}" -f $info.PageSize)
            Write-Host ("  kernel:         {0:N0} bytes @ {1}" -f $info.KernelSize, $info.KernelAddr)
            Write-Host ("  ramdisk:        {0:N0} bytes @ {1}" -f $info.RamdiskSize, $info.RamdiskAddr)
            Write-Host ('  cmdline:')
            Write-Host ("    {0}" -f $info.Cmdline) -ForegroundColor Green
            $report += "- Header version: v$($info.HeaderVersion)"
            $report += "- Internal name: ``$($info.Name)``"
            $report += "- Android OS version: $($info.OsVersion)"
            $report += "- Security patch: $($info.SecurityPatch)"
            $report += "- Page size: $($info.PageSize)"
            $report += "- Kernel: $($info.KernelSize) bytes @ $($info.KernelAddr)"
            $report += "- Ramdisk: $($info.RamdiskSize) bytes @ $($info.RamdiskAddr)"
            $report += "- Kernel cmdline:"
            $report += '  ```'
            $report += "  $($info.Cmdline)"
            $report += '  ```'
        }
        'android-sparse' {
            Write-Host '  Sparse Android image. Convert with: simg2img <in> <out.raw>' -ForegroundColor Yellow
            $report += "- Sparse Android image; convert with ``simg2img``."
        }
        'ext4' {
            Write-Host '  ext4 filesystem. Mount loopback (Linux/WSL): mount -o loop <file> /mnt' -ForegroundColor Yellow
            $report += "- ext4 filesystem. Mount via WSL: ``mount -o loop <file> /mnt``."
        }
        'f2fs'     { Write-Host '  f2fs filesystem.' }
        'squashfs' { Write-Host '  squashfs. unsquashfs in WSL.' }
        'gzip'     { Write-Host '  gzipped data.' }
        'uboot-uImage' { Write-Host '  legacy U-Boot uImage.' }
        'rockchip-firmware' { Write-Host '  Rockchip firmware container (RKFW/RKAF).' }
        default {
            # Look for u-boot banner if name suggests it
            if ($f.Name -match '^(uboot|u-boot|bootloader)') {
                $strings = Find-PrintableStrings -Bytes $head -MinLen 6
                $banners = $strings | Where-Object { $_ -match 'U-?Boot' }
                if ($banners) {
                    Write-Host "  u-boot banners:"
                    $banners | Select-Object -First 5 | ForEach-Object { Write-Host "    $_" -ForegroundColor Green }
                    $report += "- U-Boot banners:"
                    $banners | Select-Object -First 5 | ForEach-Object { $report += "  - ``$_``" }
                }
            } else {
                $strings = Find-PrintableStrings -Bytes $head -MinLen 12
                if ($strings.Count -gt 0) {
                    Write-Host '  notable strings (first 5):'
                    $strings | Select-Object -First 5 | ForEach-Object { Write-Host "    $_" -ForegroundColor DarkGray }
                }
            }
        }
    }
    $report += ""
}

if ($WriteNotes) {
    $notesDir = Split-Path -Parent $NotesOut
    if (-not (Test-Path $notesDir)) { New-Item -ItemType Directory -Path $notesDir -Force | Out-Null }
    $report -join "`n" | Set-Content -Path $NotesOut -Encoding UTF8
    Write-Host ''
    Write-Host "Wrote $NotesOut" -ForegroundColor Green
}
