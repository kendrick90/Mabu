# Rockchip Loader readout

Captured from RKDevTool on the second tablet (similar HRA7_RK3288W board,
different eMMC vendor than the Foresee in the original Mabu).

**Date**: 2026-05-25 ~18:40 local
**Device**: VID 0x2207 / PID 0x320A (RK3288 in u-boot Loader mode)
**Driver bound**: rockusb.sys (Rockchip official, via DriverAssistant v5.0)

## Chip Info

    33 32 30 41   ->   ASCII "320A"   ->   RK32 / RK3288 confirmed

(`Get Capability` failed - some loaders don't expose this. Doesn't matter,
read commands still work.)

## Flash Info

| Field | Value | Notes |
|---|---|---|
| Manufacturer | SAMSUNG | underlying NAND vendor (chip may be Foresee or other brand) |
| Flash Size | 14800 MB | user area capacity |
| Total Sector | 0x1ce8000 | = 30,310,400 sectors of 512 bytes = 15.5 GB raw |
| Block Size | 512 KB | |
| Page Size | 2 KB | |
| ECC Bits | 40 | |
| Flash CS | 0 | |
| Flash ID | `45 4D 4D 43 20` = "EMMC " | confirmed eMMC (not NAND or NOR) |

## How we got here

1. **Earlier wiring fix**: D+/D- swap got USB enumeration working again.
2. **Driver install**: tools/rockchip-stock/DriverAssitant_v5.0/.../DriverInstall.exe
   installed `rockusb.sys` for VID 0x2207 devices.
3. **Power cycle**: Force off + power on triggers a transient ~10 second
   window in u-boot where the device exposes itself as PID 0x320A.
4. **First USB command keeps it there**: once we open the device with
   rockusb commands, u-boot stays in Loader mode rather than continuing
   to kernel boot.
5. **Persistent state**: Device sat at PID 0x320A for the entire 60-sec
   watch window after the first command was sent.

## Key insight: cpebit's rkdeveloptool can't talk to rockusb.sys-bound devices

`rkdeveloptool ld` lists the device correctly:

    DevNo=1  Vid=0x2207,Pid=0x320a,LocationID=402  Loader

But every other command returns `Creating Comm Object failed!` because
libusb on Windows can't open a device that's bound to a custom kernel
driver (rockusb.sys). The fix is to use the Rockchip-supplied tooling
(RKDevTool / AFPTool) which talks to rockusb.sys directly via the
Rockchip USB API. Alternatively, swap the binding back to WinUSB via
Zadig if CLI control is essential.

## Next step

Read the partition table (Rockchip parameter file or GPT). We need the
start sector and size of the **userdata** partition. Then we dump it,
inject our adb key offline, and write the modified image back.
