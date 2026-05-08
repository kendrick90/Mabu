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
descriptor read corrupted) — root cause was likely D+/D− polarity / signal
integrity. After re-wiring, the device enumerates as:

```
USB\VID_2207&PID_0006   "H7R"
```

This is the **Rockchip rockusb / loader** USB function. Android is fully
booted on the tablet — the rockusb gadget is being exposed *from running
Android*, not because the SoC is in MaskROM. This is excellent for our
purposes: we can talk to it with `rkdeveloptool` without needing ADB or
recovery-key combos.

## Goals

1. Confirm exact SoC variant (`rkdeveloptool rci`).
2. Read the partition table (`rkdeveloptool ppt`).
3. Dump non-userdata partitions for offline analysis.
4. Decide whether to flash custom u-boot / kernel / rootfs, or to drive
   the existing Android image directly.

## Layout

- `scripts/`  — PowerShell / shell scripts for repeatable tasks.
- `notes/`    — progress notes, observations, datasheet excerpts.
- `tools/`    — downloaded binaries (gitignored).
- `dumps/`    — partition / firmware dumps (gitignored, may contain serials).
