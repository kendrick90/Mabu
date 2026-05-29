# Handoff — mid-pivot to streaming consciousness

This doc captures where we are mid-task, the architectural direction, and
the immediate next steps. Read this *before* the rest of the README.

## What's running right now

- **Mabu app on unit 4 (10.0.0.69)** — face tracking, modes, motors,
  Vosk ASR, on-device llama.cpp + Qwen 2.5 0.5B, Pico TTS all work as a
  full local loop. ~10–15 s per turn.
- **llama-server on the PC (10.0.0.49:8080)** — pre-built b9393 CUDA 13.3
  binaries under `C:\Users\user\Tools\llama-server\`. Serving
  Qwen 2.5 7B Instruct Q4_K_M from `C:\Users\user\Tools\downloads\`.
  GPU 0 (RTX 4090); Quadro M6000 hidden via `CUDA_VISIBLE_DEVICES=0`.
  Start with `run-server.ps1` in that directory.
- **Mabu side, streaming LLM path** — `StreamingLlama.kt` + OkHttp SSE
  client + sentence-boundary chunking + per-sentence TTS queue. Built
  and installed but **not yet verified end-to-end on the device.**

`TuningSettings.cognitionMode` defaults to `"streaming"`; the local
on-device LLM is still loaded at startup as a fallback. The user can
flip back to local via that setting (no UI for it yet — set via prefs
or wire a settings-panel control).

## The architectural target (in the user's words)

> personality stack and the body is a vessel

- **Body (Mabu)** — sensorimotor reflexes that have to be local: camera,
  face detection, attention tracking, motors, gaze, blinks, mic capture,
  speaker. Stays on-device because of latency.
- **Brain (PC)** — cognition: LLM, ASR, eventually TTS. Remote because
  the on-device CPU is too slow / dumb for it. RTX 4090 chews through it
  in real time.
- **Two modes**, selectable per session:
  - `local` — everything on Mabu, no network needed
  - `streaming` — remote brain over LAN, streaming primary (SSE for LLM,
    WS for ASR, chunked HTTP / WS for TTS)

WebSocket / SSE are the wire format from day one — don't fall back to
HTTP request/response if streaming is feasible.

## Immediate next steps (priority order)

1. **Verify streaming LLM end-to-end** — user holds mic, expects to hear
   Qwen 2.5 7B's reply via Pico TTS, with each sentence speaking as soon
   as it's parsed (not waiting for the full response). Watch logcat:
   `MabuStreamLLM`, `MabuFaceOverlay: mabu sentence`. If the SSE client
   has issues, the most likely culprits are URL routing (10.0.0.49 from
   Mabu), OkHttp SSE event parsing edge cases, or token JSON shape
   surprises from llama-server.
2. **Skip the local-LLM preload when `cognitionMode == "streaming"`** —
   it's eating ~470 MB of VA for nothing. Trivial guard in
   `MainActivity.onCreate`.
3. **Whisper server on PC** — build `whisper.cpp` with CUDA, run the
   `server` example with `ggml-large-v3-turbo` on GPU 0. Test from PC
   with `curl` and a wav file.
4. **`RemoteAsr.kt` on Mabu** — `AudioRecord` → PCM frames over
   WebSocket → server returns partial + final transcripts. Swap into
   `MainActivity` when in streaming mode; Vosk stays as the local
   fallback.
5. **Piper TTS server + `RemoteTts.kt`** — better voice than Pico.
   Could also do XTTS-v2 for a real "Mabu voice" via voice cloning.
6. **Settings panel UI for cognition mode + server URL** — currently
   you'd have to edit `TuningSettings` defaults or push via
   SharedPreferences manually.
7. **Eventually**: collapse the three connections into a single
   orchestrator WebSocket (FastAPI on PC) so Mabu only needs one
   socket and the server side coordinates the pipeline. Worth doing
   once we've validated the pieces independently.

## Files to know

| File | Purpose |
|---|---|
| `app/src/main/java/com/mabu/faceoverlay/StreamingLlama.kt` | Just-added SSE client for `llama-server`. Sentence boundary detection + history. |
| `app/src/main/java/com/mabu/faceoverlay/MainActivity.kt` | `onTranscript` dispatches to `respondStreaming` or `respondLocal` based on `tuning.cognitionMode`. |
| `app/src/main/java/com/mabu/faceoverlay/TuningSettings.kt` | `cognitionMode`, `llmServerUrl` added at the bottom. |
| `app/src/main/java/com/mabu/faceoverlay/TtsHelper.kt` | `speak(text, volume, queueAdd)` — pass `queueAdd=true` for the second+ sentences in a stream so they don't FLUSH each other. |
| `app/src/main/java/com/mabu/faceoverlay/LlamaInference.kt` | On-device fallback. Pinned llama.cpp commit `07eaf919e` (the next commit broke ARMv7 with a fused RMS-norm op). |
| `app/src/main/java/com/mabu/faceoverlay/AsrEngine.kt` | Vosk wrapper. **Do not** call `recognizer.reset()` after `speechService.stop()` — that races and SIGSEGVs Vosk's audio thread on this build. |
| `setup-llama.ps1` | Re-clones llama.cpp at the pinned commit. |
| `C:\Users\user\Tools\llama-server\run-server.ps1` | Starts llama-server on the PC. |

## Hardware / setup specifics this code expects

- **Unit 4** (10.0.0.69) — eyelid scale inverted, EUD inverted,
  neck-rotation inverted (`NECK_ROT_SIGN = -1`). All in
  `TuningSettings` defaults. Different unit → re-run calibration via
  settings panel sign flips and `gazeYOffset` slider.
- **PC** (10.0.0.49) — RTX 4090 on GPU 0, Quadro M6000 on GPU 1,
  CUDA 13.0 toolkit + driver 580.97.
- **Mabu** is RK3288 / ARMv7 / Android 8.1 / 2 GB RAM. On 32-bit ARM,
  the process VA fragments fast — load big things (LLM model) first.
- **Pico TTS** ignores `TextToSpeech.Engine.KEY_PARAM_VOLUME`; we set
  `STREAM_MUSIC` directly. Pico also crashes on emoji / smart quotes /
  long inputs; `TtsHelper.sanitize` strips those.
- **No Google Play Services** on Mabu (Esper was wiped). Any feature
  that depends on GMS won't work.

## Things the user values

- **Streaming over batch**, from the start, in all directions.
- **Non-standard bot personality** — the LLM should feel idiosyncratic,
  not corporate-assistant. The system prompt + future fine-tune both
  matter. Current `MABU_PERSONA` in `MainActivity.companion` is the
  starting point.
- **Body / brain split** — the on-device reflex layer is sacred (don't
  add server hops to it); the cognitive layer is fair game for the LAN.
- **Per-push explicit approval for `git push`** — the user's global
  CLAUDE.md requires it. Commits without ask are fine; pushes are not.

## Don't break

- The motor sign flips in `TuningSettings` are physical install
  calibration. **"Reset tuning"** preserves them; **"Reset all"** wipes
  them. Don't change which bucket they live in.
- The `gazeYOffset` is applied universally in the gaze tick (not in
  any source-specific path). Keep it there.
- `mabu-app/` (Python prototype) is deprecated but kept as a reference;
  don't delete unless the user asks.
- The on-device llama.cpp is pinned to `07eaf919e` via `setup-llama.ps1`.
  Master will fault on ARMv7 with `GGML_RMS_NORM_FUSE_OP_MUL`. If the
  user wants a newer version, they need a commit that either pre-dates
  the fusion or has an ARMv7 fix landed.

## Recent upstream additions you may not have seen

Commit `c4464d1` (in origin/main, possibly from a parallel session) added
**`AdbShellBridge`** as a fallback for motor port access: if `SerialPort.
openTty("/dev/ttyS1")` returns a permission error (SELinux blocking
`untrusted_app` from `serial_device`), `MabuMotors` retries via the local
adbd socket, which runs in a context that *can* touch the tty. There's
also a `selinux/` directory at the repo root with the permanent policy
patch. Read those before assuming serial just works on a fresh unit.

`MabuMotors` also gained a `sleepPose()` (eyelids closed, neck slightly
down) that the SLEEP mode can use; not yet wired up.

## Open questions

- **Best Whisper variant for the 4090?** I suggested `large-v3-turbo`
  (smaller, ~6x faster than large-v3, similar quality). User has not
  weighed in yet.
- **TTS upgrade path** — Piper for "just sound better" vs XTTS-v2 for
  voice cloning a real Mabu voice. User's choice.
- **Single orchestrator vs three connections** — I suggested doing the
  three-connection version first to validate each piece, then collapsing
  to one orchestrator socket. User hasn't pushed back on that order.
