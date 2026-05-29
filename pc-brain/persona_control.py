"""Voice-driven persona control for Mabu.

Sits between the user aggregator and the LLM. When a completed user turn is a
persona command, it acts on it (and speaks a confirmation) instead of letting
the LLM reply -- by inspecting the LLMContextFrame the aggregator pushes, then
swallowing it. Otherwise the frame passes straight through.

Commands (an optional "Mabu," wake prefix is stripped first):
  - "who can you be?" / "list personas"          -> speak the roster
  - "become <name>" / "switch to <name>" / ...   -> switch (ONLY if <name> is a
        known persona, so normal roleplay like "become a wizard" won't trigger)
  - "new persona" / "let's create a character"   -> enter the design workshop
  - (in workshop) "save it as <name>"            -> distill + save + become it
  - (in workshop) "cancel" / "never mind"        -> scrap, restore previous

Switching swaps the live LLM context: system prompt := persona.prompt, history
:= that persona's saved memory. The outgoing persona's conversation is saved
first, so each character remembers its own history across switches.
"""
import re

import aiohttp
from loguru import logger

from pipecat.frames.frames import Frame, LLMContextFrame, TTSSpeakFrame
from pipecat.processors.frame_processor import FrameDirection, FrameProcessor

DESIGNER_PROMPT = (
    "You are a warm, playful persona designer helping the user invent a new "
    "character for a small yellow social robot named Mabu. Interview them in "
    "short spoken turns -- ask one or two questions at a time about the "
    "character's name, personality, how they talk, and any quirks or backstory. "
    "Keep replies to a sentence or two. When the user seems happy with the "
    "character, remind them they can say 'save it as <name>' and you'll become "
    "that character."
)


class VoiceState:
    """Shared holder for the active persona's Chatterbox voice name. The TTS
    service reads it per request; PersonaControl sets it on every persona change
    so switching persona also switches voice."""

    def __init__(self, name=None):
        self.name = name


