"""Headless brain benchmark for Mabu -- drive STT -> LLM -> TTS without a device
or anyone speaking, so we can find where latency/drops come from and iterate.

What it does per iteration:
  1. TTS a fixed prompt with Chatterbox -> a clean speech clip (this also times
     TTS warm latency). Resample 24k->16k and save as the STT input clip.
  2. STT: stream that clip to WhisperLive, time to final transcript.
  3. LLM: send the transcript to llama-server, time to first token + full reply.
  4. TTS: synth the reply with Chatterbox, time TTFB + total, save the reply clip.
Samples GPU VRAM around each stage, prints a per-stage table, and flags failures
(empty transcript, empty reply, errors). Loop with --iters to see if latency
degrades across runs (VRAM thrash) -- the suspected issue when the 4090 is maxed.

Run in the pipecat venv (has aiohttp + websockets + numpy):
  pc-brain\pipecat\.venv\Scripts\python.exe bench.py --iters 3
Clips land in pc-brain/bench_clips/ (gitignored) for you to play back.

Talks DIRECTLY to the three servers (not the pipecat bot), so it isolates the
brain from the device/WebRTC path. Add load awareness: it competes for the same
GPU, so numbers reflect real contention.
"""
import argparse
import asyncio
import json
import subprocess
import time
import wave
from pathlib import Path

import aiohttp
import numpy as np
import websockets

LLAMA_URL = "http://localhost:8080/v1"
CHATTERBOX_URL = "http://localhost:8123"
WHISPERLIVE_URL = "ws://localhost:9090"
LLM_MODEL = "rocinante"

PROMPT = "Hey Mabu, tell me something interesting about the ocean."
SYSTEM = "You are Mabu, a small yellow social robot. Reply in one or two short sentences."

CLIPS = Path(__file__).resolve().parent / "bench_clips"
CLIPS.mkdir(exist_ok=True)


def vram():
    """(used_MiB, free_MiB) on GPU 0, or (None, None)."""
    try:
        out = subprocess.check_output(
            ["nvidia-smi", "--query-gpu=memory.used,memory.free",
             "--format=csv,noheader,nounits", "-i", "0"], text=True
        ).strip().splitlines()[0]
        u, f = (int(x) for x in out.split(","))
        return u, f
    except Exception:
        return None, None


def resample_16k(pcm_bytes: bytes, src_sr: int) -> bytes:
    """int16 PCM @ src_sr -> int16 PCM @ 16000 (linear; fine for STT)."""
    a = np.frombuffer(pcm_bytes, dtype=np.int16).astype(np.float32)
    if src_sr == 16000 or a.size == 0:
        return a.astype(np.int16).tobytes()
    n_out = int(round(a.size * 16000 / src_sr))
    x_old = np.linspace(0, 1, a.size, endpoint=False)
    x_new = np.linspace(0, 1, n_out, endpoint=False)
    return np.interp(x_new, x_old, a).astype(np.int16).tobytes()


def save_wav(path: Path, pcm: bytes, sr: int):
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr); w.writeframes(pcm)


async def tts(session, text, _retry=True):
    """POST Chatterbox /tts -> (pcm_bytes, sample_rate, ttfb_s, total_s)."""
    t0 = time.monotonic(); ttfb = None; chunks = []; sr = 24000
    try:
        async with session.post(f"{CHATTERBOX_URL}/tts", json={"text": text}) as r:
            sr = int(r.headers.get("X-Sample-Rate", "24000"))
            async for c in r.content.iter_chunked(8192):
                if c:
                    if ttfb is None:
                        ttfb = time.monotonic() - t0
                    chunks.append(c)
    except (aiohttp.ServerDisconnectedError, aiohttp.ClientError) as e:
        if _retry:
            await asyncio.sleep(0.5)
            return await tts(session, text, _retry=False)
        print(f"    [tts error after retry] {type(e).__name__}: {e}")
    return b"".join(chunks), sr, (ttfb or 0.0), time.monotonic() - t0


