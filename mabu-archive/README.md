# Mabu IP archive

Forensic captures from individual Mabu units pulled before destructive
liberation steps. Each subdirectory is one unit, named by its serial
(visible as `ro.serialno`).

## Captured units

- **`unit-2022010500480/`** (= "unit 2") — pre-liberation dumpsys snapshot
  only (no APKs, no /sdcard). Used as the template-donor; captured before
  the v2 liberation procedure existed.
- **`unit-2022010501476/`** (= "unit 3") — full forensic capture: 7 APKs
  (Catalia + Esper + OpenCV), /sdcard contents (animation CSVs, Nuance
  voice assets), dumpsys, getprop. This is the canonical source for
  `flash-mabu.ps1 -RestoreMabu` to install factorymode + assets onto
  freshly-wiped units. Captured before /data wipe per V3 procedure.

## What's in a full-capture unit directory

- `apks/` — every Catalia / Esper / OpenCV APK that was installed in
  `/data/app` on that unit. The crown jewel is `com.catalia.factorymode.apk`
  which contains:
  - `lib/armeabi-v7a/libsercomm.so` — serial-port driver to the motor
    daughter board (the actual motor protocol)
  - `lib/armeabi-v7a/libdetection_based_tracker.so` — OpenCV-based
    face/object tracking
  - `lib/armeabi-v7a/libpal_audio.so`, `libpal_core.so` — platform
    abstraction layer (audio)
  - `lib/armeabi-v7a/libvocon3200_*.so` (~7 MB) — Nuance Vocon3200
    voice-recognition stack (ASR, grammar, pronunciation, semantics)
  - `assets/animcsvs/*.csv` — bundled animation files (Time(ms), MCB1,
    MCB2, DATA1, DATA2 — 4 motor channels)

- `sdcard/` — contents of /sdcard (Mabu's external-storage area).
  Contains the *runtime* copies of the animation CSVs at top level,
  plus Nuance speech-recognition acoustic model (`acmod5_*.dat`),
  recorded `sound.raw` samples, and Esper-DPC's download payload
  (`Android/data/io.shoonya.shoonyadpc/files/Downloads/`).

- `parameter.img` — Rockchip parameter partition (8 KB, plaintext).
  Contains the partition layout, kernel cmdline, and CRC for this unit.
  Identical across units (we've verified by md5).

## Animation CSV format

```
Time(ms), MCB1, MCB2, DATA1, DATA2
```

Time-series of 4 motor channel values, sampled at ~10 ms intervals.
Values are floating-point (looks like degrees, range roughly ±90).
MCB1/MCB2 = "Motor Control Bus" channels 1 & 2; DATA1/DATA2 likely
either two more motor channels or sensor feedback. Reverse-engineering
the matching playback path in `libsercomm.so` will confirm.

## Why this archive matters

Every Mabu liberation that wipes /data destroys this data, because
the original Mabu app was installed under /data/app/ (not /system).
This is the only way to preserve the IP needed to repurpose the
hardware. Capture this BEFORE the destructive de-Esper steps.

The procedure used (`liberate-mabu.ps1` + ADB pull) is documented in
`notes/HANDOFF.md` under "V3 Liberation procedure".