class PersonaControl(FrameProcessor):
    def __init__(self, manager, llama_url: str, stop_tokens=None, voice_state=None, **kwargs):
        super().__init__(**kwargs)
        self._mgr = manager
        self._llama_url = llama_url.rstrip("/")
        self._stop = stop_tokens or ["<|im_end|>"]
        self._voice = voice_state  # VoiceState, shared with the TTS service
        self._mode = "normal"  # "normal" | "workshop"
        self._prev_persona = None  # to restore on workshop cancel

    def _apply_voice(self, persona):
        if self._voice is not None:
            self._voice.name = (persona or {}).get("voice")

    async def process_frame(self, frame: Frame, direction: FrameDirection):
        await super().process_frame(frame, direction)
        if isinstance(frame, LLMContextFrame):
            if await self._maybe_handle(frame):
                return  # swallow: the LLM should NOT also reply to a command
        await self.push_frame(frame, direction)

    # --- helpers ----------------------------------------------------------
    def _last_user_text(self, ctx):
        msgs = ctx.messages
        if not msgs or msgs[-1].get("role") != "user":
            return None
        c = msgs[-1].get("content")
        return c if isinstance(c, str) else None

    def _drop_command(self, ctx):
        """Remove the command turn the aggregator just appended."""
        ctx.set_messages(ctx.messages[:-1])

    async def _speak(self, text):
        await self.push_frame(TTSSpeakFrame(text))

    def _save_active_memory(self, ctx):
        active = self._mgr.active_name()
        if active:
            self._mgr.set_memory(active, ctx.messages)

    def _seed_context(self, ctx, persona):
        msgs = [{"role": "system", "content": persona["prompt"]}]
        msgs += persona.get("memory") or []
        ctx.set_messages(msgs)

    # --- command dispatch -------------------------------------------------
    async def _maybe_handle(self, frame) -> bool:
        ctx = frame.context
        text = self._last_user_text(ctx)
        if text is None:
            return False
        low = text.strip().lower().strip(" .!?,")
        low = re.sub(r"^(hey |ok |okay )?mabu[,:]?\s*", "", low)  # strip wake prefix

        # roster
        if re.search(r"\b(who can you be|list (your )?personas?|what personas?)\b", low):
            self._drop_command(ctx)
            names = self._mgr.display_names()
            await self._speak("I can be " + self._join(names) + ".")
            return True

        # enter the design workshop
        if (re.search(r"\b(new|create|make|design|build)\b.*\b(persona|character)\b", low)
                or re.search(r"\b(persona|character)\b.*\b(workshop|designer)\b", low)):
            await self._enter_workshop(ctx)
            return True

        if self._mode == "workshop":
            if re.search(r"\b(cancel|never ?mind|forget it|stop)\b", low):
                await self._exit_workshop(ctx, "Okay, scrapping that one.")
                return True
            m = re.match(r"save\s+(?:it|this|the (?:persona|character))?\s*(?:as\s+|persona\s+|character\s+)?(?P<name>[a-z0-9 '\-]+)?$", low)
            if low.startswith("save"):
                name = (m.group("name") if m else "").strip() if m else ""
                if not name:
                    self._drop_command(ctx)
                    await self._speak("Sure -- what should I call this character?")
                    return True
                await self._save_workshop(ctx, name)
                return True
            return False  # let the interview continue through to the LLM

        # switch -- only if the spoken name matches an existing persona
        m = re.search(
            r"\b(?:become|be|switch to|turn into|change (?:in)?to|load|go back to|"
            r"transform into|pretend to be)\s+(?P<name>[a-z0-9 '\-]+)$",
            low,
        )
        if m:
            target = self._mgr.find(m.group("name"))
            if target:
                await self._switch(ctx, target)
                return True
        return False

    # --- actions ----------------------------------------------------------
    async def _switch(self, ctx, target_slug):
        self._drop_command(ctx)
        self._save_active_memory(ctx)        # remember outgoing persona's history
        persona = self._mgr.get(target_slug)
        self._mgr.set_active(target_slug)
        self._seed_context(ctx, persona)
        self._apply_voice(persona)
        self._mode = "normal"
        name = persona.get("name", target_slug)
        logger.info(f"[persona] switched to {name}")
        await self._speak(f"Okay, I'm {name} now.")

    async def _enter_workshop(self, ctx):
        self._drop_command(ctx)
        self._save_active_memory(ctx)
        self._prev_persona = self._mgr.active_name()
        self._mode = "workshop"
        ctx.set_messages([{"role": "system", "content": DESIGNER_PROMPT}])
        self._apply_voice(None)  # design in the default voice
        logger.info("[persona] entered design workshop")
        await self._speak("Ooh, a new character! Tell me their name and what they're like.")

    async def _exit_workshop(self, ctx, message):
        self._mode = "normal"
        persona = self._mgr.get(self._prev_persona) if self._prev_persona else self._mgr.active()
        if persona:
            self._seed_context(ctx, persona)
            self._apply_voice(persona)
        await self._speak(message)

    async def _save_workshop(self, ctx, name):
        self._drop_command(ctx)
        convo = [m for m in ctx.messages if m.get("role") in ("user", "assistant")]
        prompt = await self._distill(convo, name)
        self._mgr.create(name, prompt)
        self._mgr.set_active(name)
        new_persona = self._mgr.get(name)
        self._seed_context(ctx, new_persona)
        self._apply_voice(new_persona)
        self._mode = "normal"
        logger.info(f"[persona] saved + switched to new persona '{name}'")
        await self._speak(f"Done! I'm {name} now. Hello!")

    async def _distill(self, convo, name) -> str:
        """Turn the workshop conversation into a clean character system prompt."""
        transcript = "\n".join(f"{m['role']}: {m['content']}" for m in convo) or "(no details)"
        instruction = (
            f"From the conversation below, write a SYSTEM PROMPT in the second person "
            f"('You are {name}...') that defines this character for a small social robot: "
            f"personality, how they speak, and any quirks. Output ONLY the prompt, 2-4 "
            f"short sentences, no preamble.\n\n{transcript}"
        )
        try:
            timeout = aiohttp.ClientTimeout(total=30)
            async with aiohttp.ClientSession(timeout=timeout) as s:
                async with s.post(
                    f"{self._llama_url}/chat/completions",
                    json={
                        "model": "persona",
                        "messages": [{"role": "user", "content": instruction}],
                        "max_tokens": 220,
                        "temperature": 0.7,
                        "stop": self._stop,
                    },
                ) as r:
                    d = await r.json()
                    out = d["choices"][0]["message"]["content"].strip()
                    if out:
                        return out
        except Exception as e:
            logger.warning(f"[persona] distill failed, using fallback: {e}")
        return (
            f"You are {name}, a small yellow social robot. Speak warmly and in "
            f"character, in one or two short sentences."
        )

    @staticmethod
    def _join(names):
        names = [n for n in names if n]
        if not names:
            return "nobody yet"
        if len(names) == 1:
            return names[0]
        return ", ".join(names[:-1]) + " and " + names[-1]