async def stt(pcm16k: bytes):
    """Stream 16k PCM to WhisperLive, return (transcript, seconds)."""
    import uuid
    uid = str(uuid.uuid4())
    t0 = time.monotonic()
    transcript = ""
    try:
        async with websockets.connect(f"{WHISPERLIVE_URL}/", max_size=None) as ws:
            await ws.send(json.dumps({"uid": uid, "language": "en", "task": "transcribe", "model": "large-v3-turbo"}))
            async for m in ws:                       # wait for SERVER_READY
                if json.loads(m).get("message") == "SERVER_READY":
                    break
            step = 4096                              # stream ~128ms frames
            for i in range(0, len(pcm16k), step):
                await ws.send(pcm16k[i:i + step])
                await asyncio.sleep(0.005)
            await ws.send(b"END_OF_AUDIO")
            last = time.monotonic()                  # collect until quiet or close
            while time.monotonic() - last < 2.0:
                m = await asyncio.wait_for(ws.recv(), timeout=2.0)
                d = json.loads(m)
                if "segments" in d:
                    txt = " ".join(s.get("text", "").strip() for s in d["segments"]).strip()
                    if txt:
                        transcript = txt; last = time.monotonic()
    except (asyncio.TimeoutError, websockets.exceptions.ConnectionClosed):
        pass   # server finalized + closed, or went quiet -- use what we have
    return transcript.strip(), time.monotonic() - t0


async def llm(session, user_text):
    """Non-streaming chat; return (reply, seconds)."""
    t0 = time.monotonic()
    body = {"model": LLM_MODEL, "messages": [
        {"role": "system", "content": SYSTEM}, {"role": "user", "content": user_text}],
        "max_tokens": 120, "temperature": 0.8, "stop": ["<|im_end|>"]}
    async with session.post(f"{LLAMA_URL}/chat/completions", json=body) as r:
        d = await r.json()
    reply = d["choices"][0]["message"]["content"].strip() if d.get("choices") else ""
    return reply, time.monotonic() - t0


async def one_iter(session, i):
    print(f"\n--- iteration {i} ---  VRAM start: {vram()[0]} MiB used / {vram()[1]} free")
    # 1. make the input clip (TTS the prompt)
    pcm, sr, ttfb, tot = await tts(session, PROMPT)
    save_wav(CLIPS / "input_24k.wav", pcm, sr)
    pcm16 = resample_16k(pcm, sr)
    save_wav(CLIPS / "input_16k.wav", pcm16, 16000)
    print(f"  [prep-TTS] {tot:5.2f}s (ttfb {ttfb:.2f}) -> {len(pcm16)//2/16000:.1f}s clip")
    # 2. STT
    transcript, t_stt = await stt(pcm16)
    print(f"  [STT]      {t_stt:5.2f}s -> {transcript!r}")
    if not transcript:
        print("  ** STT returned EMPTY -- this is a dropped turn **")
    # 3. LLM
    reply, t_llm = await llm(session, transcript or PROMPT)
    print(f"  [LLM]      {t_llm:5.2f}s -> {reply[:80]!r}")
    if not reply:
        print("  ** LLM returned EMPTY **")
    # 4. TTS the reply
    rpcm, rsr, rttfb, rtot = await tts(session, reply or "Sorry, I have nothing to say.")
    save_wav(CLIPS / f"reply_{i}.wav", rpcm, rsr)
    print(f"  [reply-TTS]{rtot:5.2f}s (ttfb {rttfb:.2f}) -> reply_{i}.wav")
    total = tot + t_stt + t_llm + rtot
    print(f"  TOTAL chain: {total:5.2f}s   VRAM end: {vram()[0]} MiB used / {vram()[1]} free")
    return dict(prep=tot, stt=t_stt, llm=t_llm, tts=rtot, total=total,
                stt_empty=not transcript, llm_empty=not reply)


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--iters", type=int, default=3)
    args = ap.parse_args()
    print(f"Brain benchmark: {args.iters} iters. Clips -> {CLIPS}")
    timeout = aiohttp.ClientTimeout(total=120)
    rows = []
    async with aiohttp.ClientSession(timeout=timeout) as session:
        for i in range(1, args.iters + 1):
            try:
                rows.append(await one_iter(session, i))
            except Exception as e:
                print(f"  ITER {i} FAILED: {type(e).__name__}: {e}")
    if rows:
        def col(k): return [r[k] for r in rows]
        print("\n==== summary (seconds) ====")
        for k in ("prep", "stt", "llm", "tts", "total"):
            v = col(k)
            print(f"  {k:6}: min {min(v):5.2f}  med {sorted(v)[len(v)//2]:5.2f}  max {max(v):5.2f}")
        drops = sum(1 for r in rows if r["stt_empty"] or r["llm_empty"])
        print(f"  dropped (empty STT or LLM): {drops}/{len(rows)}")


if __name__ == "__main__":
    asyncio.run(main())
