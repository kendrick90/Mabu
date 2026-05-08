# Access attempts log

Living record of what we've tried to get authorized access to the Mabu's
Android system, what worked, what didn't, and why.

## Device facts established

- **Tablet SoC**: suspected RK3288 (Cortex-A17, ~2018 era). Unconfirmed.
- **OS**: Android (version unknown), provisioned in Device Owner mode
  via Esper.io MDM.
- **MDM tenant**: dead (Catalia Health defunct ~2020). No remote
  unenroll path possible.
- **USB header on main board**: 30-pin, exposes USB OTG, debug-ish UART
  (actually motor-controller link), I2C to power board, audio,
  PWRON, ADKEY (resistor-ladder buttons), etc.

## USB enumeration matrix

| State | VID:PID | Name / Serial | Interfaces | Authorized? |
|---|---|---|---|---|
| Main Android | 2207:0006 | "H7R" / 2022010502079 | ADB only (Class FF/42/01) | No - Esper kiosk hides dialog |
| Recovery     | 2207:0011 | rockchipplatform      | ADB (FF/42/01) + "MTP" (FF/FF/00 - actually rockusb-flavoured) | No - same wall |

Both PIDs (0x0006 and 0x0011) have high byte == 0, which standard
`rkdeveloptool` builds explicitly reject. So the Rockchip-tool path
needs either a custom build or a true MaskROM PID.

## Attempts and results

### Software paths

| Attempt | Result |
|---|---|
| Plain ADB auth dialog (replug while screen awake) | Dialog never appears - Esper kiosk suppresses system dialogs |
| Esper "switch to admin mode" password | Tried `admin`, "2 attempts remaining" - **DO NOT TRY MORE**. Lockout consequences could trigger auto-factory-reset to a dead Esper tenant, soft-bricking management plane permanently |
| `adb reboot` from main Android (unauth) | "device unauthorized" - reboot is gated by auth on this build |
| `adb reboot sideload-auto-reboot` from main | "device unauthorized" |
| `adb reboot sideload-auto-reboot` from recovery | "device unauthorized" - recovery's adbd has same auth gate |
| File transfer / MTP toggle in Android UI | Blocked by Esper policy |
| Read files via unauthorized adb | All commands need auth (get-state, get-serialno, shell, pull, logcat) |

### Hardware paths

| Attempt | Result |
|---|---|
| ADKEY shorted to GND + power-on | **Boots to recovery!** "No command" / Android-with-warning idle screen |
| Trying to invoke recovery menu (Vol Up + Power) from "No command" | First attempt: couldn't generate distinct ADKEY resistance values; resolved with 220 Ohm + 680 Ohm resistors as Vol Up / Vol Down |
| Recovery menu navigation with 220 Ohm + 680 Ohm + PWRON pulse | **Works!** Selected "Apply update from ADB", reached sideload mode (`adb devices` shows `sideload` instead of `unauthorized`) |

### Sideload findings

| Attempt | Result |
|---|---|
| `adb sideload probe-empty.zip` (empty zip, 22 bytes) | "footer is wrong verification failed" - **OTA signature verification IS enabled**. The recovery checks the 6-byte signature footer (must end with 0xff 0xff in middle bytes per AOSP `verifier.cpp`) before any content parsing. Unsigned zips will never work. |
| **Pending**: testkey-signed probe zip | TBD - testing whether the recovery's `/res/keys` includes the AOSP testkey, which would let us sign payloads with the publicly-available test private key |

### Investigated and ruled out: direct rockusb on MI_01

Recovery composite exposes a second interface (FF/FF/00, labelled "MTP"
in Windows Device Manager). I assumed this was Rockchip's rockusb gadget
and built a direct WinUSB-API client (scripts/rockusb_winusb.py) to
bypass rkdeveloptool's PID filter.

The client opens the WinUSB-bound interface successfully but the device
rejects USB Mass Storage Class Bulk Reset (control transfer returns
ERROR_GEN_FAILURE) and bulk OUT writes time out. **MSC Bulk Reset is
the most basic "I am MSC" handshake; rejection means MI_01 is NOT
rockusb.** It's something else - possibly an OEM-customized MTP
variant (the descriptor string says "MTP"), an Esper diagnostic
channel, or an Android sideload-related protocol. Either way: not
rockusb, so rkdeveloptool replacement is irrelevant here.

The rockusb_winusb.py script is left in place for future use if we
ever encounter true rockusb on a non-standard PID; the libusb-package
bundling and direct-WinUSB-API code is reusable.

## What's left to try (in cost order)

1. **USB host mode + USB keyboard in recovery**
   - Boot to recovery (proven path)
   - Disconnect D+/D- from PC, ground OTG_ID, connect USB keyboard via OTG_DP/OTG_DM/VCCUSB
   - Navigate menu with arrow keys, select "Apply update from ADB"
   - Reconnect to PC, sideload a payload that adds our adb key to /data/misc/adb/adb_keys

2. **MaskROM via PCB pad short**
   - Need photo of PCB (top + bottom, RF shield is in the way)
   - Identify eMMC CLK or D0 ball, short to GND during power-on
   - SoC falls through to USB loader mode with chip-specific PID
   - Custom rkdeveloptool or rockusb client to read/write eMMC offline

3. **Compile custom rkdeveloptool** that accepts PID 0x0006 / 0x0011
   - Recovery's "MTP" interface at FF/FF/00 might already be the rockusb gadget
   - If so, this gives us partition read/write without needing MaskROM
   - Effort: rebuild upstream source with patched DefineHeader.h

4. **Find SoC debug UART**
   - Header's UART is the motor-controller link, not console
   - Real SoC debug UART probably on PCB test pads, possibly under shield

## Constraints / lessons

- **Don't guess Esper passwords** - lockout is one-way and consequences
  on a dead-tenant device are severe.
- **Reboots are auth-gated** in modern Android - we can't sneak through
  via `adb reboot <subcommand>`.
- **Both adbds are auth-gated** equally - main system and recovery.
- **The kiosk hides system dialogs** - we can't expect an auth prompt
  to be visible while Esper is running.

## Next concrete step

Test 2: USB keyboard via OTG host mode in recovery. Costs only some
wire-shuffling time and a USB keyboard everyone has lying around.
