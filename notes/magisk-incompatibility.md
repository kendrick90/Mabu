# Magisk doesn't root the Mabu (RK3288 H7R, Android 8.1)

Tested on unit 6 (`2022010500003`) on 2026-06-13. Both **Magisk v30.7**
(latest at time of test) and **Magisk v27.0** (the last branch with
explicit A6-A8.1 support) produced patched boot images that **hang at the
recovery "no command" screen** instead of booting Android.

## What we tried

For each version: pushed `lib/armeabi-v7a/*` (renamed: lib*.so -> bare
name) plus `assets/boot_patch.sh`, `util_functions.sh`, `stub.apk` to
`/data/local/tmp/`, plus the live boot.img dumped from this unit's boot
partition (LBA 0x20000, 32 MB). Ran `sh boot_patch.sh boot.img` over
`adb shell`. The script ran, repacked `new-boot.img` at the original
32 MB size. Wrote it back via `rkdeveloptool wl 0x20000`.

## Symptom

Device kernel boots (we can see it), but instead of `init` taking over
normal Android boot, it lands at the AOSP recovery "no command" screen.
USB enumerates as `rockchipplatform unauthorized` (the recovery-mode
adbd, not our auth-bypassed /system/bin/adbd). Power cycle + vol-up
recovers it back to Loader for re-flash of the original.

## Why (best guess)

This boot.img has a layout Magisk doesn't anticipate:

```
HEADER_VER      [0]          <- legacy header v0
KERNEL_SZ       [7831736]
RAMDISK_SZ      [1378512]
SECOND_SZ       [3749888]    <- 3.7 MB "second" section, unusual
EXTRA_SZ        [0]
CMDLINE         [buildvariant=user]
KERNEL_FMT      [zimage]     <- raw zImage, no gzip piggy
RAMDISK_FMT     [gzip]
```

Magisk's v30 output included three "Failed to patch" lines during
kernel cmdline modification. v27 silently skipped those steps. Either
way, repack produces a structurally-valid 32 MB image whose ramdisk
contains Magisk's `magiskinit` wrapper -- and that wrapper appears to
fail in a way that punts to recovery (its standard "I can't run, drop
to safe mode" failsafe).

The 3.7 MB SECOND section is the most likely culprit. On modern devices
SECOND is empty or contains a small dtb; here it's substantial and may
contain Rockchip-specific boot data (u-boot helper? DTBs?) that
Magisk's repack copies as opaque bytes but whose hash/offset the kernel
or u-boot validates after the ramdisk has shifted.

## Workaround

**Don't try to root via boot.img.** The `flash-mabu-v3.ps1` default is
now no-Magisk. We get everything the project needs without uid=0:

| Need | How |
|---|---|
| Motor UART (/dev/ttyS1) | Already `crwxrwxrwx`, label `serial_device` -- shell or app can open it directly |
| Autostart on boot | App `BOOT_COMPLETED` receiver, or Loader-side `/system/etc/init/*.rc` service |
| /system writes | Loader sector writes (the patch mechanism we already use) |
| Camera / mic / audio | App-level permissions |
| Network | Normal app permissions |

If uid=0 is ever genuinely required, the path is **not** Magisk. It's
a Loader-side install: write a static binary to `/system/xbin/` and a
`/system/etc/init/*.rc` service that runs it as root. We have full
sector-level control of /system already; we just don't need root often
enough to have built this yet.

## Recovery procedure (in case anyone tries Magisk again)

If a Magisk-patched boot.img leaves the device at recovery "no
command":

1. Hold power ~10s to force off.
2. Hold vol-up, then plug USB. Keep vol-up held.
3. `rkdeveloptool ld` should show `Pid=0x320a ... Loader`.
4. `rkdeveloptool wl 0x20000 firmware/originals/boot.img`
5. `rkdeveloptool rd`

This was exercised twice on unit 6 on the night of 2026-06-13 -- it
works reliably.
