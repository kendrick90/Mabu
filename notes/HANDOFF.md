# Mabu/Esper tablet unlock — agent handoff

Long-running project. Goal: **remove Esper Device Owner kiosk from an
RK3288 Android 8.1 tablet** so the user can repurpose it.

## Latest status (2026-05-25 session 2)

**Esper kiosk neutralized.** Device now boots to normal Android home
screen (no Esper UI, no kiosk lock). What worked:

1. dm-verity disabled via parameter file patch (from session 1)
2. Located all three Esper APKs on /system/app via ext4 walking
   (espersupervisor inode 441, esperdpc inode 475, esperhelper inode 518)
3. Corrupted the EOCD signature (`PK\x05\x06` → `\x00\x00\x00\x00`) of
   each APK's zip via single-sector writes (4 bytes each, see
   `scripts/find-eocd.py` / dumps/*-apk-eocd-patched.bin). PackageManager
   skips these on next scan.
4. Wiped userdata partition head (first 256 MB) via Loader — forced vold
   to reformat /data. Original 16 MB snapshot preserved at
   dumps/userdata-original-head.bin in case revert needed.
5. After reset, USB enumerated as PID 0x0011 (standard Rockchip MTP+ADB,
   was 0x0006 under Esper). Home screen visible, no kiosk.

**Resolved:** ADB shell now works unconditionally. Root cause was NOT
ro.adb.secure — adbd on this Rockchip 8.1 build has the `auth_required`
global hardcoded to `1` in its .data section (Rockchip's adbd seems to
ignore `ro.adb.secure`). One-byte patch at file_off 0xD311C of
/system/bin/adbd flips it to `0`. We also patched adbd_auth_init to
return immediately (defense in depth). Loader-side sectors written:

  abs LBA 1,696,240 (one byte at offset 284 in sector: 0x01 -> 0x00)
  abs LBA 1,694,778 (two bytes at offset 56-57: F0 B5 -> 70 47 / BX LR)

`adb devices` returns `device` immediately on next boot — no dialog,
no host-key approval needed.

**Settings.apk crash explained:** narrowed to Developer Options sub-page
only. `DevelopmentSettings.onResume() -> SystemProperties.set(...)` fails
because SELinux denies system_app write to `logpersistd_logging_prop`.
Settings doesn't handle the failure -> uncaught RuntimeException.

We tried patching `/system/etc/selinux/plat_property_contexts` to change
the type to `log_prop` (sectors at abs LBA 2076322 and 2076324). It had
no effect because **Android 8.1 uses the precompiled binary policy at
`/vendor/etc/selinux/precompiled_sepolicy`** at runtime, not the text
file. The text file is just one of several inputs compiled into the
binary at build time. Patches reverted; left as a finding for future
attempts. To actually fix this you'd need to either:
  - Patch the precompiled binary (CIL/SEPolicy blob — fragile)
  - Patch Settings.apk's classes.dex to skip setLogpersistOff
  - Install a third-party Activity Launcher app to bypass the crashing
    sub-page

Practical answer: avoid the Dev Options sub-page on the tablet UI; use
ADB shell for everything Dev Options would do (logcat config, animation
scale, USB tethering, etc.).

**WiFi ADB is on.** `service.adb.tcp.port=5555` is in /vendor/build.prop
and adbd's auth_required is 0 (our patch), so on every boot you can
`adb connect <tablet-ip>:5555` from any host on the LAN without USB
and without an approval dialog. The tablet IP is DHCP — set a static
lease for it on your router if you want a stable address.

## Two distinct Mabu-unit states we've seen

There are two starting states for a Mabu unit, and they need slightly
different treatment:

**A) Active Esper-managed (kiosk visible, Esper backend tried to run):**
  - /data/system/device_policies.xml contains both the Device Owner ref
    AND kiosk policies (lock task, app whitelist, etc.)
  - Even with Esper APKs corrupted, DPM tries to enforce those policies
  - To get a usable tablet you MUST wipe /data (we did 256-320 MB of
    Loader-side zeroing on the first unit). Dev Options may regress
    due to vold's incomplete reformat.

**B) Factory-reset Esper-managed (no kiosk shown, but DO still set):**
  - /data is clean: kiosk policies wiped by factory reset
  - The Device Owner reference persists across factory reset (FRP-like)
    but with no policies attached, it doesn't enforce anything
  - Esper APK corruption + adbd patches alone is enough — NO /data wipe
  - Dev Options works on-device UI (because /data is in pristine
    factory-reset state)
  - USB ADB still wedges (Esper's stale DPM service keeps trying to
    reach the dead admin) -- USE WIFI ADB instead

For both states, the v2 liberate-mabu.ps1 (parameter + adbd patches)
gives you ADB. WiFi ADB is the most reliable transport on either, since
TCP isn't subject to whatever USB-layer game the lingering Esper code
plays. State (B) is essentially done once liberate-mabu has run plus
Esper APK EOCDs are nuked.

## V3 Liberation procedure (current best-practice, validated)

Wipe size matters. Two passes on first unit (256 + 320 MB) left Dev
Options crashing. The second unit (96 MB before Loader wedged) ended up
with a fully working Dev Options + cleared Device Owner. So aim small.

Full procedure for a NEXT Mabu unit (do these steps in order):

1. **Catch Loader** (PID 0x320A on power-on, hold the recovery button
   if the boot is too fast). USB connected.

2. **Apply core patches:**
     .\scripts\liberate-mabu.ps1
   This writes the parameter file (verity off + selinux permissive) and
   the two adbd patches (auth_required = 0 and adbd_auth_init -> BX LR).

3. **BACK UP MABU SOFTWARE if this is an unwiped (Esper-active) unit.**
   This is the hardest step. Esper actively wedges USB ADB within ~5
   seconds of boot, so you have a narrow window. Three strategies, in
   ascending complexity, try in order:

   3a. **Race against the wedge.** After 'rkdeveloptool rd', the device
       boots in ~30s. As soon as 'adb devices' shows 'device', fire one
       compound command:

         adb -s <serial> shell "
           am force-stop io.shoonya.shoonyadpc;
           am force-stop io.shoonya.shoonyahelper;
           am force-stop io.shoonya.shoonyasupervisor;
           pm disable-user --user 0 io.shoonya.shoonyadpc;
           pm disable-user --user 0 io.shoonya.shoonyahelper;
           pm disable-user --user 0 io.shoonya.shoonyasupervisor;
           tar cf /sdcard/mabu.tar /data/data/com.catalia.* /data/app/com.catalia.* 2>/dev/null;
           ls -la /sdcard/mabu.tar
         "
         adb -s <serial> pull /sdcard/mabu.tar ./mabu-<serial>.tar

       The pm disable-user calls might fail if Esper has anti-disable
       restrictions, but force-stop usually wins long enough for the
       tar. If USB drops mid-pull, retry from the next strategy.

   3b. **WiFi ADB.** Esper-managed Mabus had to be provisioned via
       network, so WiFi credentials are persisted in /data and auto-
       connect at boot. Find the tablet's IP (router DHCP table, or
       'nmap -p 5555 192.168.x.0/24'), then:
         adb connect <ip>:5555
       service.adb.tcp.port=5555 is set in /vendor/build.prop and our
       adbd-patched binary listens on 5555 from boot. WiFi transport
       isn't subject to Esper's USB shenanigans (proven on second unit).

   3c. **Selective Esper neutralization first.** Before extracting Mabu,
       go back to Loader and corrupt JUST esperhelper.apk EOCD (LBA
       2063565). Theory: 'helper' is the USB sentry. With it dead and
       esperdpc + espersupervisor still alive, the kiosk UI keeps Mabu
       accessible but USB ADB stays stable. UNTESTED -- the next fresh
       Mabu is the validation opportunity.

   If 'pm list packages | grep -i catalia' returns empty: the unit
   was already factory-reset, Mabu app is gone, skip this step
   entirely.

4. **Catch Loader again** (power-cycle and re-catch).

5. **Corrupt Esper APK EOCDs** (kills Esper's three packages):
     wl 1851238 dumps/espersupervisor-apk-eocd-patched.bin
     wl 1981802 dumps/esperdpc-apk-eocd-patched.bin
     wl 2063565 dumps/esperhelper-apk-eocd-patched.bin

6. **Wipe ~96 MB of /data** (clears Device Owner reference, triggers
   fresh vold reformat without breaking Dev Options):
     .\scripts\wipe-data-head.ps1 -SizeMB 96 -Reset

7. **Wait for boot.** First boot reformats /data, takes ~30-60s longer
   than usual. 'adb devices' should show 'device' immediately. USB
   transport now stable (Esper DPM ghost gone).

8. **Install a launcher** (none on stock /system):
     adb install apks/F-Droid.apk
     adb install apks/KISS.apk
     adb shell cmd package set-home-activity fr.neamar.kiss/.MainActivity
     adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
   From the tablet UI you can now install anything else (browser, etc)
   via F-Droid.

## Original V2 procedure (for reference)

Critical insight: **don't wipe /data, don't corrupt Esper APKs until
AFTER you have the Mabu software backed up.** The original procedure
(corrupt APKs + wipe /data) destroyed the Mabu app on this unit. For
future units:

1. Catch Rockchip Loader (PID 0x320A) on boot.
2. Run `scripts/liberate-mabu.ps1 -Reset`. This applies only the
   parameter patch + the two adbd patches.
3. Wait for Android to boot. `adb devices` shows `device` instantly.
4. Backup Mabu software:
     adb shell tar cf /sdcard/mabu-backup.tar /data/data/com.catalia.* \
       /data/app/com.catalia.* /data/misc/sounds 2>/dev/null
     adb pull /sdcard/mabu-backup.tar ./mabu-<serial>-backup.tar
5. (Optional) Now do the destructive de-Esper steps on this unit:
   - Corrupt Esper APK EOCDs (use dumps/*-apk-eocd-patched.bin)
   - Wipe /data head 256 MB (use dumps/zeros-16mb.bin x16)
   - Reset; device boots to vanilla Android with no kiosk.

**Useful infrastructure built this session:**
- `scripts/dump-range.ps1` — range-aware partition dump with per-chunk
  timeout (kills wedged Loader sessions in 45s instead of hanging)
- `scripts/find-prop-default.py` — walks ext4 using inode tables
  (flex_bg packs all 16 inode tables in first ~34 MB of /system; we have
  them all in system.img) to locate any /system/etc/* file
- `scripts/find-esper.py` — lists /system/app and /system/priv-app
- `scripts/patch-prop-sector.py` — built ro.adb.secure=0 sector write
  (took effect on disk; doesn't help at runtime because adbd reads
  ro.adb.secure once at startup and the prop is being set to 1 earlier
  by some other source)

---

## Original session notes follow

We've made significant progress; one final step is blocked.

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
