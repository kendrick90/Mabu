# mabu-android

Native Android app layer for the Mabu tablet. Supersedes the Termux + Python
prototype in `../mabu-app/` (which was capped around 3-5 FPS on the Cortex-A17).

First demo: **live face overlay** — front-camera preview with ML Kit Face
Detection drawing the face bounding box, 15 landmark contours (eyes, mouth,
nose, eyebrows, face outline), 10 landmark dots, and a smile / eyes-open
probability readout.

## Why ML Kit and not MediaPipe

RK3288 is `armeabi-v7a` only. Modern MediaPipe Tasks Vision AARs dropped
that ABI around 0.10.x. ML Kit's bundled face detector (`com.google.mlkit:
face-detection`) still ships armv7 native libs and doesn't depend on Google
Play Services, so it runs on this device with no extra plumbing. See
the memory note `direction-native-android` for the broader rationale.

## Deployment to your Mabu

Prerequisite: the unit is **liberated** (Esper removed, ADB working). See
the parent [`../README.md`](../README.md) for that procedure if you haven't
done it yet. Once your unit is liberated you'll have its LAN IP from the
provisioning step (e.g. 10.0.0.69) and WiFi ADB enabled on port 5555.

### 1 · Install the toolchain (Windows host)

You need **JDK 17**, the **Android SDK** with platform 28 + build-tools 34
and **Gradle 8.4** (or just install Android Studio, which bundles all
three). The portable / no-admin layout this repo was developed against:

```
C:\Users\<you>\Tools\jdk-17\          (Eclipse Temurin 17 LTS zip)
C:\Users\<you>\Tools\android-sdk\     (Android command-line tools layout)
C:\Users\<you>\Tools\gradle-8.4\      (gradle-8.4-bin.zip extracted)
```

Set env vars persistently:

```powershell
setx JAVA_HOME       'C:\Users\<you>\Tools\jdk-17'
setx ANDROID_HOME    'C:\Users\<you>\Tools\android-sdk'
setx ANDROID_SDK_ROOT 'C:\Users\<you>\Tools\android-sdk'
```

Update `local.properties` in this directory to point at your `android-sdk`
(it's gitignored).

### 2 · Pull llama.cpp source

```powershell
# from the repo root
.\setup-llama.ps1
```

Clones llama.cpp under `app/src/main/cpp/llama.cpp/`. The full source is
~185 MB so we don't vendor it; the CMake build picks it up if present
and stubs the LLM if not.

### 3 · Connect to the unit over WiFi ADB

```powershell
adb connect <your-mabu-ip>:5555
adb devices
```

First connection from a new host: the tablet shows an **"Allow ADB
debugging from this computer?"** dialog on its touch screen — tap
**Always allow**. If that doesn't appear, the unit's authorization may
have been bypassed by the liberation patches; if so, you're already in.

If the device shows `unauthorized`, look at the tablet. If the device
shows `offline`, the screen probably went to sleep and WiFi dropped —
wake the screen and re-`adb connect`.

### 4 · Push the model files

Two large blobs the app reads from disk — neither is bundled in the APK.

**LLM model** — Qwen2.5-0.5B Instruct, Q4_K_M GGUF (~470 MB):

Download from
[Qwen/Qwen2.5-0.5B-Instruct-GGUF on Hugging Face](https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf)
and push:

```powershell
adb push qwen2.5-0.5b-instruct-q4_k_m.gguf /data/local/tmp/mabu.gguf
```

**ASR model** — Vosk small English (~70 MB extracted):

```powershell
# download + unzip vosk-model-small-en-us-0.15 from
#   https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip
adb push vosk-model-small-en-us-0.15 /sdcard/vosk-model-en
```

The Vosk model needs to land at exactly `/sdcard/vosk-model-en/` (a
directory of files, not a zip).

### 5 · Build, install, grant permissions, launch

```powershell
.\gradlew.bat installDebug

# Pre-grant the runtime permissions so the app doesn't have to prompt
adb shell pm grant com.mabu.faceoverlay android.permission.CAMERA
adb shell pm grant com.mabu.faceoverlay android.permission.RECORD_AUDIO
adb shell pm grant com.mabu.faceoverlay android.permission.READ_EXTERNAL_STORAGE

