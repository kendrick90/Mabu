"""
Anima's brain as a single Pipecat pipeline (replaces the device-side RemoteAsr/
RemoteTts/StreamingLlama orchestration + the standalone WhisperLive server).

  SmallWebRTC <-> Silero VAD -> SmartTurn v3 -> faster-whisper STT
              -> llama-server (LLM) -> Chatterbox (custom TTS) -> SmallWebRTC

Pipecat handles VAD, semantic turn-taking, and barge-in, so the old endpointing
hacks go away. STT runs in-process (faster-whisper, reuses the cached
large-v3-turbo). LLM points at our existing llama-server (OpenAI-compatible).
TTS calls our existing Chatterbox HTTP server. WebRTC's AEC removes Mabu's own
voice from the mic, so no manual mute-while-speaking is needed.

Run via run-pipecat.ps1 (sets GPU 0 + server URLs). The dev runner serves a
browser test UI + the WebRTC offer endpoint that the Pipecat Android SDK
(SmallWebRTC transport) also connects to.
"""
import os

import aiohttp
from loguru import logger

from pipecat.audio.turn.smart_turn.local_smart_turn_v3 import LocalSmartTurnAnalyzerV3
from pipecat.audio.vad.silero import SileroVADAnalyzer
from pipecat.frames.frames import TTSAudioRawFrame, TTSStartedFrame, TTSStoppedFrame
from pipecat.pipeline.pipeline import Pipeline
from pipecat.pipeline.runner import PipelineRunner
from pipecat.pipeline.task import PipelineParams, PipelineTask
from pipecat.processors.aggregators.llm_context import LLMContext
from pipecat.processors.aggregators.llm_response_universal import (
    LLMContextAggregatorPair,
    LLMUserAggregatorParams,
)
from pipecat.runner.run import main
from pipecat.runner.utils import create_transport
from pipecat.services.openai.llm import OpenAILLMService
from pipecat.services.tts_service import TTSService
from pipecat.transports.base_transport import TransportParams

from whisperlive_stt import WhisperLiveSTTService
from persona_manager import PersonaManager
from persona_control import PersonaControl

LLAMA_URL = os.environ.get("LLAMA_URL", "http://localhost:8080/v1")
LLM_MODEL = os.environ.get("LLM_MODEL", "qwen2.5-7b-instruct")
CHATTERBOX_URL = os.environ.get("CHATTERBOX_URL", "http://localhost:8123")
TTS_SAMPLE_RATE = int(os.environ.get("TTS_SAMPLE_RATE", "24000"))
# Streaming STT backend (Option 1): the existing WhisperLive WS server. Loopback
# on the PC, so no firewall rule needed. Start it with run-whisperlive.ps1.
WHISPERLIVE_URL = os.environ.get("WHISPERLIVE_URL", "ws://localhost:9090")
# Persona store lives in the repo (gitignored -- holds per-persona memory).
PERSONAS_DIR = os.environ.get(
    "PERSONAS_DIR", os.path.join(os.path.dirname(os.path.abspath(__file__)), "personas")
)

MABU_PERSONA = (
    "You are Mabu, a small yellow social robot watching the user from a tabletop. "
    "Speak in one or two short sentences -- warm, curious, a bit quirky. "
    "Never lecture or hedge. If you don't know, say so briefly."
)


class ChatterboxTTSService(TTSService):
    """Custom Pipecat TTS service that streams PCM from our Chatterbox server."""

    def __init__(self, base_url: str, sample_rate: int = 24000, **kwargs):
        super().__init__(sample_rate=sample_rate, **kwargs)
        self._base_url = base_url.rstrip("/")

    def can_generate_metrics(self) -> bool:
        return True

    async def run_tts(self, text: str, context_id: str = ""):
        logger.debug(f"[chatterbox] synth: {text!r}")
        await self.start_ttfb_metrics()
        yield TTSStartedFrame()
        try:
            timeout = aiohttp.ClientTimeout(total=60)
            async with aiohttp.ClientSession(timeout=timeout) as session:
                async with session.post(f"{self._base_url}/tts", json={"text": text}) as resp:
                    if resp.status != 200:
                        logger.error(f"[chatterbox] HTTP {resp.status}")
                        return
                    first = True
                    async for chunk in resp.content.iter_chunked(8192):
                        if not chunk:
                            continue
                        if first:
                            await self.stop_ttfb_metrics()
                            first = False
                        yield TTSAudioRawFrame(
                            audio=chunk, sample_rate=self.sample_rate, num_channels=1
                        )
        except Exception as e:
            logger.error(f"[chatterbox] synth failed: {e}")
        finally:
            yield TTSStoppedFrame()


