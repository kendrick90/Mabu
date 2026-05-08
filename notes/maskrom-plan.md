# MaskROM extraction plan

## Why this is the path

After extensive software work:

- ADB authorization dialog suppressed by Esper kiosk -> dead end
- Esper "switch to admin" password unknown, 2 wrong attempts away from
  potential auto-factory-reset to a dead tenant -> dead end
- ADB sideload requires signed OTA. Recovery tested with empty zip and
  rejected with "footer is wrong verification failed" - signature
  verification IS enabled. AOSP testkey *might* be in /res/keys but
  probably not on a 2018-era Esper-managed Android 8.1.0 build with
  Sept 2018 patches. Even getting one more sideload attempt requires
  painful resistor + PWRON dancing.
- Network access shows the tablet at 10.0.0.161, only port 5555 (ADB)
  exposed. Same auth wall on TCP. No mDNS pairing service (pre-
  Android 11), no other services.
- Recovery's MI_01 interface (FF/FF/00, labelled "MTP") is NOT
  rockusb (rejected USB MSC Bulk Reset).

MaskROM bypasses all of the above. It's the SoC's hardware fallback
when the eMMC bootloader is unreadable -> it falls through to a
ROM-resident USB protocol that supports raw flash R/W.

## Tools staged in tools/rockchip-stock/

  DriverAssitant_v5.0/
    DriverInstall.exe       <- installs rockusb.sys (the kernel driver
                                that binds to MaskROM-mode devices)
    Driver/x64/win10/       <- the actual driver INF and SYS files
    ADBDriver/              <- bundled Google ADB driver (we already have
                                a patched version, so this can be ignored)

  RKDevTool_Release_v2.92/
    RKDevTool.exe           <- GUI for talking to MaskROM/Loader devices
    bin/AFPTool.exe         <- partition flash helper
    bin/RKImageMaker.exe    <- image builder
    revision.txt            <- changelog/version info

  DriverAssitant_v5.0.zip   sha256=30044d0a6a15f922963d13b8409120abef16783e456c73bceb8148bc30f806c3
  RKDevTool_Release_v2.92.zip sha256=d136c5bb12db57f48304fac428d59163ed98cc0fd590a8e6f304dc18ba8f0ef1

## Workflow once the PCB photo identifies the MaskROM trigger pad

For RK3288 the standard trigger is shorting the **eMMC CLK pin** (pin G2 on
most BGA-153 packages) or any of the eMMC data lines (D0-D7) to GND while
power is applied. The SoC's bootrom can't read the bootloader from eMMC,
so it falls through to USB loader mode and exposes itself on the bus
with a chip-specific PID:

  RK3288 in MaskROM:  VID 2207 / PID 320A   (vs PID 0006 in main, 0011 in recovery)

### 1. Install Rockchip USB driver (one-time)

    cd tools\rockchip-stock\DriverAssitant_v5.0
    .\DriverInstall.exe          (run as admin; will UAC prompt)

    -> Click "Driver Install" in the GUI.
    -> Installs rockusb.sys + INF as a kernel driver.

### 2. Force MaskROM

  - Tablet powered off completely (PWRON >7s if needed).
  - With the eMMC CLK or D0 pin held shorted to a nearby GND...
  - Apply power (USB or DCIN).
  - Watch `scripts\watch-usb.ps1` - we expect a NEW USB device to appear:
       VID_2207 PID_320A   (or a similar chip-specific PID)
  - Once it appears, you can release the short - the bootrom is already
    in USB loader mode and won't fall back to eMMC mid-session.

### 3. Talk to it with RKDevTool

    cd tools\rockchip-stock\RKDevTool_Release_v2.92
    .\RKDevTool.exe

    -> Status line should say "Found One MaskRom Device" or
       "Found One LOADER Device"
    -> Switch to the "Advanced" tab to see the full set of operations.
    -> "Read Flash Info" -> verifies the protocol works
    -> "Read Storage" or partition-by-partition reads to dump the device.

### 4. Patch /data/misc/adb/adb_keys offline

    -> Read userdata partition (probably the largest, several GB)
    -> Loop-mount the dump (Linux/WSL): `mount -o loop userdata.img /mnt`
    -> Append our adbkey.pub to /mnt/misc/adb/adb_keys
    -> chown 2000:2000, chmod 0640
    -> Unmount, write back the modified image to userdata partition
    -> RKDevTool "Reset Device" or remove power
    -> Tablet boots normally; adb devices shows "device" not "unauthorized".

## What we don't know yet (need photo)

- Exact RK3288 package variant (BGA-453 SHRINK or BGA-453 FBGA)
- eMMC chip orientation - which side of the PCB it's on
- Whether the PCB has a labelled MASKROM/RECOVERY test pad accessible
  without removing the RF shield (best case: just bridge two pads with
  tweezers)
- Whether the RF shield has a removable lid (frame stays soldered, lid
  pops off) vs. a one-piece can that needs hot air
- Whether the eMMC chip is on the back of the PCB - those test pads
  often peek out outside the shield since shields typically cover only
  the top
