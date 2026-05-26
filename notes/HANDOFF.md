# Mabu/Esper tablet unlock — agent handoff

Long-running project. Goal: **remove Esper Device Owner kiosk from an
RK3288 Android 8.1 tablet** so the user can repurpose it. We've made
significant progress; one final step is blocked.

---

## What we've established (don't re-discover)

### Hardware

- **Device**: Mabu robot tablet (Catalia Health, defunct ~2020-2022).
  Board ID `HRA7_RK3288W_V1.2_2021.10.15`.
- **SoC**: Rockchip **RK3288** (Cortex-A17 quad, confirmed via 320A PID
  and chip-info readback).
- **eMMC**: Samsung 16 GB BGA on a Foresee-branded package
  (`NCEMAD9D-16G`, 14800 MB user area, 30,310,400 sectors of 512 B,
  block 512 KB, page 2 KB).
- **OS**: Android 8.1.0, security patch level 2018-09-05, Esper-managed
  Device Owner mode (kiosk shell, hidden system dialogs, dead MDM
  tenant).
- **Wiring**: USB OTG broken out via a 30-pin header on the main PCB.
  D+/D- polarity was the wiring gotcha to remember (swap fixes
  "device descriptor request failed").

### Software tooling, in place at `tools/`

- `tools/rkdeveloptool/rkdeveloptool.exe` — cpebit's Windows build
  (libusb-based; works only when device is bound to WinUSB)
- `tools/rockchip-stock/DriverAssitant_v5.0/` — official Rockchip
  USB driver bundle (installs `rockusb.sys`)
