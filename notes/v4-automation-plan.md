# V4 plan: zero-touch automation

Goal: user plugs tablet in, holds vol-up on power-on, walks away. Returns
to a fully liberated + provisioned + (optionally) rooted unit. No manual
taps on the touch UI, no PowerShell prompts, no WiFi setup mid-flow.

## Manual steps to eliminate vs preserve

| Today | Action | V4 plan |
|---|---|---|
| Vol-up on power-on | Physical recovery trigger so Loader catches | **Keep** — unavoidable first-contact action, ~1 second |
| Tap "Allow" on USB debugging dialog | adbd auth gate | Already auto-bypassed by our adbd patches |
| Set up WiFi after /data wipe on touch UI | Lost credentials = no network adb | **Skip** — do all installs over USB ADB instead |
| Tap through Magisk app's "Select and Patch a File" | The script needs Magisk to produce magisk_patched.img | **Replace** with headless `adb shell sh boot_patch.sh boot.img` |
| Press Enter in PowerShell prompts | Read-Host calls | **Remove** — `Read-Host` crashes in non-interactive mode anyway |
| Calibrate motors via factorymode wizard | Per-unit mechanical zero offsets | **Defer** — keep as a separate post-flash step, ~3 minutes user time |

## Headless Magisk via `boot_patch.sh`

Magisk Manager's app-side "Select and Patch a File" UI is a thin wrapper
around `assets/boot_patch.sh` inside the APK. The same script can be
invoked directly via adb shell — no touch UI needed.

Sketch:
```powershell
# Unzip Magisk binaries to host once
unzip apks/Magisk.apk lib/armeabi-v7a/* assets/* -d magisk-bins/

# For each unit:
adb push magisk-bins/lib/armeabi-v7a/* /data/local/tmp/
adb push magisk-bins/assets/* /data/local/tmp/
adb push firmware/scratch/boot-<serial>.img /data/local/tmp/boot.img

# Magisk's .so files double as ELF binaries -- need to rename
adb shell 'cd /data/local/tmp && \
    for f in libmagisk32.so libmagiskboot.so libmagiskinit.so libmagiskpolicy.so libbusybox.so; do \
        n=$(echo $f | sed "s/^lib//;s/\.so$//"); \
        cp $f $n; chmod 755 $n; \
    done && \
    sh boot_patch.sh boot.img'

adb pull /data/local/tmp/new-boot.img firmware/scratch/boot-magisk-<serial>.img
```

Then write `new-boot.img` to the boot partition via Loader. Same as
manual flow, no human touch.

**Risk**: prior hand-rolled boot.img patches were rejected by u-boot
(unbootable). Magisk's patcher preserves AVB and SHA-1 structure
properly, but this is genuinely unproven on RK3288 Android 8.1.
First V4 trial should keep the original boot.img backed up and
verify boot succeeds before claiming victory.

**Risk**: Magisk v30.7 may require Android 9+. If it does, downgrade
to Magisk v27.x which supported back through Android 6. Need to confirm
empirically.

## USB → WiFi failover

Esper-active units have a stale DPM service that wedges USB ADB about
~5-30s after boot (we've seen this on units 1, 3, 6). Self-healing flow:

```
Wait-Device:
  for attempt in 1..N:
    if "adb -s <serial> shell echo ok" returns "ok" via USB → return USB serial
    for ip in last-known-ip, 10.0.0.*:5555:
      if "adb connect $ip" succeeds AND echo ok works → return $ip
    sleep 5
  fail
```

Last-known-ip is read from a per-unit state file we maintain across
runs. If unit has never been seen on WiFi yet, scan local subnet (one
ping sweep with -W 200 then probe :5555).

## Resume + idempotence

Already have `-ResumeFrom <phase>` in v3. V4 keeps this. Plus:
- Each phase writes a marker to `pre-wipe-archive/unit-<serial>/.phase-<name>.done`
- Default ResumeFrom = next pending phase, not always "catch"
- All file writes use idempotent patterns (overwrite or skip-if-exists)

## Non-interactive safety

- Replace `Read-Host` with: if `[Environment]::UserInteractive -and -not [Console]::IsOutputRedirected`, prompt; else skip
- All confirmation prompts get a `-Yes` flag (default true in non-interactive mode)

## Phase order in v4

```
0. (user) vol-up while plugging in power
1. catch Loader (polling)
2. liberate Loader-side: ALL patches (parameter, adbd, EOCDs, init zeros)
3. rd to Android
4. wait for ADB (USB or WiFi self-heal)
5. capture-shell: APKs, sdcard, dumpsys
6. magisk-headless: push boot.img + magisk bins, run boot_patch.sh, pull new-boot.img
7. reboot loader (via adb)
8. write new-boot.img to boot partition
9. rd to Android
10. wait for ADB; verify `su -c id` succeeds
11. capture-root: /data/data/com.catalia.factorymode, /data/system
12. reboot loader
13. /data wipe 96 MB
14. rd, wait for ADB (USB ok post-wipe per all units to date)
15. install F-Droid, Lawnchair, factorymode, OpenCV Manager
16. push animation CSVs + nuance + sound.raw
17. (optional) restore-adb-auth.ps1 for ship-mode
18. done — print "Run motor calibration on factorymode Trouble Shooting/Motor Debug"
```

Total expected time: ~8 minutes unattended after initial vol-up.

## Validation plan

Don't promote v4 until it runs cleanly on TWO consecutive fresh Esper
Mabus end-to-end with zero manual intervention (except vol-up). Keep v3
and v2 in tree until then; v2 stays the safe default.

## Files we'd add for v4

- `scripts/flash-mabu-v4.ps1` — orchestrator
- `scripts/magisk-headless.ps1` — boot_patch.sh via adb shell
- `tools/magisk-bins/` — extracted binaries from Magisk.apk (one-time prep,
  committed since they're small)
- `scripts/find-device-v4.ps1` (or inline function) — USB/WiFi self-heal
