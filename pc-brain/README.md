# pc-brain

PC-side "brain" servers for Mabu's streaming-consciousness mode. Mabu (the
RK3288 tablet) keeps the sensorimotor reflexes local; cognition runs here on
the PC and streams over the LAN. See [`../mabu-android/HANDOFF.md`](../mabu-android/HANDOFF.md)
for the architecture.

## Servers

| Server | What | Wire | Port | Notes |
|---|---|---|---|---|
| llama-server | LLM (Qwen 2.5 7B) | SSE `/v1/chat/completions` | 8080 | Lives in `C:\Users\user\Tools\llama-server\`. Prebuilt CUDA binaries. |
| **WhisperLive** | ASR (Whisper) | **WebSocket** | 9090 | This dir. faster-whisper / CTranslate2 on GPU 0. |
| **Chatterbox** | TTS (voice) | HTTP POST `/tts` -> PCM | 8123 | This dir. Resemble AI on GPU 0. Voice cloning + emotion. |

> Each server needs a Windows Firewall inbound allow rule (Private profile) for
> the device to reach it over the LAN -- see the `pc-brain-firewall` note.
> A future step is to collapse these into one **Pipecat** orchestrator (semantic
> turn-taking + barge-in) that wraps these local models behind a single socket.

## WhisperLive (ASR)

Real-time streaming ASR with live partial transcripts. Backend is
**faster-whisper (CTranslate2)** -- an optimized precompiled GPU runner. torch
is installed with CUDA only for device detection + to supply CTranslate2's
cuBLAS/cuDNN DLLs; it is not the inference engine.

### Setup (once)

```powershell
.\setup-whisperlive.ps1
```

Clones WhisperLive into `whisperlive/` (gitignored), makes a venv at
`whisperlive/.venv`, installs CUDA torch + the minimal faster_whisper deps,
and verifies the GPU is visible.

### Run

```powershell
.\run-whisperlive.ps1          # port 9090
.\run-whisperlive.ps1 9091     # custom port
```

Pins to GPU 0 (RTX 4090), hides the Quadro M6000, and accepts raw int16 PCM
(`--raw_pcm_input`) straight from Android's `AudioRecord`.

### Client / model

The model size (e.g. `large-v3-turbo`) is chosen by the **client** in its
WebSocket handshake -- the server loads + caches it on first connect under
`~/.cache/whisper-live/`. On Mabu this client is `RemoteAsr.kt`; for a
quick PC-side smoke test use the bundled `whisperlive/run_client.py`.

## Chatterbox (TTS)

Streaming voice for Mabu. `chatterbox_server.py` loads Chatterbox (Resemble AI)
once on the GPU and serves `POST /tts {text}` -> raw int16 mono PCM @ 24 kHz.
The device (`RemoteTts.kt`) sends one sentence per request as the LLM streams,
and plays each via `AudioTrack`. Inference is fully local.

### Setup (once) + run

```powershell
.\setup-chatterbox.ps1     # venv at chatterbox/.venv, pip chatterbox-tts (no conda)
.\run-chatterbox.ps1       # port 8123, GPU 0
```

### Custom voice (cloning)

Drop a clean ~6-10 s speech sample at `chatterbox_server.py`'s directory as
`voice.wav` and restart -- every reply then uses that cloned voice. Without it,
Chatterbox's default voice is used. `exaggeration` / `cfg_weight` in the POST
body tune emotion.
