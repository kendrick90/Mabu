# firmware/ — captured + modified eMMC bytes

All of the firmware-derived binaries used by the liberation scripts.
Split by purpose so it's obvious what's committed (and why), what's
local-only, and what each file does.

Subdirectories use kebab-case so they sort sensibly.

## `patches/` — committed

Small modified firmware blobs that the liberation scripts write to the
eMMC. Committed because the scripts can't run without them. These
contain edited firmware bytes specific to the H7R Mabu build — same
copyright stance as committing the Catalia and Esper APKs under
`../mabu-archive/`.

| File | Size | Written to | Source |
|---|---|---|---|
| `parameter-patched.img` | 8 KB | LBA 0 (parameter partition) | `patch-parameter.py` applied to `originals/parameter.img` |
| `adbd-authreq-patched.bin` | 512 B | LBA 1,696,240 | Byte 284 flipped 0x01 → 0x00 (`auth_required = 0`) |
| `adbd-authinit-patched.bin` | 512 B | LBA 1,694,778 | Bytes 56-57: F0 B5 → 70 47 (`adbd_auth_init` returns immediately) |
| `espersupervisor-apk-eocd-patched.bin` | 512 B | LBA 1,851,238 | EOCD signature zeroed |
| `esperdpc-apk-eocd-patched.bin` | 512 B | LBA 1,981,802 | EOCD signature zeroed |
| `esperhelper-apk-eocd-patched.bin` | 512 B | LBA 2,063,565 | EOCD signature zeroed |
| `zeros-4k.bin` | 4 KB | one ext4 block | Plain NULs (overwrites `init.esper.rc` and `set-device-owner.sh` data blocks) |
| `zeros-16mb.bin` | 16 MB | /data head, 6× | Plain NULs (corrupts ext4 superblock so vold reformats) |

## `originals/` — committed

Baseline captures from the device. Used by:
- `restore-adb-auth.ps1` (the two `adbd-*-orig.bin`)
- `restore-boot.ps1` (`boot.img`)
- `patch-parameter.py` as input (`parameter.img`)
- Reference / verification anywhere else

| File | Size | Notes |
|---|---|---|
| `parameter.img` | 8 KB | Original parameter partition. Source for `patch-parameter.py` |
| `adbd-authreq-orig.bin` | 512 B | Original LBA 1,696,240 — restored by `restore-adb-auth.ps1` |
| `adbd-authinit-orig.bin` | 512 B | Original LBA 1,694,778 — restored by `restore-adb-auth.ps1` |
| `adbd.bin` | 1.1 MB | Full `/system/bin/adbd` extracted from a unit. Useful for further patching investigations |
| `esperdpc-apk-eocd-original.bin` | 512 B | Original EOCD sector for esperdpc.apk |
| `esperhelper-apk-eocd-original.bin` | 512 B | Original EOCD sector for esperhelper.apk |
| `espersupervisor-apk-eocd-original.bin` | 512 B | Original EOCD sector for espersupervisor.apk |
| `boot.img` | 32 MB | Original boot partition (used by `restore-boot.ps1`) |

(`recovery.img` and `misc.img` are kept in `scratch/` since no current
script needs them; re-dump fresh from a unit via `dump-partitions.ps1`
if a future investigation requires them.)

## `system-probes/` — gitignored

Partial dumps of /system used by the ext4 inode walkers in `scripts/`.
Each is ~10–35 MB; too large to commit and not strictly required (can be
regenerated with `dump-range.ps1` against a unit in Loader if needed).

| File | Approx size | Covers |
|---|---|---|
| `system.img` | 35 MB | First 35 MB of /system — inode tables (flex_bg packs them all here) |
| `system-etc-combined.img` | 26 MB | Partition bytes 220–246 MiB — `/etc/init/` directory + data blocks |
| `system-etc-region.img` | 16 MB | Earlier /etc region capture |
| `system-etc-below.img` | 10 MB | Earlier /etc region capture |
| `system-app-region.img` | 12 MB | /system/app data blocks |
| `system-privapp-region.img` | 16 MB | /system/priv-app data blocks |
| `system-bin-head.img` | 4 MB | Head of /system/bin |
| `vendor-head.img` | 16 MB | Head of /vendor |

## `scratch/` — gitignored

Everything else: one-off probes, verification reads, dump output,
intermediate files. None of it is referenced by current scripts; future
scripts write here by default. Safe to delete the whole subdirectory
at any time.
