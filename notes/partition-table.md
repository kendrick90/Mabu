# Partition table (second tablet, HRA7_RK3288W)

Extracted from the Rockchip parameter file at sector 0 via
`rkdeveloptool rl 0 8 sectors-0-7.bin` then ASCII-decoded.

## Raw parameter file content

```
FIRMWARE_VER:7.1
MACHINE_MODEL:RK3288
MACHINE_ID:007
MANUFACTURER:rk3288
MAGIC: 0x5041524B
ATAG: 0x00200800
MACHINE: 3288
CHECK_MASK: 0x80
PWR_HLD: 0,0,A,0,1
CMDLINE: console=ttyFIQ0
         androidboot.baseband=N/A
         androidboot.veritymode=enforcing       <- dm-verity ON
         androidboot.hardware=rk30board
         androidboot.console=ttyFIQ0
         init=/init
         initrd=0x62000000,0x00800000
         mtdparts=rk29xxnand:
           0x00002000@0x00002000(uboot)
           0x00002000@0x00004000(trust)
           0x00002000@0x00006000(misc)
           0x00008000@0x00008000(resource)
           0x00010000@0x00010000(kernel)
           0x00010000@0x00020000(boot)
           0x00020000@0x00030000(recovery)
           0x00038000@0x00050000(backup)
           0x00002000@0x00088000(security)
           0x00100000@0x0008a000(cache)
           0x00400000@0x0018a000(system)
           0x00008000@0x0058a000(metadata)
           0x00080000@0x00592000(vendor)
           0x00080000@0x00612000(oem)
           0x00000400@0x00692000(frp)
           -@0x00692400(userdata)
```

## Decoded partitions (sectors are 512 bytes)

| Partition | Start LBA  | Size LBA   | Size       | Notes |
|-----------|-----------:|-----------:|-----------:|-------|
| uboot     | 0x00002000 | 0x00002000 | 4 MB       | bootloader |
| trust     | 0x00004000 | 0x00002000 | 4 MB       | Trust OS (TEE) |
| misc      | 0x00006000 | 0x00002000 | 4 MB       | BCB - reboot mode flag |
| resource  | 0x00008000 | 0x00008000 | 16 MB      | DTB and friends |
| kernel    | 0x00010000 | 0x00010000 | 32 MB      | kernel image |
| boot      | 0x00020000 | 0x00010000 | 32 MB      | boot ramdisk |
| recovery  | 0x00030000 | 0x00020000 | 64 MB      | recovery image |
| backup    | 0x00050000 | 0x00038000 | 112 MB     | OTA backup |
| security  | 0x00088000 | 0x00002000 | 4 MB       | security stuff |
| cache     | 0x0008a000 | 0x00100000 | 512 MB     | /cache |
| system    | 0x0018a000 | 0x00400000 | 2 GB       | /system - dm-verity protected |
| metadata  | 0x0058a000 | 0x00008000 | 16 MB      | metadata |
| vendor    | 0x00592000 | 0x00080000 | 256 MB     | /vendor - dm-verity protected |
| oem       | 0x00612000 | 0x00080000 | 256 MB     | OEM customizations |
| frp       | 0x00692000 | 0x00000400 | 512 KB     | Factory Reset Protection |
| **userdata** | **0x00692400** | rest      | **~11.2 GB** | /data |

## Key observations

- `androidboot.veritymode=enforcing` -> dm-verity is on for /system and
  /vendor. We cannot modify those partitions and reboot without breaking
  the verity tree (kernel will refuse to mount).
- `userdata` is NOT verity-protected (it's read-write user data). We
  can modify the raw bytes - **but** if File-Based Encryption (FBE) is
  enabled, our edits won't be readable through the encryption layer.
  Need to check userdata's ext4 superblock to confirm.
- `boot` partition has the kernel + ramdisk including `default.prop`.
  If we can repack boot.img with `ro.adb.secure=0` in default.prop,
  adbd would skip auth entirely.
- `misc` partition's BCB (Bootloader Control Block) lets the bootloader
  enter recovery / sideload modes automatically on next boot.
- `frp` is small but factory-reset-protection - leave alone.

## Strategic options now that we have eMMC access

1. **Patch boot.img default.prop** to disable ADB secure mode. Cleanest
   but needs to repack with valid hashes.
2. **Check userdata FBE status**, if disabled just edit adb_keys
   directly.
3. **Patch the parameter file** to add `androidboot.veritymode=disabled`,
   then we can also modify /system freely. The parameter file is at
   sector 0 and has no hash protection beyond its own checksum.
4. **Use misc partition BCB** to force boot into recovery + sideload.
   But still need testkey or unsigned-zip acceptance.
