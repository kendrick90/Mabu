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

## Build prerequisites (host machine)

This is the toolchain we set up under `C:\Users\user\Tools\`:

| Tool | Version | Path |
|---|---|---|
| JDK | Temurin 17 | `C:\Users\user\Tools\jdk-17` |
| Android SDK | platforms 28+34, build-tools 34 | `C:\Users\user\Tools\android-sdk` |
| Gradle | 8.4 | `C:\Users\user\Tools\gradle-8.4` |

Persistent env vars: `JAVA_HOME`, `ANDROID_HOME`, `ANDROID_SDK_ROOT`
(set via `setx` during initial setup; they take effect in new shells).

`local.properties` in this directory points the build to `ANDROID_HOME`.
Don't commit it — it's gitignored.

## Build / install / run

From this directory, in a shell where `JAVA_HOME` and `ANDROID_HOME` are
set:

```powershell
# Build debug APK
.\gradlew.bat assembleDebug

# Install to the connected device (unit 4 at 10.0.0.69:5555)
.\gradlew.bat installDebug

# Launch
adb shell am start -n com.mabu.faceoverlay/.MainActivity

# Watch logs
adb logcat -s MabuFaceOverlay:* FaceAnalyzer:* AndroidRuntime:E *:F
```

To grab a screenshot of what the overlay looks like running on Mabu:

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
