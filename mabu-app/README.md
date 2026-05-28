# mabu-app

> **Superseded by [`../mabu-android/`](../mabu-android/).** This directory
> is kept as a reference, not a runtime. The Termux/Python loop topped out
> around 3-5 FPS on still captures; the native Kotlin port runs the full
> face tracking + ASR + LLM + TTS conversation loop in real time and is the
> source of truth going forward.
>
> What ported where:
>
> | This dir | Replaced by |
> |---|---|
> | `mabu.py` motor protocol | [`mabu-android/app/src/main/java/com/mabu/faceoverlay/MabuMotors.kt`](../mabu-android/app/src/main/java/com/mabu/faceoverlay/MabuMotors.kt) |
> | `face-mirror.py` (OpenCV LBP @ 3-5 FPS) | `mabu-android/` (ML Kit + Camera1 @ 8-15 FPS, plus pupil-gaze, attention, modes) |
> | `lbpcascade_frontalface.xml` | ML Kit's bundled face detector (no asset needed) |
>
> Keep this Python around as the shorter, more readable wire-protocol
> reference, and for any future scenario where Mabu needs to be driven from
> a non-Android host (a Pi, a dev box doing protocol exploration, etc.).
> The Kotlin port is line-for-line comparable; if you change one,
> consider whether the other needs the same update. Per-unit-4
> calibration corrections (eyelid + EUD inversion, see
> [`notes/motor-protocol.md`](../notes/motor-protocol.md) and the
> motor-calibration memory) live on the Kotlin side only.

---

Python prototype layer for an embodied Claude on a liberated Mabu robot.
Runs on the tablet inside Termux. Fast-iteration code lives here; if we
ever need 30 FPS camera we'll graduate to a native Android shell, but
not before.

## Files

| File | What |
|---|---|
| `mabu.py` | Motor driver module. Opens `/dev/ttyS1`, sends our protocol (`notes/motor-protocol.md`), exposes semantic poses (`gaze`, `blink`, `nod`, `shake`, `surprised`, etc.). |
| `face-mirror.py` | First closed-loop demo: tablet camera watches you, eyes follow your face, Mabu blinks when you blink. ~3-5 FPS via `termux-camera-photo`. |
| `lbpcascade_frontalface.xml` | LBP cascade extracted from factorymode's assets/opencv/. Used by face-mirror. |

## Quick start on the tablet

In Termux (after first-launch bootstrap):

```sh
# one-time setup
termux-setup-storage          # accept the dialog; lets termux read /sdcard
pkg update -y
pkg install -y python termux-api
pip install pyserial opencv-python-headless numpy

# pull our app code from /sdcard (push via adb from host first)
cp /sdcard/mabu-app/*.py /sdcard/mabu-app/*.xml .

# self-test motors (no camera needed)
python mabu.py

# face mirror (also installs the Termux:API addon for camera access)
python face-mirror.py
```

### Termux:API addon

`termux-camera-photo` is provided by the **Termux:API** companion app
(separate APK from F-Droid). Install it on the device:

```sh
# from the host
adb install apks/Termux-API.apk      # we'll push this; not yet in apks/
```

## Architecture notes

- `mabu.py` deliberately re-implements the motor protocol from scratch
  rather than calling into factorymode. We have no IPC handle into
  factorymode (no exported services / broadcasts), so external control
  isn't possible without re-deriving the bytes. The protocol is small
  and fully documented in `../notes/motor-protocol.md`.
- Animations are **Python coroutines** in spirit, not CSVs. Named
  motions (`blink`, `nod`, `shake`) live in `mabu.Mabu`; for arbitrary
  motion you call `m.move(eyes_lr=70, neck_tilt=40, ...)` with any
  subset of the 7 motors.
- Per-unit motor calibration is ignored for now. Each unit's mechanical
  zero will be slightly different until we add our own calibration
  store. Practical effect: pose values like `eyes_lr=70` might come out
  visually-72 on one unit and visually-68 on another. Fine for v1.

## Roadmap

1. **Motors validated** (in progress — `mabu-motor-test.py` running on unit 4)
2. **`mabu.py` selftest** runs end-to-end: blink, wink, nod, shake, sweep, surprise, recenter
3. **`face-mirror.py`** runs: gaze tracks face, blink mirrors blink
4. **Voice loop**: push-to-talk → Whisper or Claude voice → Claude API → TTS
5. **Embodied responses**: Claude returns `{"speech": "...", "motion": "nod"|...}`,
   we play the motion while TTS speaks
6. **Idle behavior**: when nothing's happening, run `look_around` + spontaneous blinks
   so the robot looks alive instead of frozen