async def run_pipeline(transport):
    # Streaming STT via WhisperLive (partials + finals), replacing the segmented
    # in-process WhisperSTTService. Pipecat VAD/SmartTurn still own turn-taking.
    stt = WhisperLiveSTTService(url=WHISPERLIVE_URL, model="large-v3-turbo", language="en")
    # stop on "<|im_end|>": with the forced ChatML template, Nemo-based models
    # (Rocinante) emit the literal "<|im_end|>" as text instead of halting (the
    # token isn't native to their tokenizer), so it leaked into the TTS. llama-
    # server honours the OpenAI `stop` param. Harmless for Qwen (also ChatML).
    # Temperature 0.8 suits the RP/character model.
    llm = OpenAILLMService(
        base_url=LLAMA_URL,
        api_key="local",
        model=LLM_MODEL,
        params=OpenAILLMService.InputParams(
            temperature=0.8,
            extra={"stop": ["<|im_end|>"]},
        ),
    )
    tts = ChatterboxTTSService(base_url=CHATTERBOX_URL, sample_rate=TTS_SAMPLE_RATE)

    # In Pipecat 1.x, VAD / user-turn detection lives on the USER AGGREGATOR, not
    # the transport. Without vad_analyzer here, no VADController is created, so
    # UserStartedSpeaking never fires and voice never reaches STT (text/SAY still
    # works because it bypasses VAD). This is THE fix for "speech not transcribing".
    # Default VAD params (confidence 0.7 / min_volume 0.6) -- these reject the
    # near-silence that made Whisper hallucinate "Thank you". The user aggregator
    # owns VAD/turn detection in Pipecat 1.x, so this is the analyzer that counts.
    vad = SileroVADAnalyzer()

    # Persona store: seed the context from the ACTIVE persona (its system prompt
    # + its own saved memory), not the hard-coded MABU_PERSONA. PersonaControl
    # then handles voice commands ("become X", "new persona", ...) and swaps the
    # live context. MABU_PERSONA only seeds the default persona on first run.
    personas = PersonaManager(PERSONAS_DIR, MABU_PERSONA)
    active = personas.active()
    seed = [{"role": "system", "content": active["prompt"]}] + (active.get("memory") or [])
    logger.info(f"[persona] active = {active.get('name')} (of {personas.display_names()})")

    context = LLMContext(seed)
    aggregators = LLMContextAggregatorPair(
        context,
        user_params=LLMUserAggregatorParams(vad_analyzer=vad),
    )
    persona_ctl = PersonaControl(personas, LLAMA_URL, stop_tokens=["<|im_end|>"])

    pipeline = Pipeline([
        transport.input(),
        stt,
        aggregators.user(),
        persona_ctl,          # intercepts persona commands before the LLM
        llm,
        tts,
        transport.output(),
        aggregators.assistant(),
    ])

    task = PipelineTask(pipeline, params=PipelineParams(allow_interruptions=True))

    runner = PipelineRunner(handle_sigint=False)
    await runner.run(task)


async def bot(runner_args):
    transport_params = {
        "webrtc": lambda: TransportParams(
            audio_in_enabled=True,
            audio_out_enabled=True,
            vad_analyzer=SileroVADAnalyzer(),
            turn_analyzer=LocalSmartTurnAnalyzerV3(),
        ),
    }
    transport = await create_transport(runner_args, transport_params)
    await run_pipeline(transport)


if __name__ == "__main__":
    main()
