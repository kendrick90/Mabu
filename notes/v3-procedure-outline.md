# V3 procedure outline — pre-wipe data capture + root

Status: **DESIGN DRAFT, NOT BUILT.** Don't run this; use the V2 procedure
(`flash-mabu.ps1`) until V3 lands. This document captures what we *want*
to do for the next fresh Esper Mabu that arrives, learned from
liberating five units so far.

## Why a new procedure

The V2 flow (`flash-mabu.ps1 -WipeData -RestoreMabu`) successfully
liberates a unit but destroys two things in the process:

1. **Per-unit motor calibration** — lives in
   `/data/data/com.catalia.factorymode/`, owner-only (uid of the
   factorymode app), unreadable by shell. Wiped by the 96 MB /data
   head-zero. Re-derived in minutes via factorymode's calibration
   wizard, but it'd be nicer to preserve the original.

2. **Anything else interesting in /data** — provisioning tokens,
   account references, sync state, Esper-installed APKs (we have these
   in `mabu-archive/` from unit 3, but each new unit may have a slightly
   different version we'd want to capture).

We have unfettered Loader access (any rockusb command works), but
**/data is FDE-encrypted** (`ro.crypto.type=block`). Raw Loader-side
reads of the userdata partition return ciphertext. Decryption only
happens at runtime, mounted via dm-crypt at `/dev/block/dm-0`. So
**all data capture must happen at runtime, in Android, with the
device mounted normally**.

Combined with the fact that most of /data is owner-only, we need
either:
- (a) Run an extraction-helper APK as `system_app` with broad SELinux
  permissions, OR
- (b) Get **root** on the running device (via Magisk or similar) and
  use `su` to read everything.

(b) is the simpler, well-trodden path on RK3288 Android 8.1, and gives
us a permanent dev tool. Boot.img patching with Magisk preserves the
SHA-1 / signing structure correctly (where our hand-rolled boot.img
patches were rejected by u-boot in earlier sessions).

## Step-by-step

```
0. PREP HOST
   • flash-mabu.ps1, liberate-mabu.ps1, etc. already in repo
   • Boot.img-Magisk-patched (per-build, not per-unit) committed at
     firmware/patches/boot-magisk.img  (TBD: generate once and commit)
   • Empty pre-wipe-archive/ directory ready to receive per-unit captures

1. ENTER LOADER
   • Connect USB harness
   • Press recovery button combo on power-on (vol-up + power on RK3288;
     verify per board variant) — device enters recovery, which exposes
     the rockusb interface at PID 0x320A
   • Host polls `rkdeveloptool ld` until Loader appears
   • This trigger is more reliable than racing the ~10s u-boot window

2. NON-DESTRUCTIVE PATCHES ONLY (no /data wipe yet)
   • Write parameter-patched.img (verity off, selinux permissive)
   • Write the two adbd patches (auth_required=0, adbd_auth_init=BX LR)
   • DO NOT write the three Esper APK EOCD nukes yet — those break the
     Esper /data DPC's view of /system, which could destabilize the
     unit before we've finished extraction
   • DO NOT zero init.esper.rc / set-device-owner.sh yet either —
     same reason
   • rkdeveloptool rd — device boots back to Esper kiosk, but with
     ADB now wide open (no auth dialog)

3. PRE-WIPE CAPTURE — SHELL UID (works without root)
   • Create a per-unit dir: pre-wipe-archive/unit-<serial>/
   • adb pull /data/app/io.shoonya.*-*/base.apk          (DPC)
   • adb pull /data/app/io.esper.*-*/base.apk            (remote viewer, OTA mgr)
   • adb pull /data/app/com.catalia.*-*/base.apk         (factorymode etc.)
   • adb pull /data/app/org.opencv.engine-*/base.apk     (OpenCV Manager)
   • adb shell tar cf /sdcard/sdcard.tar -C / sdcard 2>/dev/null
   • adb pull /sdcard/sdcard.tar
   • adb shell dumpsys package > pkg-list.txt            (full pkg DB)
   • adb shell dumpsys device_policy > device_policy.txt (DPM state pre-wipe)
   • adb shell getprop > getprop.txt                     (all props)
   • adb shell pm list packages -f > pm-list.txt
   • For each /data/app/*/lib/<abi>/*.so: adb pull (native libs)
   • Document the captured calibration is NOT yet here (need root)

4. ROOT VIA MAGISK
   • On host: dump boot.img to firmware/scratch/boot-<serial>.img
       rkdeveloptool wl  ← wait, we need to be in Loader. So:
       adb -s <serial> shell reboot loader
       rkdeveloptool rl 0x20000 0x10000 firmware/scratch/boot-<serial>.img
   • Push boot.img to a Magisk Manager APK installation on the device,
     have it produce magisk_patched.img
       (or: do the patch offline with `magiskboot` CLI on host)
   • Write magisk_patched.img back to boot partition:
       rkdeveloptool wl 0x20000 magisk_patched.img
   • rkdeveloptool rd — reboot
   • Verify: adb shell su -c id

5. PRE-WIPE CAPTURE — ROOT UID (the calibration + private dirs)
   • adb shell su -c "tar cf /sdcard/data-system.tar /data/system /data/system_ce /data/system_de" 2>/dev/null
   • adb shell su -c "tar cf /sdcard/data-data.tar /data/data" 2>/dev/null
   • adb shell su -c "chown shell:shell /sdcard/*.tar"
   • adb pull /sdcard/data-system.tar
   • adb pull /sdcard/data-data.tar
   • CALIBRATION specifically:
       adb pull /data/data/com.catalia.factorymode/  (root needed)
   • Optional: snapshot of /system, /vendor, /oem (all RO, all
     byte-identical across units of same build, but capture for
     audit trail)

6. DESTRUCTIVE LIBERATION (V2 path, now)
   • Reboot to Loader
   • Apply the three Esper APK EOCD nukes
   • Apply init.esper.rc + set-device-owner.sh zeros
   • Wipe /data head 96 MB
   • Reset

7. FINISH (same as V2)
   • Wait for boot, set up WiFi via touch UI
   • Install F-Droid, Lawnchair, factorymode, OpenCV Manager
   • Push animation CSVs, nuance assets, sound.raw

8. RESTORE PER-UNIT CALIBRATION (optional)
   • If we successfully captured /data/data/com.catalia.factorymode in step 5,
     we can restore it now:
       adb root  # via Magisk
       adb push pre-wipe-archive/unit-<serial>/data-data/com.catalia.factorymode /sdcard/
       adb shell su -c "cp -r /sdcard/com.catalia.factorymode/* /data/data/com.catalia.factorymode/"
       adb shell su -c "chown -R u0_a55:u0_a55 /data/data/com.catalia.factorymode"  # uid varies
   • Skipping this just means running the calibration wizard on first
     boot — a few minutes, no big deal

9. SHIP-MODE LOCKDOWN (optional)
   • Reboot to Loader
   • restore-adb-auth.ps1 -Reset
   • Standard Android auth dialog returns

## Open questions

- **Recovery button combo on Mabu**: vol-up + power is the Rockchip
  default but we need to verify the exact gesture on the Mabu body
  before formalizing. The handoff hasn't recorded this; user found it
  empirically on unit 5.
- **Boot.img Magisk patch**: build once per /system fingerprint
  (all five Mabus so far are the same fingerprint). Commit the patched
  image to `firmware/patches/boot-magisk.img`. Generating it requires:
    - magiskboot tool (extract from Magisk-Latest.apk)
    - Original boot.img → magiskboot unpack → patch ramdisk → repack
  Need to verify the patched image passes u-boot's signature check on
  RK3288 (previous hand-rolled boot.img patches did NOT — see
  `notes/HANDOFF.md`'s "boot.img patches DON'T WORK" finding. But
  Magisk's tooling preserves the AVB structure properly.)
- **Calibration restore**: the factorymode app's data dir has owner-uid
  randomized per install. After /data wipe and reinstall, the uid
  changes, so we can't just chown the restored files to the old uid.
  Need to discover the new uid post-install and chown to it.

## Why we're punting V3 to "next fresh unit"

We have five working liberated Mabus already. V3 is a build-and-test
project that needs a fresh Esper unit as the target. Don't risk
unrolling a working unit just to validate V3.

When the next Esper unit arrives:
1. Check unit-N-pristine snapshot capability against this outline
2. Build the magiskboot toolchain on host
3. Run V3 end-to-end
4. Compare extracted data to anything we already have in
   mabu-archive/unit-2022010501476/ — confirm we're not losing
   anything we used to have
5. If V3 works, promote it from this outline to a real
   `scripts/flash-mabu-v3.ps1`
