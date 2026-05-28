# Mabu liberation scripts — live reference

Scripts that are part of the validated procedure or active diagnostics.
Dead-end attempts have been moved to `archive/` with explanations.

## Top-level entry point

**`flash-mabu.ps1`** — one-command provisioning for a fresh Mabu.
```powershell
# Catch Loader on power-on, then:
.\flash-mabu.ps1 -WipeData -RestoreMabu
```
Wraps everything below into a single workflow.

## Loader-side patching

| Script | Purpose |
|---|---|
| `liberate-mabu.ps1` | The 8 sector patches: parameter file (verity off, selinux permissive), adbd auth bypass (×2), Esper APK EOCD nukes (×3), init.esper.rc zero, set-device-owner.sh zero. Idempotent. |
| `wipe-data-head.ps1` | Zero the first N MB of /data so vold reformats on boot. Required for active-Esper units (Esper's DPC lives in /data/app). 96 MB is the validated sweet spot. |
| `patch-parameter.py` | Builds `firmware/patches/parameter-patched.img` from `firmware/originals/parameter.img` with the kernel cmdline + Rockchip CRC32 recomputed. Run once per build to regenerate; output is committed. |
| `find-eocd.py` | Locates the End-of-Central-Directory record inside each Esper APK and produces the per-APK `*-eocd-patched.bin` sector blobs. |

## Inspection / file location

| Script | Purpose |
|---|---|
| `find-esper-files.py` | Walks the ext4 inode tree in a partial system.img dump to locate `/system/etc/init/init.esper.rc` and `/system/bin/set-device-owner.sh` and report their data-block LBAs. How we found the targets for the two new patches. |
| `find-prop-default.py` | Same approach for `/system/etc/prop.default` (historical — props are no longer the path we patch). |
| `find-app-dirs.py` | Walks `/system/app` and `/system/priv-app` directory blocks. |
| `find-esper.py`, `find-esper-apks.py` | Earlier ext4 walkers used to first locate the Esper APKs. |
| `inspect-tablet.ps1` | Quick state probe via adb (props, owner, admins, packages). |
| `analyze-dump.ps1` | Offline analysis of a partition dump. |

## Dumping (fallback path — not used by the current procedure)

| Script | Purpose |
|---|---|
| `dump-range.ps1` | Read N sectors at LBA X via rkdeveloptool, with per-chunk timeout to detect Loader wedge. Used by the cycled dumper. |
| `dump-system-cycled.ps1` | Auto-cycled /system dump that uses `adb shell reboot loader` to re-enter Loader between batches (Loader wedges after ~28 MB of reads). Yields a flashable system.img if firmware drift ever requires it. |
| `dump-partitions.ps1`, `catch-loader-and-dump.ps1` | Earlier dumpers. |
| `wedge-probe.ps1` | Diagnostic for measuring the per-session Loader read-wedge boundary. |

## Setup / environment

| Script | Purpose |
|---|---|
| `install-tools.ps1` | Downloads rkdeveloptool, the Rockchip driver package, and Zadig. |
| `install-android-driver.ps1` | Patches Google's USB driver to include VID 0x2207 PID 0x0006 so adb sees the device. |
| `bind-winusb.ps1` | Binds WinUSB to the Loader (PID 0x320A) via Zadig so rkdeveloptool can talk to it. |
| `reset-driver.ps1` | Reset the USB driver binding if it gets stuck. |
| `check-usb.ps1`, `watch-usb.ps1` | USB enumeration diagnostics. |

## Emergency

| Script | Purpose |
|---|---|
| `restore-boot.ps1` | Writes the original boot.img back and clears misc, in case a patch attempt bricks boot. |

## firmware/ layout

The patches and originals these scripts use live under `firmware/`. See
`../firmware/README.md` for the full inventory and what each subdirectory
contains. Quick reference:

- `firmware/patches/` — committed. Small modified-firmware files written
  to the eMMC by `liberate-mabu.ps1` / `wipe-data-head.ps1`. Includes
  `parameter-patched.img`, the two `adbd-*-patched.bin`, the three
  `*-eocd-patched.bin`, and `zeros-{4k,16mb}.bin`.
- `firmware/originals/` — committed. Captured-from-device baseline bytes
  used by `restore-adb-auth.ps1` (the `adbd-*-orig.bin`), by
  `restore-boot.ps1` (`boot.img`), and as reference for `patch-parameter.py`
  (`parameter.img`).
- `firmware/system-probes/` — gitignored. Partial /system extracts used
  by the ext4 inode walkers (`find-esper-files.py`, `find-prop-default.py`,
  `find-app-dirs.py`, `find-esper.py`, `find-esper-apks.py`). Regenerate
  with `dump-range.ps1` if needed.
- `firmware/scratch/` — gitignored. Everything else: dump output,
  verification reads, one-off probes.