- `tools/rockchip-stock/RKDevTool_Release_v2.92/` — Rockchip GUI
  (works with rockusb.sys binding, doesn't with WinUSB)
- `tools/google-usb-driver/` — patched Google ADB driver for VID 2207
- `tools/testkey/` — AOSP testkey.x509.pem + testkey.pk8 (not used in
  the current path; was for failed sideload sig attempt)
- Python 3.13 with `cryptography`, `pyusb`, `libusb-package`, `zeroconf`
- adb / fastboot in `%LOCALAPPDATA%\Microsoft\WinGet\Packages\Google.PlatformTools_*`
- Zadig (winget id `akeo.ie.Zadig`)

### Critical technical findings

**1. Rockchip Loader window:** during normal boot, u-boot exposes
PID 0x320A on USB for ~10 seconds. Any rockusb command latches it
into Loader mode indefinitely.

**2. Driver chain:** WinUSB binding is needed for `rkdeveloptool` CLI
control. The Rockchip-bundled `rockusb.sys` (installed by
DriverInstall.exe in DriverAssistant) gives RKDevTool GUI access only.
We Zadig-bound WinUSB to PID 320A; that path is current.

**3. Partition layout** (from parameter file at sector 0):

| Partition  | Start LBA  | Size LBA   | Size     |
|------------|-----------:|-----------:|---------:|
| parameter  | 0x00000000 | 16         | 8 KB     |
| uboot      | 0x00002000 | 0x00002000 | 4 MB     |
| trust      | 0x00004000 | 0x00002000 | 4 MB     |
| misc       | 0x00006000 | 0x00002000 | 4 MB     |
| resource   | 0x00008000 | 0x00008000 | 16 MB    |
| kernel     | 0x00010000 | 0x00010000 | 32 MB    |
| boot       | 0x00020000 | 0x00010000 | 32 MB    |
| recovery   | 0x00030000 | 0x00020000 | 64 MB    |
| backup     | 0x00050000 | 0x00038000 | 112 MB   |
| security   | 0x00088000 | 0x00002000 | 4 MB     |
| cache      | 0x0008A000 | 0x00100000 | 512 MB   |
| **system** | **0x0018A000** | **0x00400000** | **2 GB** |
| metadata   | 0x0058A000 | 0x00008000 | 16 MB    |
| vendor     | 0x00592000 | 0x00080000 | 256 MB   |
| oem        | 0x00612000 | 0x00080000 | 256 MB   |
| frp        | 0x00692000 | 0x00000400 | 512 KB   |
| userdata   | 0x00692400 | rest       | ~11.2 GB |

**4. Boot.img patches DON'T WORK.** Tested two variants:
  - Re-patched with new AOSP-style SHA-1 in id field → tablet boots
    only to recovery
  - Re-patched with original id field preserved byte-for-byte → also
    boots only to recovery
  Conclusion: either Rockchip's u-boot does verification beyond the
  boot.img header id field that we haven't found, OR our cpio rewrite
  is subtly corrupt (changing /default.prop from symlink to regular
  file). Either way, boot.img path is currently blocked.

**5. PARAMETER FILE PATCH WORKS!** ⭐ Modified the cmdline in the
parameter file at sector 0 to add `androidboot.veritymode=disabled`
and `androidboot.selinux=permissive`, recomputed the **Rockchip
custom CRC32** (polynomial **`0x04C10DB7`**, init=0, no reflection,
no final xor — one bit different from IEEE 802.3), wrote back via
`rkdeveloptool wl 0 parameter-patched.img`, sent reset, and the tablet
**booted normally to main Android** with both flags active.
  - Patched parameter file is at `dumps/parameter-patched.img`
  - Patcher: `scripts/patch-parameter.py`
  - **dm-verity is now disabled on this device.** We can modify
    `/system` content and the kernel will not refuse to mount it.

**6. ADB current state** (post-parameter-patch): tablet boots to main
Android, enumerates as `VID 2207 PID 0006 H7R 2022010502079`. adb
shows the device as `offline` (wedged handshake we've seen
throughout). The Esper kiosk still suppresses the auth dialog.

---

## Current blocker: dumping /system is unreliable

Plan was: dump system.img (2 GB at sector 0x18A000) → byte-replace
`ro.adb.secure=1` → `0` and similar → write back → reboot → unauth ADB
works because verity is off.

**The Loader gets wedged during long reads.** We've observed:
- ~32 MB single-shot reads → fail at 2%
- 4 MB chunked reads with `catch-loader-and-dump.ps1` → worked for
  boot (32 MB), recovery (64 MB), misc (4 MB)
- But the system dump (2 GB) hung at **35,717,120 bytes (1.7%)** —
  somewhere between chunks 8 and 9. rkdeveloptool process stuck
  holding the USB device.

**Hypothesis:** u-boot Loader has either a session byte-limit or a
session time-limit. Once exceeded, it stops responding. To recover,
we power-cycle the tablet (force off via PWRON >7s, then power back
on) and re-catch the Loader window.

---

## Files / scripts on disk

- `scripts/patch-bootimg.py` — boot.img unpack/repack (currently
  blocked, see above; supports `--keep-id`)
- `scripts/patch-parameter.py` — parameter file unpack/repack with
  correct Rockchip CRC32 (**works**)
- `scripts/patch-system-props.py` — binary search-and-replace for
  prop values in a system.img dump (ready to use once we have a
  full dump)
- `scripts/catch-loader-and-dump.ps1` — polls for Loader, locks in,
  does chunked partition dumps
- `scripts/write-patched-boot.ps1` — abandoned (boot.img path dead)
- `scripts/restore-boot.ps1` — emergency restore if a patch bricks
  boot (writes back original boot.img + clears misc)
- `scripts/roundtrip-test.py` — diagnostic for cpio/boot.img
  round-trip (revealed the SHA-1 mismatch)
- `scripts/find-hash-formula.py` — checked 14 SHA-1 variants against
  boot.img id field, none matched
- `scripts/rockusb.py` / `scripts/rockusb_winusb.py` — pure-Python
  rockusb clients (mostly dead-end work; recovery's MI_01 is not
  rockusb)
- `dumps/` — current working dumps (boot.img, recovery.img, misc.img,
  parameter.img, parameter-patched.img, partial system.img at 35 MB)
- `dumps-tablet-reset/` — backup dumps from the OTHER reset tablet
  (identical boot.img, kept just in case)

---

## Strategic options for next agent

In rough order of effort:

### Option A (smartest) — only dump what we need, not all 2 GB

The text we need to modify is in `/system/build.prop` and
`/system/etc/prop.default` — total a few KB. Rather than dumping the
whole 2 GB partition:

1. Dump the first ~256 MB of system in 4 MB chunks. Build.prop and
   prop.default are almost certainly in the first 100 MB of an ext4
   filesystem (small files, allocated early).
2. Search the partial dump for `ro.adb.secure=1` and friends.
3. Note their **exact byte offsets within the partition**.
4. Issue per-offset writes: write a few bytes back via
   `rkdeveloptool wl <lba> <small-file>` where the small file is a
   block that fills sectors containing the prop strings with the
   modified value.

This avoids the 2 GB dump problem entirely. **Strongly recommended.**

### Option B — chunk dump but stop and restart Loader sessions

Modify `catch-loader-and-dump.ps1` to:
- Dump 5–10 chunks (20–40 MB)
- Then issue `rkdeveloptool rd` (resets device to boot)
- Wait for Loader window again
- Resume from the next chunk

Tedious but each session stays under the wedge threshold. The user
would have to power-cycle between sessions.

### Option C — investigate kernel partition

There's a `kernel` partition at sector 0x10000, 32 MB, that we never
dumped. Possibility: u-boot loads the actual booting kernel/ramdisk
from there, not from `boot`. That would explain why our boot.img
patches had no effect. **Worth a 32 MB dump just to know.**

### Option D — userdata adbkey injection

With dm-verity disabled, we can modify /system. But ANOTHER avenue:
add the host's `~/.android/adbkey.pub` to `/data/misc/adb/adb_keys`.
This bypasses the auth dialog because the host's key is then "already
trusted". Requires:
- Checking whether userdata uses FBE (File-Based Encryption). If yes,
  we can't write valid encrypted blocks without the device's key.
  Check by reading the ext4 superblock at sector 0x692400 + 2 (i.e.
  superblock is at byte 1024 of the partition) and looking for the
  encrypt feature flag.
- If no FBE: find the existing `/data/misc/adb/adb_keys` file's
  inode, append our key, write back. Tricky if it doesn't exist
  yet (have to create new inode + extent + dir entry).

---

## Steps to resume

1. **Kill any lingering rkdeveloptool/powershell processes** holding
   the USB device.
2. **User power-cycles the tablet** (force off, then on). USB OTG
   stays plugged.
3. Run `scripts/catch-loader-and-dump.ps1 -Names <partition>` to
   catch Loader and dump.
4. For full /system: pursue Option A (search the first ~256 MB for
   prop strings, then targeted byte writes).

---

## Things known to be working

- ✅ rkdeveloptool CLI talks to Loader (PID 320A) via WinUSB binding
- ✅ Parameter file patching with custom Rockchip CRC32
- ✅ Reads of up to ~64 MB partitions complete reliably
- ✅ dm-verity is disabled on the live device (parameter patch took)

## Things known to be broken

- ❌ Boot.img repacking — bootloader rejects our images even with
  original id preserved
- ❌ Long single rockusb sessions (>30s or >100 MB ish) — Loader
  wedges, requires power-cycle

## User preferences captured

- Wants visible terminal windows for heavy ops (so they can see what's
  happening). For state checks, the PowerShell tool's output is fine.
- ASCII-only in `.ps1` files (Windows PowerShell 5.1 reads BOM-less
  UTF-8 as Windows-1252 and chokes on em-dashes etc. We have a saved
  memory at `~/.claude/projects/.../feedback_powershell_ascii.md`).
- Stop suggesting password guesses on lockout-protected systems —
  we have a saved memory on this from the Esper admin password
  incident.
