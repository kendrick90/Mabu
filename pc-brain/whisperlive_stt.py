"""Streaming STT for Pipecat backed by the existing WhisperLive WS server.

Option 1 of the STT-latency fix: Pipecat's in-process WhisperSTTService is
*segmented* (waits for end-of-turn, then transcribes the whole utterance, no
live partials). WhisperLive is *streaming* -- it transcribes incrementally and
pushes a rolling segment list, so partials appear as you speak and the final is
basically ready by the time you stop. This service reconnects Pipecat to that
proven server (the same one RemoteAsr.kt used) and emits:

  - InterimTranscriptionFrame for the live (not-yet-completed) trailing segment
    -> drives the on-device speech bubble, restoring the snappy feel.
  - TranscriptionFrame for each newly-completed segment -> the user aggregator
    accumulates these and fires the LLM at end-of-turn (Pipecat VAD/SmartTurn
    still own turn-taking + barge-in; WhisperLive is purely the transcriber).

Protocol (WhisperLive whisper_live/server.py, mirrors RemoteAsr.kt):
  - Connect ws://host:9090/  (server must run with --raw_pcm_input)
  - Send config JSON {uid, language, task, model, use_vad, ...}
  - Receive {message:"SERVER_READY"} once the model is loaded
  - Stream raw int16 mono PCM @ 16 kHz as binary frames
  - Receive {segments:[{start,end,text,completed}, ...]} -- a rolling last-N
    list; completed=true are final, the trailing one is the live partial.

This is the quick win. Option 3 (in-process streaming whisper, no separate
server) will be a different STTService behind the same Pipecat seam.
"""
import asyncio
import json
from uuid import uuid4

import websockets
from loguru import logger

from pipecat.frames.frames import (
    CancelFrame,
    EndFrame,
    Frame,
    InterimTranscriptionFrame,
    StartFrame,
    TranscriptionFrame,
)
from pipecat.services.stt_service import STTService
from pipecat.utils.time import time_now_iso8601


class WhisperLiveSTTService(STTService):
    def __init__(
        self,
        *,
        url: str = "ws://localhost:9090",
        model: str = "large-v3-turbo",
        language: str | None = "en",
        hotwords: str | None = None,
        **kwargs,
    ):
        super().__init__(**kwargs)
        self._url = url.rstrip("/")
        self._model = model
        self._language = language
        # hotwords bias Whisper toward a phrase -- great for catching "Mabu" in
        # real speech, but it also makes Whisper HALLUCINATE "Mabu" on silence/
        # noise. Off by default; the VAD speech-gate below is the better lever.
        self._hotwords = hotwords

        self._uid = str(uuid4())
        # Tracked by the base STTService from VAD frames; we gate sending on it
        # so WhisperLive only ever sees actual speech (no silence => no "thank
        # you"/"Mabu" hallucinations between turns).
        self._user_speaking = False
        self._ws = None
        self._receive_task = None
        self._server_ready = asyncio.Event()
        # Max segment end-time (seconds since connect) already emitted as final,
        # so we never re-emit a completed segment from the rolling list.
        self._emitted_end = 0.0

    def can_generate_metrics(self) -> bool:
        return True

    async def start(self, frame: StartFrame):
        await super().start(frame)
        await self._connect()

    async def stop(self, frame: EndFrame):
        await super().stop(frame)
        await self._disconnect()

    async def cancel(self, frame: CancelFrame):
        await super().cancel(frame)
        await self._disconnect()

    async def _connect(self):
        if self._ws:
            return
        try:
            # max_size=None: server sends large rolling JSON; don't cap it.
            self._ws = await websockets.connect(f"{self._url}/", max_size=None)
        except Exception as e:
            logger.error(f"[whisperlive] connect failed ({self._url}): {e}")
            self._ws = None
            return

        config = {
            "uid": self._uid,
            "language": self._language,
            "task": "transcribe",
            "model": self._model,
            "use_vad": True,
            "send_last_n_segments": 10,
            "no_speech_thresh": 0.45,
            "clip_audio": False,
            "same_output_threshold": 5,
        }
        if self._hotwords:
            config["hotwords"] = self._hotwords
        await self._ws.send(json.dumps(config))
        self._server_ready.clear()
        self._emitted_end = 0.0
        self._receive_task = self.create_task(self._receive_loop())
        logger.info(f"[whisperlive] connecting to {self._url} (model={self._model})")

    async def _disconnect(self):
        if self._receive_task:
            await self.cancel_task(self._receive_task)
            self._receive_task = None
        if self._ws:
            try:
                # MUST be bytes: the server checks `frame_data == b"END_OF_AUDIO"`
                # and otherwise np.frombuffer()s it -- a text frame (str) crashes
                # its STT thread ("a bytes-like object is required, not 'str'").
                await self._ws.send(b"END_OF_AUDIO")
            except Exception:
                pass
            try:
                await self._ws.close()
            except Exception:
                pass
            self._ws = None
        self._server_ready.clear()

    async def _receive_loop(self):
        try:
            async for message in self._ws:
                if isinstance(message, bytes):
                    continue  # server sends JSON text only
                try:
                    data = json.loads(message)
                except Exception:
                    continue
                if data.get("uid") and data["uid"] != self._uid:
                    continue
                if data.get("message") == "SERVER_READY":
                    self._server_ready.set()
                    self._emitted_end = 0.0
                    logger.info(f"[whisperlive] SERVER_READY (backend={data.get('backend')})")
                    continue
                if data.get("message") == "DISCONNECT":
                    logger.info("[whisperlive] server DISCONNECT")
                    continue
                if "segments" in data:
                    await self._handle_segments(data["segments"])
        except asyncio.CancelledError:
            raise
        except Exception as e:
            logger.warning(f"[whisperlive] receive loop ended: {e}")

    async def _handle_segments(self, segments):
        """Emit newly-completed segments as finals and the trailing one as interim.

        WhisperLive resends an authoritative rolling list each message, so we key
        off the segment end-time to emit each completed segment exactly once.
        """
        ts = time_now_iso8601()
        last_index = len(segments) - 1
        for i, seg in enumerate(segments):
            text = (seg.get("text") or "").strip()
            if not text:
                continue
            end = float(seg.get("end", 0.0) or 0.0)
            completed = bool(seg.get("completed", False))
            if completed:
                if end > self._emitted_end:
                    self._emitted_end = end
                    await self.push_frame(
                        TranscriptionFrame(text, self._user_id, ts, language=None)
                    )
            elif i == last_index:
                # Live partial -> bubble. Don't advance _emitted_end.
                await self.push_frame(
                    InterimTranscriptionFrame(text, self._user_id, ts, language=None)
                )

    async def run_stt(self, audio: bytes):
        # Stream raw int16 PCM to WhisperLive ONLY while VAD says the user is
        # speaking, so WhisperLive never transcribes the silence between turns
        # (that silence is what produced the "thank you"/"Mabu" hallucinations).
        # self._user_speaking is maintained by the base STTService from the
        # VAD frames it receives. Transcripts arrive asynchronously via the
        # receive loop; this generator yields nothing (streaming-STT idiom).
        if self._ws and self._server_ready.is_set() and self._user_speaking:
            try:
                await self._ws.send(audio)
            except Exception as e:
                logger.warning(f"[whisperlive] send failed: {e}")
        return
        yield  # makes this an async generator without emitting frames
