# Mabu liberation toolkit

Reverse-engineering and repurposing the **Mabu** health robot tablet
(Catalia Health, defunct). The robot is an Android 8.1 tablet bonded to
a motor controller and power daughterboard, locked down by **Esper
MDM** (kiosk mode, Device Owner, USB ADB suppressed).

This repo is the validated procedure for removing that lockdown — turning
the tablet back into a freely user-controlled Android device while keeping
the option to drive the robot motors via the original factory-test app.

## Quick start (1 command per unit)

```powershell
# Connect via internal USB harness, power on, catch Loader (PID 0x320A)
.\scripts\flash-mabu.ps1 -WipeData -RestoreMabu
# WiFi setup happens on the touch UI when prompted
# Done. Lawnchair + F-Droid + Mabu Factory Mode installed.
```

That's the whole assembly-line flow. The rest of this README is what's
inside the box.

## Hardware

- **SoC:** Rockchip RK3288 (confirmed via PID 0x320A + chip-info).
  Board ID `HRA7_RK3288W_V1.2_2021.10.15`.
- **eMMC:** Samsung 16 GB BGA, ext4 throughout, 30,310,400 × 512 B sectors.
- **Android:** 8.1.0, build fingerprint
  `rockchip/H7R/H7R:8.1.0/OPM6.171019.030.E1/...:user/release-keys`.
  Same build on every unit we've seen.
- **USB OTG:** Broken out via a 30-pin header on the main board (see
  the pinout below). D+/D− polarity was the gotcha to remember during
  first-time wiring.

### 30-pin header pinout (2 × 15)

Fine pitch (sub-2.54 mm — measure before sourcing connectors). Pin 1
location TBD on first inspection.

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

Functional groups: USB OTG (VCCUSB / OTG_ID / OTG_DM / OTG_DP / GND),
motor UART (TX / RX / RTS / CTS), PMIC I²C (SDA / SCL), audio
(SPKN/SPKP differential out, PDM mic, IN3P aux), buttons (PWRON,
ADKEY resistor ladder via ADC).

## Software stack

- **Esper** (Device Owner / kiosk) at provisioning time, packaged as
  `io.shoonya.shoonyadpc` (DPC, installed to `/data/app/`),
  `io.shoonya.helper` and `com.shoonyaos.oculus.plugin.supervisor.h7r`
  (in `/system/app/`), plus `io.esper.remoteviewer` and
  `io.esper.otamanager` (in `/data/app/`).
- **Catalia Mabu Factory Mode** (`com.catalia.factorymode`): the
  factory-test program with the main class
  `com.catalia.mabu.navigation.MainActivity`. Misleading class name —
  it's the diagnostic suite, not the consumer Mabu conversational app.
  The consumer app was never archived; presumed Esper-deployed only.

## What the patches actually do

Eight sector-level writes via Rockchip Loader. None of them touch boot.img;
they target raw eMMC sectors in /system, the parameter partition, and the
adbd binary.

| # | Where | What | Why |
|---|---|---|---|
| 1 | parameter @ LBA 0 | Kernel cmdline: `androidboot.veritymode=disabled androidboot.selinux=permissive`, Rockchip CRC32 recomputed | Removes dm-verity and SELinux enforcement so /system edits can take effect at runtime |
| 2 | /system/bin/adbd @ LBA 1,696,240 | Byte 284: `0x01 → 0x00` (auth_required global) | adbd accepts host without dialog/key approval |
| 3 | /system/bin/adbd @ LBA 1,694,778 | Bytes 56-57: `F0 B5 → 70 47` (`BX LR` — return early from adbd_auth_init) | Defense in depth |
| 4 | /system/app/espersupervisor.apk @ LBA 1,851,238 | Zero the 4-byte EOCD signature | PackageManager skips |
| 5 | /system/app/esperdpc.apk @ LBA 1,981,802 | Same | Same |
| 6 | /system/app/esperhelper.apk @ LBA 2,063,565 | Same | Same |
| 7 | /system/bin/set-device-owner.sh @ LBA 1,691,408 | Zero the 4 KB data block | Init runs an empty shell script (no-op) instead of `dpm set-device-owner …` |
| 8 | /system/etc/init/init.esper.rc @ LBA 2,076,672 | Zero the 4 KB data block | Init parses an empty .rc — no `set-device-owner` service ever registered |

