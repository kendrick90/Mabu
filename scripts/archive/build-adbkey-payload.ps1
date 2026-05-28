# build-adbkey-payload.ps1
#
# Builds an OTA package that adds the host's adbkey.pub to
# /data/misc/adb/adb_keys on the tablet. Signed with AOSP testkey.
#
# This is the "real" payload to fire after we confirm sideload + testkey
# works (via probe-stub-signed.zip). After this is sideloaded successfully
# and the device reboots back to main Android, `adb devices` should show
# the device as `device` (authorized) instead of `unauthorized`.
#
# Layout of the resulting zip:
#   META-INF/com/google/android/update-binary    (Edify interpreter from
#                                                 a known-good source)
#   META-INF/com/google/android/updater-script   (Edify script we author)
#   adbkey.pub                                   (host public key)
#
# Pre-requisite: scripts\install-tools.ps1 has been run (we need Java?
# no, we use sign-ota.py for the testkey signing). cryptography lib
# installed for sign-ota.py.

$ErrorActionPreference = 'Stop'

$RepoRoot = Split-Path -Parent $PSScriptRoot
$DumpsDir = Join-Path $RepoRoot 'dumps'
$ToolsDir = Join-Path $RepoRoot 'tools'
$KeyDir   = Join-Path $ToolsDir 'testkey'

$AdbKeyPub = Join-Path $env:USERPROFILE '.android\adbkey.pub'
if (-not (Test-Path $AdbKeyPub)) {
    Write-Host "Host adbkey.pub not found at $AdbKeyPub. Run 'adb start-server' once to generate." -ForegroundColor Red
    exit 1
}
if (-not (Test-Path (Join-Path $KeyDir 'testkey.pk8'))) {
    Write-Host "AOSP testkey not found. Re-run scripts\install-android-driver.ps1 setup or download manually." -ForegroundColor Red
    exit 1
}

# We don't have an Edify update-binary on this machine. The standard path
# is to extract one from a known-good public source. Until we have one,
# this script will document what's missing and stage everything else.
$UpdateBinary = Join-Path $ToolsDir 'edify\update-binary'
if (-not (Test-Path $UpdateBinary)) {
    Write-Host '' -ForegroundColor Yellow
    Write-Host '*** Need an ARM Edify update-binary ***' -ForegroundColor Yellow
    Write-Host '' -ForegroundColor Yellow
    Write-Host "Place an ARM ELF Edify interpreter at: $UpdateBinary" -ForegroundColor Yellow
    Write-Host '' -ForegroundColor Yellow
    Write-Host 'Two reasonable sources:' -ForegroundColor Yellow
    Write-Host '  1. Magisk installer zip (https://github.com/topjohnwu/Magisk/releases) -' -ForegroundColor White
    Write-Host '     extract META-INF/com/google/android/update-binary' -ForegroundColor White
    Write-Host '  2. AnyKernel3 (https://github.com/osm0sis/AnyKernel3) -' -ForegroundColor White
    Write-Host '     ditto, extract from a release zip' -ForegroundColor White
    Write-Host '' -ForegroundColor Yellow
    Write-Host 'After placing the binary, re-run this script.' -ForegroundColor Yellow
    exit 1
}

# Stage a working directory.
$staging = Join-Path $env:TEMP "adbkey-payload-$([guid]::NewGuid().ToString('N'))"
$mi = Join-Path $staging 'META-INF\com\google\android'
New-Item -ItemType Directory -Path $mi -Force | Out-Null

# update-binary - the Edify interpreter
Copy-Item $UpdateBinary (Join-Path $mi 'update-binary')

# updater-script - mount /data, write the key, unmount
$script = @'
ui_print("Adding host adb key");
mount("ext4", "EMMC", "/dev/block/by-name/userdata", "/data");
package_extract_file("adbkey.pub", "/tmp/adbkey.pub");
run_program("/sbin/sh", "-c", "mkdir -p /data/misc/adb && cat /tmp/adbkey.pub >> /data/misc/adb/adb_keys && chown 2000:2000 /data/misc/adb/adb_keys && chmod 0640 /data/misc/adb/adb_keys");
unmount("/data");
ui_print("Done. Reboot to apply.");
'@
[System.IO.File]::WriteAllText((Join-Path $mi 'updater-script'), $script.Replace("`r",''))

# adbkey.pub - the host public key
Copy-Item $AdbKeyPub (Join-Path $staging 'adbkey.pub')

# Zip it up
$unsignedZip = Join-Path $DumpsDir 'add-adbkey-unsigned.zip'
$signedZip   = Join-Path $DumpsDir 'add-adbkey.zip'
if (Test-Path $unsignedZip) { Remove-Item $unsignedZip -Force }
Compress-Archive -Path "$staging\*" -DestinationPath $unsignedZip -Force
Write-Host "Built unsigned zip: $unsignedZip ($([math]::Round((Get-Item $unsignedZip).Length/1KB,2)) KB)" -ForegroundColor Green

# Sign with testkey
python (Join-Path $RepoRoot 'scripts\sign-ota.py') $unsignedZip $signedZip (Join-Path $KeyDir 'testkey.x509.pem') (Join-Path $KeyDir 'testkey.pk8')

# Cleanup
Remove-Item $staging -Recurse -Force
Write-Host ''
Write-Host "Ready to sideload: $signedZip" -ForegroundColor Green
Write-Host "Once on the tablet, the script:" -ForegroundColor White
Write-Host '  1. Mounts /data' -ForegroundColor White
Write-Host '  2. Appends host adbkey.pub to /data/misc/adb/adb_keys' -ForegroundColor White
Write-Host '  3. Sets correct ownership/permissions (2000:2000, 0640)' -ForegroundColor White
Write-Host '  4. Unmounts /data' -ForegroundColor White
Write-Host '' -ForegroundColor White
Write-Host 'After install, reboot to main Android. adb devices should show "device" (authorized).' -ForegroundColor White
