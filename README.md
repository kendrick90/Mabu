# Mabu — Hardware Hacking Notes

Reverse-engineering / repurposing project for the **Mabu** health robot
(Catalia Health, defunct). The device is an Android tablet bonded to a
motor controller daughterboard and a power/battery daughterboard.

## Hardware overview

- **Main board:** Android tablet, Rockchip SoC (confirmed via USB VID 0x2207).
  Exact SoC variant TBD — product string reports `H7R`, which is an OEM
  custom string, not a Rockchip part name.
- **Power board:** connects to main board via SDA / SCL / GND / DCIN / GND
  (I²C link to the SoC's PMIC bus, plus power rails).
- **Motor board:** connects to main board via TX / RX / GND (UART, 3.3 V).

## The 30-pin header (2x15)

A fine-pitch header on the main board breaks out the inter-board signals.
Pitch is **smaller than 2.54 mm** — likely 1.27 mm; verify with calipers
before sourcing connectors. Pin 1 location TBD.

| Col A | Pin |  | Pin | Col B    |
|-------|-----|--|-----|----------|
| DCIN  |  1  |  |  2  | DCIN     |
| GND   |  3  |  |  4  | DCIN     |
| GND   |  5  |  |  6  | GND      |
| SPKN  |  7  |  |  8  | SPKP     |
| GND   |  9  |  | 10  | GND      |
| RTS   | 11  |  | 12  | CTS      |
| TX    | 13  |  | 14  | SDA      |
| RX    | 15  |  | 16  | SCL      |
| GND   | 17  |  | 18  | GND      |
| PWRON | 19  |  | 20  | IN3P     |
| VCCUSB| 21  |  | 22  | PDM      |
| OTG_ID| 23  |  | 24  | VCC      |
| OTG_DM| 25  |  | 26  | GND      |
| OTG_DP| 27  |  | 28  | ADKEY    |
| GND   | 29  |  | 30  | GND      |

Functional groups:

- **USB OTG:** `VCCUSB`, `OTG_ID`, `OTG_DM` (D−), `OTG_DP` (D+), GND.
- **UART (motor link):** `TX`, `RX`, `RTS`, `CTS` — used by the tablet to
  drive the motor controller. Not the SoC debug console.
- **I²C (power link):** `SDA`, `SCL` — to the power/PMIC daughterboard.
- **Audio:** `SPKN`/`SPKP` differential out, `PDM` mic in, `IN3P` likely aux.
- **Buttons:** `PWRON` (power button), `ADKEY` (resistor-ladder buttons,
  read via SoC ADC).

## Current state of access

USB OTG was wired to a host PC. Initial wiring failed enumeration with the
classic "Device Descriptor Request Failed" symptom (got an address, then
descriptor read corrupted) - root cause was likely D+/D- polarity / signal
integrity. After re-wiring, the device enumerates as:

```
USB\VID_2207&PID_0006   "H7R"
Compatible IDs: USB\Class_FF&SubClass_42&Prot_01   <- Android ADB interface
```

**It is NOT the Rockchip rockusb gadget** despite using the Rockchip VID.
Class 0xFF / SubClass 0x42 / Protocol 0x01 is Google's Android Debug
Bridge interface signature - the OEM is exposing ADB on the chip-vendor
VID (an unusual but legal choice). Standard `rkdeveloptool` builds reject
PIDs with a zero high byte (verified in `RKScan.cpp` - PID 0x0006 is
explicitly filtered out), so the Rockchip-tool path is a dead end.

The right tool is **adb** with the Google Android USB driver, patched to
include `VID_2207&PID_0006`. See `scripts\install-android-driver.ps1`.

The SoC is suspected to be **RK3288** based on era (~2018) and form factor;
this will be confirmed via `getprop ro.board.platform` once adb is up.

## Goals

1. Get adb shell access (install patched Google USB driver -> `adb devices`).
2. Read system properties to confirm SoC and Android variant.
3. Pull useful artifacts via adb (`adb pull /proc/cpuinfo`, partition images
   if root is available, etc.) for offline analysis.
4. Decide whether to drive the existing Android image directly (run custom
   APKs, control the motor controller via the existing UART) or replace
   the firmware entirely.

## Layout

- `scripts/`  — PowerShell / shell scripts for repeatable tasks.
- `notes/`    — progress notes, observations, datasheet excerpts.
- `tools/`    — downloaded binaries (gitignored).
- `dumps/`    — partition / firmware dumps (gitignored, may contain serials).
