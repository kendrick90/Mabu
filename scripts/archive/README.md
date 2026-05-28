# Archived scripts (dead-end approaches)

These were attempts that did not pan out, kept here so a future agent
doesn't re-investigate from scratch. None of them are in the current
liberation procedure (see `../README.md`).

| Script | What it was trying to do | Why archived |
|---|---|---|
| `patch-bootimg.py` | Repack boot.img with modified ramdisk so /default.prop disables ADB auth and sets selinux=permissive | Rockchip u-boot rejects every variant we built. With and without preserving the original SHA-1 id field, device only boots into recovery. Verification beyond the documented Android boot.img header is happening somewhere we never identified. **Use the parameter-file patch (`patch-parameter.py`) instead — it works.** |
| `write-patched-boot.ps1` | Companion to patch-bootimg.py — writes the patched boot.img to /boot via Loader | Same boot.img path failure. |
| `find-hash-formula.py` | Brute-force search across 14 SHA-1 variants to see if any matched the boot.img id field | None matched. The id field probably uses an undocumented Rockchip extension we never reverse-engineered. |
| `roundtrip-test.py` | Diagnostic showing the boot.img unpack→repack mutates the cpio archive | Confirmed the cpio rewrite was lossless byte-wise but the bootloader still rejected it. Diagnostic only. |
| `dump-all.py` | Pure-Python rockusb client to do an in-process /system dump | libusb "Pipe error" on first CBW write to the bulk OUT endpoint, never resolved. Possibly a WinUSB binding quirk on the FF/06/05 interface descriptor. The cycled rkdeveloptool approach (`../dump-system-cycled.ps1`) is the working alternative. |
| `rockusb.py`, `rockusb_winusb.py` | Earlier pyusb experiments | Same OUT-endpoint stall as `dump-all.py`. |
| `build-misc-bcb.py` | Construct Android Bootloader Control Block to boot into recovery sideload | Rockchip ignores standard BCB format on this build. No way to script-trigger recovery sideload. |
| `build-adbkey-payload.ps1` | Inject the host's adbkey.pub into /data/misc/adb/adb_keys via Loader | Would have required either FBE key access (we don't have it) or a fresh /data ext4 we built ourselves. Replaced by patching adbd itself to skip auth (`liberate-mabu.ps1` writes that patch). |
| `sign-ota.py` | Sign a custom OTA package with the AOSP testkey for sideload | The stock recovery on this device rejects testkey-signed packages. Path abandoned. |
| `patch-prop-sector.py` | Targeted in-place edit of `ro.adb.secure=1 → 0` inside /system/etc/prop.default via byte-level sector write | Wrote correctly, but adbd reads `ro.adb.secure` once at startup and an earlier setter (somewhere in init) re-sets it to 1 before adbd reads it. Replaced by patching adbd's compiled `auth_required` global directly. |
| `patch-system-props.py` | Generalized prop search-and-replace inside a system.img dump for later writeback | Never used in anger because the prop approach turned out not to fix ADB auth (see `patch-prop-sector.py` above). Kept as reference. |
