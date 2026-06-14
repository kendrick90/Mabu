"""
Chatterbox TTS server for Mabu/Anima's voice.

Loads Chatterbox (Resemble AI) once on the GPU and synthesizes a sentence at a
time, returning raw int16 mono PCM at the model's sample rate. The device
(RemoteTts.kt) POSTs one sentence per request as the LLM streams them, and
plays each via AudioTrack -- so the first sentence speaks while later ones are
still being generated.

Endpoints:
  GET  /health              -> {"status":"ok","sample_rate":<sr>}
  POST /tts                 -> raw int16 LE mono PCM (audio/L16)
       body (JSON): {"text": "...", "exaggeration": 0.5, "cfg_weight": 0.5}

A reference voice clip (voice.wav next to this file) is used for cloning if
present; otherwise Chatterbox's default voice is used. Inference is fully local.
"""
import io
import os
import numpy as np
import torch
from fastapi import FastAPI, Response, UploadFile, File, Form
from fastapi.responses import StreamingResponse, JSONResponse
from pydantic import BaseModel
import uvicorn

from chatterbox.tts import ChatterboxTTS

DEVICE = "cuda" if torch.cuda.is_available() else "cpu"
HERE = os.path.dirname(os.path.abspath(__file__))
# Named voices live in pc-brain/voices/<name>.wav (each a short reference clip
# Chatterbox clones from). A legacy voice.wav next to this file maps to "voice".
VOICES_DIR = os.path.join(HERE, "voices")
os.makedirs(VOICES_DIR, exist_ok=True)
LEGACY_VOICE = os.path.join(HERE, "voice.wav")


def _voice_path(name):
    """Resolve a voice name to a .wav path, or None for Chatterbox's default."""
    if not name or name in ("default", "none"):
        return None
    if name == "voice" and os.path.isfile(LEGACY_VOICE):
        return LEGACY_VOICE
    p = os.path.join(VOICES_DIR, f"{name}.wav")
    return p if os.path.isfile(p) else None


def _list_voices():
    names = [os.path.splitext(f)[0] for f in os.listdir(VOICES_DIR) if f.lower().endswith(".wav")]
    if os.path.isfile(LEGACY_VOICE):
        names.append("voice")
    return ["default"] + sorted(set(names))


print(f"[chatterbox] loading model on {DEVICE} ...", flush=True)
model = ChatterboxTTS.from_pretrained(device=DEVICE)
SR = model.sr
# from_pretrained loads a builtin conds.pt into model.conds -- stash it so we can
# restore the default voice after using a clone.
_DEFAULT_CONDS = model.conds
# Track which voice's conditionals are currently prepared, so we only re-extract
# them when the voice actually changes (not on every sentence).
_current_voice = "default"
print(f"[chatterbox] ready: sr={SR}, voices={_list_voices()}", flush=True)


def _ensure_voice(name):
    """Prepare Chatterbox conditionals for the named voice if it changed."""
    global _current_voice
    if name == _current_voice:
        return
    path = _voice_path(name)
    if path:
        model.prepare_conditionals(path)   # clone from the reference clip
    else:
        model.conds = _DEFAULT_CONDS        # restore the builtin default voice
    _current_voice = name


# Warm up so the first real request isn't slow (CUDA graph / kernel compile).
try:
    _ = model.generate("Hello.")
    print("[chatterbox] warmup done", flush=True)
except Exception as e:
    print(f"[chatterbox] warmup failed (non-fatal): {e}", flush=True)

app = FastAPI()


class TtsReq(BaseModel):
    text: str
    voice: str | None = None
    exaggeration: float = 0.5
    cfg_weight: float = 0.5


@app.get("/health")
def health():
    return JSONResponse({"status": "ok", "sample_rate": SR, "device": DEVICE,
                         "voices": _list_voices()})


@app.get("/voices")
def voices():
    return JSONResponse({"voices": _list_voices()})


@app.post("/clone")
async def clone(name: str = Form(...), file: UploadFile = File(...)):
    """Register a cloned voice: save the uploaded reference clip as
    voices/<name>.wav. Subsequent /tts with voice=<name> will use it."""
    import re
    slug = re.sub(r"[^a-z0-9_-]+", "_", name.strip().lower()).strip("_") or "voice"
    dest = os.path.join(VOICES_DIR, f"{slug}.wav")
    with open(dest, "wb") as f:
        f.write(await file.read())
    global _current_voice
    _current_voice = "\0"  # force re-prepare on next use
    print(f"[chatterbox] cloned voice '{slug}' -> {dest}", flush=True)
    return JSONResponse({"status": "ok", "voice": slug, "voices": _list_voices()})


def _pcm_bytes(text: str, voice, exaggeration: float, cfg_weight: float) -> bytes:
    _ensure_voice(voice or "default")
    with torch.no_grad():
        wav = model.generate(text, exaggeration=exaggeration, cfg_weight=cfg_weight)
    audio = wav.squeeze().detach().cpu().clamp(-1.0, 1.0).numpy()
    return (audio * 32767.0).astype("<i2").tobytes()


@app.post("/tts")
def tts(req: TtsReq):
    text = (req.text or "").strip()
    if not text:
        return Response(status_code=204)
    pcm = _pcm_bytes(text, req.voice, req.exaggeration, req.cfg_weight)

    # Stream the PCM in chunks so the device can start AudioTrack playback as
    # bytes arrive rather than waiting for the whole sentence to transfer.
    def gen():
        step = 16384
        for i in range(0, len(pcm), step):
            yield pcm[i:i + step]

    return StreamingResponse(gen(), media_type="audio/L16",
                             headers={"X-Sample-Rate": str(SR)})


if __name__ == "__main__":
    port = int(os.environ.get("CHATTERBOX_PORT", "8123"))
    uvicorn.run(app, host="0.0.0.0", port=port, log_level="info")