The /data wipe (`wipe-data-head.ps1`) is separate. It is **required** on
active-Esper units because the Device Policy Controller binary
(`io.shoonya.shoonyadpc`) is installed to `/data/app/` by Esper at
provisioning time, not to `/system/`. The /system EOCD nukes cannot reach
it, and DPM blocks soft uninstall (`SecurityException: Attempt to remove
non-test admin`). 96 MB of zeros at the head of the partition corrupts
the ext4 superblock; vold detects this on boot and reformats /data
cleanly. Larger wipes (256+ MB) have correlated with a Settings.apk
Developer Options crash on unit 1; smaller wipes don't reliably trigger
reformat. 96 MB is the sweet spot.

The patches survive factory reset (they're in /system or sector 0).
WiFi credentials, motor calibration, and any installed user apps are wiped
by /data reformat.

## Per-unit state matrix (after liberation)

| Unit | Serial | DO | Esper | USB ADB | WiFi ADB | Notes |
|---|---|---|---|---|---|---|
| 1 | 2022010502079 | clear | clean | wedges (offline after handshake) | works | Dev Options crashes — Settings.apk dex or precompiled SELinux issue, deferred |
| 2 | 2022010500480 | clear | clean | works | 10.0.0.147 | Template reference. Dev Options OK |
| 3 | 2022010501476 | clear | clean | works | TBD post-wifi-setup | Just liberated |

## Caveats / known limits

- **No consumer Mabu app.** Our archives only contain `factorymode`. The
  patient-facing Mabu conversational software is not in any captured /data
  on any of three units — presumed Esper-deployed only at provisioning
  time, never persisted across factory reset.
- **Dev Options crashes on unit 1.** Cause is in /system, not /data.
  Workarounds: use ADB shell for everything Dev Options does, or
  install Activity Launcher to skip the broken sub-page. Patching
  Settings.apk dex is possible but unimplemented.
- **USB ADB unreliable on unit 1.** Enumerates, then sits as `offline`
  forever. Cause unknown. WiFi ADB is the stable transport on this
  build regardless — the parameter file already includes
  `service.adb.tcp.port=5555`.
- **Loader read wedge.** rkdeveloptool's `rl` works in 4 MB chunks but
  the Loader wedges after ~28 MB cumulative reads in one session,
  requiring a physical power cycle. The cycled dumper
  (`scripts/dump-system-cycled.ps1`) is the workaround. The current
  procedure avoids large reads entirely, so this is only relevant if
  firmware ever drifts and we need to capture a fresh /system image.

## Layout

- `scripts/`  — live scripts. See `scripts/README.md` for what each one does.
- `scripts/archive/` — dead-end attempts (boot.img repacking, pyusb rockusb
  clients, OTA sideloading, etc.) with explanations of why they didn't work.
- `notes/HANDOFF.md` — detailed session-by-session log. Has more depth
  than this README; consult it when something here doesn't match observed
  behavior.
- `notes/partition-table.md`, `loader-readout.md`, etc. — reference data.
- `apks/`  — committed installers (F-Droid, Lawnchair, factorymode is in mabu-archive/).
- `mabu-archive/`  — per-unit captures (Mabu APKs, sdcard animation CSVs,
  dumpsys outputs). The factorymode.apk + animations source for restore.
- `tools/`  — downloaded tooling (rkdeveloptool, Rockchip drivers, Zadig).
  Gitignored.
- `dumps/`  — partition dumps and patch payloads. Gitignored to avoid
  publishing firmware-derived bytes.
