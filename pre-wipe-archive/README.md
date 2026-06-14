# pre-wipe-archive/

Per-unit captures taken BEFORE the destructive /data wipe in the V3
flash procedure. Each subdirectory is named by the unit's serial
(`ro.serialno`) and contains:

- `apks/` — every 3rd-party APK that was in /data/app at capture time.
  Includes Esper-deployed packages (`io.shoonya.*`, `io.esper.*`),
  Catalia packages (`com.catalia.*`), and any OpenCV Manager that
  came with the unit.
- `sdcard.tar` — full contents of /sdcard at capture time.
- `factorymode-data.tar` — `/data/data/com.catalia.factorymode/`,
  contains the per-unit motor calibration. **Requires root** so this
  file only exists when V3's Magisk-rooting step succeeded.
- `data-system.tar` — `/data/system/` (DPM state, accounts, settings).
  Root-required.
- `dumpsys-package.txt`, `dumpsys-device_policy.txt`, `pm-list.txt`,
  `getprop.txt` — runtime state for forensic reference.
- `boot.img`, `recovery.img`, `misc.img`, `parameter.img` — partition
  snapshots from Loader. Mostly redundant across units of the same
  /system fingerprint, but kept per-unit for audit trail.

This directory is gitignored by default; commit selectively if a unit
has unique content worth preserving in the repo.
