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
from fastapi import FastAPI, Response
from fastapi.responses import StreamingResponse, JSONResponse
from pydantic import BaseModel
import uvicorn

from chatterbox.tts import ChatterboxTTS

DEVICE = "cuda" if torch.cuda.is_available() else "cpu"
VOICE_PROMPT = os.path.join(os.path.dirname(__file__), "voice.wav")

print(f"[chatterbox] loading model on {DEVICE} ...", flush=True)
model = ChatterboxTTS.from_pretrained(device=DEVICE)
SR = model.sr
HAS_VOICE = os.path.isfile(VOICE_PROMPT)
print(f"[chatterbox] ready: sr={SR}, voice_prompt={'yes' if HAS_VOICE else 'default'}", flush=True)

# Warm up so the first real request isn't slow (CUDA graph / kernel compile).
try:
    _ = model.generate("Hello.", **({"audio_prompt_path": VOICE_PROMPT} if HAS_VOICE else {}))
    print("[chatterbox] warmup done", flush=True)
except Exception as e:
    print(f"[chatterbox] warmup failed (non-fatal): {e}", flush=True)

app = FastAPI()


class TtsReq(BaseModel):
    text: str
    exaggeration: float = 0.5
    cfg_weight: float = 0.5


@app.get("/health")
def health():
    return JSONResponse({"status": "ok", "sample_rate": SR, "device": DEVICE,
                         "voice": "clone" if HAS_VOICE else "default"})


def _pcm_bytes(text: str, exaggeration: float, cfg_weight: float) -> bytes:
    kwargs = {"exaggeration": exaggeration, "cfg_weight": cfg_weight}
    if HAS_VOICE:
        kwargs["audio_prompt_path"] = VOICE_PROMPT
    with torch.no_grad():
        wav = model.generate(text, **kwargs)  # torch tensor, float [-1,1], shape (1,N) or (N,)
    audio = wav.squeeze().detach().cpu().clamp(-1.0, 1.0).numpy()
    return (audio * 32767.0).astype("<i2").tobytes()


@app.post("/tts")
def tts(req: TtsReq):
    text = (req.text or "").strip()
    if not text:
        return Response(status_code=204)
    pcm = _pcm_bytes(text, req.exaggeration, req.cfg_weight)

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
