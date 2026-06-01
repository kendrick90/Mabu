# Brain performance — investigation notes

Symptom: sometimes you speak and get **no response**, or replies are slow / pause
mid-sentence. This doc is how to investigate it fast and iterate to a fix.

## UPDATE (2026-05-31, after fixes): the deeper root cause is a WhisperLive leak

Swapped the LLM 12B -> **Stheno 8B** (`run-all.ps1 -Model stheno`): freed ~9 GB,
LLM latency ~0.5 s, replies clean (Llama-3 `<|eot_id|>` stops natively). Baseline
with the full stack + 1 device connection is now ~15 GB used / **~7-8 GB free**.

But `bench.py` exposed the real culprit: **WhisperLive grows ~1.7 GB of VRAM per
WebSocket connection and never releases it on disconnect.** Killing WhisperLive
alone freed ~10.5 GB after 5 bench connections (a single large-v3-turbo needs
~3 GB). `single_model` is on by default, yet each client still adds ~a model's
worth — so either the flag isn't effective for the faster_whisper backend here,
or per-client CTranslate2/CUDA runtime state isn't freed.

**Why this is THE issue:** every reconnect spins a new STT connection — WiFi
blip, the app's auto-reconnect, an app restart, each pipeline run. So VRAM
climbs over a session until the GPU re-maxes -> dropped/slow responses + the
"loading/unloading" feel. The 8B bought headroom (~5 reconnects of slack) but
does NOT eliminate it.

**Fix candidates (next):**
1. Verify `single_model` actually shares the model for faster_whisper (check the
   server's startup log / add `-sm`); if not effective, patch the server to free
   per-client resources + `torch.cuda.empty_cache()` on disconnect.
2. Reuse ONE WhisperLive connection in the pipecat bot across pipeline
   runs/reconnects (module-level singleton in `whisperlive_stt.py`) instead of a
   fresh connection each time -- caps connection-driven growth.
3. Interim: `stop-all.ps1` + `run-all.ps1` between sessions fully releases it.

bench.py STT note: it returned empty transcripts on synthetic TTS audio (likely
WhisperLive's server VAD filtering it). Harness limitation, not production -- live
device STT works. To benchmark STT, feed a real recorded clip or pass use_vad off.

## Confirmed root cause (2026-05-31)

**The RTX 4090 is VRAM-maxed.** With Rocinante 12B (llama-server) + WhisperLive
(faster-whisper large-v3-turbo) + Chatterbox all on GPU 0:

- `nvidia-smi` shows **~300–600 MiB free of 24 GB** — effectively full.
- Free VRAM **bounces** (e.g. 596 → 2800 → 1128 MiB between bench iterations) =
  something is allocating/releasing under pressure (the "loading/unloading" hunch).
- Under that pressure, **WhisperLive and Chatterbox intermittently drop the
  connection** (seen in `bench.py`: `ConnectionClosedOK`, `ServerDisconnectedError`).
- A live session showed **14 user-turns → 11 LLM responses**: some turns produced
  no reply (dropped, consistent with STT failing/timing out under contention).

The **M6000 (GPU 1) sits idle with 12 GB free** — that's the obvious release valve.

## Tooling

### `bench.py` — headless brain benchmark (no device, no speaking)
Drives the full chain **TTS-a-prompt → STT → LLM → TTS-the-reply**, talking
directly to the three servers (isolates the brain from the device/WebRTC path).
Per-stage latency + VRAM around each stage + drop detection + saved audio clips.

```
pc-brain\pipecat\.venv\Scripts\python.exe bench.py --iters 5
```
- Clips land in `pc-brain/bench_clips/` (gitignored) — `reply_N.wav` to play back.
- Watch the **summary**: per-stage min/med/max seconds + "dropped (empty STT/LLM)".
- Re-run after each change and **compare** — this is the iteration loop.

### Other signals
- `nvidia-smi -l 1` in a window while testing — watch free VRAM live.
- `curl localhost:7861/status` — live persona/voice/state.
- llama-server runs with `--metrics` → Prometheus at `http://localhost:8080/metrics`
  (tokens/s, prompt eval time, KV cache usage).
- Per-server windows (llama / WhisperLive / Chatterbox) show their own logs; the
  pipecat bot log is `pc-brain/pipecat.log.err`.

## Iteration loop

1. `bench.py --iters 5` → record baseline (per-stage med + drop rate + free VRAM).
2. Change **one** thing (below).
3. Restart only the affected server (`stop-all.ps1` + `run-all.ps1`, or the one script).
4. `bench.py --iters 5` → compare. Keep if latency/drops improved without quality loss.

## Fix candidates (free VRAM on the 4090), rough budgets

Ordered by bang-for-buck. **Goal: keep ≥2–3 GB free headroom on GPU 0.**

| Change | Frees on 4090 | Risk / tradeoff |
|---|---|---|
| **Move Chatterbox TTS → M6000 (GPU 1)** | ~3–4 GB | Maxwell is slow for fp16; TTS may be a bit slower. **Test first** — set `CUDA_VISIBLE_DEVICES=1` in `run-chatterbox.ps1`. |
| **Move WhisperLive STT → M6000** | ~2–3 GB | CTranslate2 on Maxwell (compute 5.2) — may need `compute_type=int8` or `float32`; large-v3-turbo may be slow. Test. |
| **Downgrade LLM 12B → 8B** (e.g. abliterated Llama-3.1-8B, or the existing `qwen` 7B) | ~3–5 GB | Less "character", but biggest single win. One line in `models.json` + `run-llm.ps1 <name>`. |
| **Smaller Whisper** (large-v3-turbo → `distil-large-v3` / `small.en`) | ~1–2 GB | Slightly lower STT accuracy; also faster. Set the model in `whisperlive_stt.py` config. |
| **Lower llama ctx 8192 → 4096** | KV cache, ~0.3–1 GB | Shorter memory window. `-CtxSize 4096` to `run-llm.ps1`. |

### Recommended first experiment
Either **(A)** move STT **and** TTS to the M6000 (frees ~5–6 GB on the 4090,
leaving the 12B comfortable), **or (B)** downgrade the LLM to 8B and keep all on
the 4090. (A) preserves Rocinante's quality if Maxwell can run STT/TTS acceptably;
(B) is guaranteed to work but changes the brain. **Validate Maxwell viability for
(A) before committing** — run Chatterbox alone on GPU 1 and `bench.py`; if TTS
latency is unacceptable, fall back to (B).

## Notes
- Always `stop-all.ps1` between runs to fully release VRAM (leftover allocations
  otherwise stack up).
- `bench.py` competes for the same GPU, so its numbers reflect real contention —
  run it *instead of* a live device session when measuring, not alongside.
