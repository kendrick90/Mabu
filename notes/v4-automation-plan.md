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

## Magisk is OUT of the V4 plan

Tested empirically on unit 6 on 2026-06-13. Both **Magisk v30.7 and
v27.0** produce repacked boot images that boot the kernel but then
hang at the recovery "no command" screen. Documented in
`magisk-incompatibility.md`. The 3.7 MB SECOND section in the Mabu
boot.img is the most likely structural culprit; Magisk's repack
doesn't anticipate it.

V4 therefore does NOT root the device. We don't need uid=0 for the
project's mission anyway:

- `/dev/ttyS1` (motor UART) is `crwxrwxrwx` -- shell or any app can
  open it directly.
- `/system` is mutable via Loader-side sector writes (the patch
  mechanism we already use).
- Autostart-on-boot lives in app `BOOT_COMPLETED` receivers or in
  Loader-side `/system/etc/init/*.rc` service files we add.

If a future task genuinely needs uid=0 (e.g., to instrument
system_server, modify iptables, or read another app's /data/data),
the path is a **Loader-side static-su install**: drop a setuid binary
at `/system/xbin/su` and an `init.rc` service that chowns/chmods it
on boot. This bypasses Magisk entirely and uses tooling we already
trust. ~30 min to build; not in scope unless a need appears.

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
2. parameter + adbd patches Loader-side
3. rd to Android
4. wait for ADB (USB or WiFi self-heal)
5. capture-shell: APKs, sdcard, dumpsys
6. reboot loader (via adb)
7. destructive patches: EOCD nukes + init zeros
8. /data wipe 96 MB
9. rd, wait for ADB
10. install F-Droid, Lawnchair, factorymode, OpenCV Manager
11. push animation CSVs + nuance + sound.raw
12. (optional) restore-adb-auth.ps1 for ship-mode
13. done — print "Run motor calibration on factorymode Trouble Shooting/Motor Debug"
```

Total expected time: ~5 minutes unattended after initial vol-up
(shorter than originally planned now that Magisk + the second Loader
round-trip are out).

## Validation plan

Don't promote v4 until it runs cleanly on TWO consecutive fresh Esper
Mabus end-to-end with zero manual intervention (except vol-up). Keep v3
in tree as the proven default.

## Files we'd add for v4

- `scripts/flash-mabu-v4.ps1` — orchestrator (with WiFi self-heal,
  phase markers, no Read-Host)
- `scripts/find-device-v4.ps1` (or inline function) — USB/WiFi self-heal
