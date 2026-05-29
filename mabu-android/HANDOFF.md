# Handoff — Anima (Mabu) streaming consciousness

Read this before the rest of the README. It captures the working state, the
architecture, and what's next. The app is **Anima** (`com.mabu.anima`).

## What works right now (full hands-free conversation)

A complete streaming loop runs end-to-end, all cognition on the PC brain:

- **Ears — WhisperLive ASR** (`pc-brain/whisperlive`): always-on, hands-free
  (no push-to-talk). `RemoteAsr.kt` streams 16 kHz int16 PCM over WebSocket;
  server VAD segments speech; a silence debounce endpoints each utterance.
  `large-v3-turbo` on GPU 0. hotword "Mabu". Snappy and reliable.
- **Mind — llama-server** (`C:\Users\user\Tools\llama-server\`): Qwen 2.5
  7B Instruct Q4_K_M, SSE streaming. `StreamingLlama.kt` chunks the reply into
  sentences as they arrive (~100–300 ms first sentence).
- **Voice — Chatterbox TTS** (`pc-brain/chatterbox`): `RemoteTts.kt` POSTs each
  sentence to `chatterbox_server.py`, which returns int16 PCM @ 24 kHz; the
  device pipelines synth + `AudioTrack` playback in order. Drop a `voice.wav`
  next to the server to clone a custom voice.
- **Glue**: echo-guard mutes the mic while Mabu speaks (re-opens on playback
  drain; 90 s watchdog backstop). Live transcript shows in a **speech bubble**
  by the face when one face is detected. Mute toggle lives in the volume cluster.

`TuningSettings.cognitionMode` defaults to `"streaming"`. In streaming mode the
on-device LLM (llama.cpp) and Vosk are skipped entirely; they remain the
`"local"`-mode fallback. Pico TTS is disabled (it SIGSEGVs) except as the
local-mode voice.

## Architecture (the user's framing)

> personality stack and the body is a vessel

- **Body (Anima on Mabu)** — sensorimotor reflexes that must be local: camera,
  face detection, attention/gaze, motors, blinks, mic capture, speaker.
- **Brain (PC, 10.0.0.49)** — cognition over the LAN, streaming-first:
  ASR (WS), LLM (SSE), TTS (HTTP/PCM). RTX 4090 runs all of it in real time.

## PC brain servers

| Server | Dir | Port | Start |
|---|---|---|---|
| llama-server (LLM) | `C:\Users\user\Tools\llama-server\` | 8080 | `run-server.ps1` |
| WhisperLive (ASR)  | `pc-brain/whisperlive` | 9090 | `pc-brain/run-whisperlive.ps1` |
| Chatterbox (TTS)   | `pc-brain/chatterbox`  | 8123 | `pc-brain/run-chatterbox.ps1` |

Each is GPU 0, M6000 hidden via `CUDA_VISIBLE_DEVICES=0`. Setup scripts:
`pc-brain/setup-whisperlive.ps1`, `pc-brain/setup-chatterbox.ps1` (each makes a
gitignored venv). **Firewall**: every new server port needs an inbound allow
rule (Private profile) or the device connect times out — see the
`pc-brain-firewall` memory. python313's inbound block rules were disabled.

## Driving the app over ADB (no buttons)

A debug `BroadcastReceiver` (`MainActivity.registerDebugReceiver`). Broadcasts
**must** target the package (`-p com.mabu.anima`) or Android 8.0+ drops them.

```
adb shell "am broadcast -a com.mabu.anima.SAY   -p com.mabu.anima --es text 'how are you today?'"
adb shell "am broadcast -a com.mabu.anima.SPEAK -p com.mabu.anima --es text 'hello there'"
adb shell "am broadcast -a com.mabu.anima.MODE  -p com.mabu.anima --es mode PUPPET"
adb shell "am broadcast -a com.mabu.anima.STOP  -p com.mabu.anima"
```
SAY = full LLM→TTS path (no mic needed); SPEAK = TTS only; MODE = FOLLOW/PUPPET/
IDLE/SLEEP; STOP = cancel stream + speech.

## Next steps / open directions

1. **Pipecat migration (DECIDED, in progress)** — the proper fix for
   "it interrupts me". Move orchestration to **Pipecat** on the PC (Silero VAD +
   **SmartTurn** semantic turn detection + automatic barge-in). Going **e2e**
   (no browser-first step); device uses the **official Pipecat Kotlin SDK +
   SmallWebRTC**. This lets us DELETE the current ASR hacks rather than port them:
   the 800 ms silence debounce, `consumedEnd` timestamp filtering, the
   mute-while-speaking echo guard + watchdog, and reconnect plumbing all go away
   (SmartTurn handles endpointing; **WebRTC AEC** handles echo). The device
   shrinks to reflexes (camera/motors/overlay) + a thin Pipecat audio client.
   **Retire**: `RemoteAsr.kt`, `RemoteTts.kt`, `StreamingLlama.kt` (kept in git /
   local fallback) and the standalone **WhisperLive** server (Pipecat does STT
   in-process). **Keep**: llama-server (Pipecat LLM points at it) + Chatterbox
   (custom Pipecat TTS service over HTTP).
   - **Phase 1 DONE (2026-05-29)**: `pc-brain/pipecat_bot.py` + `setup-pipecat.ps1`
     + `run-pipecat.ps1`. Brain runs on the PC; **browser-test it at
     `http://localhost:7860`** (mic → Mabu, with turn-taking + barge-in). WebRTC
     offer endpoint at `/api/offer`. Gotchas baked in: `PYTHONUTF8=1` (runner
     emoji banner crashes cp1252 otherwise); correct deps are
     `pipecat-ai[silero,webrtc,whisper,openai,local-smart-turn]` + `fastapi`
     `uvicorn` `pipecat-ai-prebuilt` (NOT `-small-webrtc-prebuilt`). SmartTurn =
     `LocalSmartTurnAnalyzerV3`. `SmallWebRTCConnection.send_app_message` is the
     PC->device control channel for agentic tools.
   - **Phase 2 (in progress)**: Android side.
     - **DONE**: dep `ai.pipecat:small-webrtc-transport:1.1.0` (mavenCentral,
       pulls `ai.pipecat:client:1.1.0`) added to `app/build.gradle.kts`. The
       make-or-break risk is CLEARED: app builds clean, minSdk 24 ≤ our API 27,
       and the APK packages `libjingle_peerconnection_so.so` under
       **armeabi-v7a** — WebRTC runs on the RK3288. (Published latest is 1.1.0;
       the repo's main is an unreleased 1.2.0.)
     - **NEXT**: write a `PipecatClient` wrapper (Kotlin) using
       `SmallWebRTCTransport(context)` + `PipecatClient`, connect to
       `http://10.0.0.49:7860/api/offer` (add a firewall allow rule for 7860).
       The SDK owns mic capture + speaker + **AEC** + the WebRTC data channel
       (`onAppMessage` for agentic tools). Wire into `MainActivity` streaming
       mode; mute button → SDK mic enable/disable. Then retire `RemoteAsr.kt` /
       `RemoteTts.kt` / `StreamingLlama.kt` + the standalone WhisperLive server.
       API: `PipecatClient(SmallWebRTCTransport(context), PipecatClientOptions(
       callbacks = object: PipecatEventCallbacks(){...}))`; connect via the
       offer endpoint. Core repo: github.com/pipecat-ai/pipecat-client-android.
2. **Uncensored / idiosyncratic LLM** — trivial swap: point `run-server.ps1` at
   a different GGUF. Candidates: abliterated Qwen 2.5/3 7B (drop-in, same prompt
   format), Dolphin-Llama3, or a 24B (Venice/Dolphin-Mistral) — the 4090 has room.
3. **Custom Mabu voice** — drop a `voice.wav` next to `chatterbox_server.py`.
4. **Settings-panel UI** for cognitionMode + the three server URLs (currently
   prefs/defaults: `llmServerUrl`, `asrServerUrl`, `ttsServerUrl`).
5. **Persona** — sharpen `MABU_PERSONA` (still a bit "how can I help you today?").
6. **Single orchestrator socket** — collapse the 3 LAN connections into one
   (Pipecat adoption would do this naturally).
7. **TEMP**: default mode is `SLEEP` (in `onCreate`) while iterating on audio so
   the robot sits still — revert to `Mode.FOLLOW` when done.

## Agentic tool-calling (roadmap, rides on Pipecat)

Goal: ask Mabu to *do* things — "go to sleep", "enter puppet mode", "launch the
music app", "clone my voice". Mechanism = LLM function/tool calling (Qwen + its
abliterated variants support it; llama-server exposes the OpenAI tools API).
**Pipecat has first-class function calling** (`register_function`), which is a
major reason to migrate — a tool becomes a JSON schema + a handler.

The body/brain split decides where a tool runs:
- **Body actions** (mode/sleep/puppet, motors, volume, launch app) — the PC LLM
  emits the call; Pipecat sends it over the **WebRTC data channel**; the Android
  client executes it. This promotes the existing debug `BroadcastReceiver`
  (MODE/SLEEP/STOP) into the real PC->device control surface.
- **Brain actions** (swap model, change persona, lookups) — handler runs on PC.
- **Guided flows** — e.g. **clone voice**: tool starts a sub-flow; Mabu reads a
  prompt sentence, the device records ~8 s, uploads to PC as `chatterbox/voice.wav`,
  Chatterbox hot-reloads the voice, Mabu replies in the cloned voice. (Add a
  "reload voice" endpoint to `chatterbox_server.py` so no restart is needed.)

Principles: a single tool registry (name->schema->handler, split body/brain);
confirm outward/irreversible actions; tools express *intent* and the reflex
layer decides how to animate it (don't add server hops to reflexes).

Phasing: Pipecat Phase 1 (PC pipeline) -> Phase 2 (Android Pipecat client +
data-channel action dispatcher) -> Phase 3 tools, starting with `set_mode` as
the proof, then `launch_app`, then the `clone_voice` guided flow.

## Key files

| File | Purpose |
|---|---|
| `app/.../RemoteAsr.kt` | Always-on WhisperLive WS client. VAD endpoint + timestamp-filtered segments (discrete utterances) + reconnect. |
| `app/.../RemoteTts.kt` | Chatterbox client. Pipelined synth→AudioTrack playback, in order; reports speaking for the echo guard. |
| `app/.../StreamingLlama.kt` | llama-server SSE client. Sentence chunking + history. Guards org.json `optString` "null". |
| `app/.../MainActivity.kt` | Dispatch: `onTranscript`→`respondStreaming` (RemoteTts) or `respondLocal` (Pico). Echo-guard mute + watchdog. Debug receiver. |
| `app/.../TtsHelper.kt` | Pico wrapper (local-mode fallback only). Speaking-state listener + sanitize. |
| `app/.../AsrEngine.kt` | Vosk (local fallback). Don't `recognizer.reset()` after `stop()` — SIGSEGVs. |
| `app/.../LlamaInference.kt` | On-device LLM (local fallback). Pinned llama.cpp `07eaf919e`. |
| `pc-brain/chatterbox_server.py` | FastAPI TTS: POST /tts → int16 PCM @24kHz; optional voice.wav clone. |

## Hardware / setup specifics

- **Unit 4** (10.0.0.69) — eyelid scale inverted, EUD inverted, neck-rotation
  inverted (`NECK_ROT_SIGN = -1`). All in `TuningSettings`. Other unit → recalibrate.
- **PC** (10.0.0.49) — RTX 4090 (GPU 0), Quadro M6000 (GPU 1), CUDA 13.0,
  driver 580.97. Python 3.13. WiFi ADB to the device is stable (drops = robot moved).
- **Mabu** — RK3288 / ARMv7 / Android 8.1 / 2 GB RAM. 32-bit VA fragments fast.
- **No Google Play Services** (Esper wiped). GMS-dependent features won't work.

## Things the user values

- **Streaming over batch**, every direction.
- **Hands-free, no push-to-talk** — always listening; mute is optional.
- **Non-corporate, idiosyncratic personality** — open to uncensored/abliterated
  models and a cloned voice.
- **Body / brain split** — the on-device reflex layer is sacred; cognition on the LAN.
- **Per-push explicit approval for `git push`** (global CLAUDE.md). Commits are fine.

## Don't break

- Motor sign flips in `TuningSettings` are physical install calibration.
  "Reset tuning" preserves them; "Reset all" wipes them. Keep that split.
- `gazeYOffset` is applied universally in the gaze tick. Keep it there.
- The package rename couples to JNI: native symbols are `Java_com_mabu_anima_*`
  in `cpp/serial.c` + `cpp/llama_jni.cpp`. Renaming the package again means
  renaming those too (else `UnsatisfiedLinkError` on motor/LLM native calls).
- `mabu-app/` (Python prototype) deprecated; keep unless asked.
- On-device llama.cpp pinned to `07eaf919e` (`setup-llama.ps1`); master faults on
  ARMv7 (`GGML_RMS_NORM_FUSE_OP_MUL`).
- `AdbShellBridge` is the motor-port fallback when SELinux blocks `/dev/ttyS1`;
  see the repo-root `selinux/` policy. `MabuMotors.sleepPose()` exists (not wired).