adb shell am start -n com.mabu.faceoverlay/.MainActivity
```

### 6 · Verify

Watch logs while you tap around:

```powershell
adb logcat -s MabuFaceOverlay:* MabuLLM:* MabuASR:* MabuTTS:* AndroidRuntime:E *:F
```

What you should see / hear:

- Face overlay tracking your face on the preview
- Robot's eyes (and neck, lightly) following you in FOLLOW mode
- `⚙ → LLM smoke test` produces a reply in logcat (~10 s the first time
  it loads the model)
- `⚙ → TTS smoke test` makes Pico say a sentence
- Hold-to-talk mic at the bottom transcribes and gets a spoken reply

### Driving the app from adb (handy during dev)

The app registers a few broadcasts for headless testing:

```powershell
adb shell "am broadcast -a com.mabu.faceoverlay.SPEAK --es text 'hello'"
adb shell "am broadcast -a com.mabu.faceoverlay.LLM --es prompt 'who are you' --ez speak true"
adb shell "am broadcast -a com.mabu.faceoverlay.SET_MODE --es mode PUPPET"
adb shell "am broadcast -a com.mabu.faceoverlay.SET_TTS_VOLUME --ef volume 0.18"
```

### Screenshot

```powershell
adb shell screencap -p /sdcard/overlay.png
adb pull /sdcard/overlay.png
```

## App structure

| File | Role |
|---|---|
| `MainActivity.kt` | Bootstraps CameraX, requests CAMERA permission, wires the front camera into a `PreviewView` and an `ImageAnalysis` analyzer. |
| `FaceAnalyzer.kt` | `ImageAnalysis.Analyzer` that runs ML Kit Face Detection (FAST mode, contours + landmarks + classification). Returns `FaceResult` to the overlay. |
| `FaceOverlayView.kt` | Custom `View` over the preview. Maps ML Kit's image-space coordinates to view-space accounting for FILL_CENTER scaling and front-camera mirror. Draws box / contours / landmarks / probability text. |

## Constraints baked into the Gradle config

- `minSdk = 24`, `targetSdk = 28` — Mabu is API 27 (Android 8.1).
  targetSdk 28 keeps us out of API 29+ scoped-storage / background-camera
  restrictions that we don't need.
- `compileSdk = 34` — required by current AndroidX / CameraX / ML Kit; harmless.
- `abiFilters = ["armeabi-v7a"]` — keeps the APK small and avoids
  shipping arm64 libs that wouldn't load on RK3288.

## On-device LLM (llama.cpp)

The app links against [llama.cpp](https://github.com/ggerganov/llama.cpp) so
Mabu can run small quantized models on-device. The source is ~185 MB and
**not vendored** — run `..\setup-llama.ps1` from the repo root once after
a fresh clone:

```powershell
.\setup-llama.ps1   # shallow-clones into mabu-android/app/src/main/cpp/llama.cpp/
```

The CMake build picks it up automatically. If the directory is missing,
the JNI compiles with stubs and `LlamaInference.nativeAvailable()` returns
false.

### Model file

The smoke-test button looks for a model at `/data/local/tmp/mabu.gguf`.
Recommended starter:

- [Qwen2.5-0.5B-Instruct Q4_K_M](https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF)
  — ~320 MB, fits comfortably alongside face tracking

```powershell
adb -s 10.0.0.69:5555 push qwen2.5-0.5b-instruct-q4_k_m.gguf /data/local/tmp/mabu.gguf
```

Then tap **⚙ settings → LLM smoke test** and watch logcat (`-s MabuLLM`)
for token-throughput numbers.

## Next demos worth trying after this works

1. Drive motors from face detection — port the eye-tracking / blink-mirror
   logic from `../mabu-app/face-mirror.py` to Kotlin. Open `/dev/ttyS1`
   from Kotlin (requires `chmod 666` via shell on first boot, or root the
   tty access), send the existing motor protocol.
2. Pose estimation (ML Kit Pose Detection) — useful for "is someone
   sitting in front of me, leaning in, walking past."
3. Object detection (ML Kit Object Detection or TFLite EfficientDet-Lite0)
   for "what's the person holding" cues.
4. On-device ASR via `android.speech.SpeechRecognizer` for push-to-talk to
   Claude, with results fed into the same motion-and-speech loop.
