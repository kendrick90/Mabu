"""Persona store for Mabu: multiple named personas, each with its own system
prompt and its own conversation memory, persisted to disk so you can switch
back and forth and each character "remembers" its own history.

Layout (all inside the repo, gitignored):
  pc-brain/personas/<slug>.json   -> {name, prompt, voice, memory:[...], created, updated}
  pc-brain/personas/_state.json   -> {"active": "<slug>"}

Pure storage + active-persona tracking. The Pipecat-side PersonaControl handles
voice commands and mutates the live LLM context; this module just owns the files.
"""
import json
import re
import time
from pathlib import Path

# Keep only the last N conversation messages per persona (system prompt excluded).
MEMORY_CAP = 40


def _now() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%S")


class PersonaManager:
    def __init__(self, personas_dir, default_prompt: str, default_name: str = "Mabu"):
        self.dir = Path(personas_dir)
        self.dir.mkdir(parents=True, exist_ok=True)
        self._state_path = self.dir / "_state.json"
        # Seed a starter persona from the built-in prompt on first run.
        if not self.list():
            self.create(default_name, default_prompt)
            self.set_active(default_name)

    # --- naming -----------------------------------------------------------
    @staticmethod
    def slug(name: str) -> str:
        s = re.sub(r"[^a-z0-9_-]+", "_", (name or "").strip().lower()).strip("_")
        return s or "persona"

    def _path(self, name: str) -> Path:
        return self.dir / f"{self.slug(name)}.json"

    # --- queries ----------------------------------------------------------
    def list(self) -> list:
        return sorted(p.stem for p in self.dir.glob("*.json") if not p.stem.startswith("_"))

    def display_names(self) -> list:
        out = []
        for s in self.list():
            d = self.get(s)
            out.append(d.get("name", s) if d else s)
        return out

    def exists(self, name: str) -> bool:
        return self._path(name).exists()

    def get(self, name: str):
        p = self._path(name)
        if not p.exists():
            return None
        try:
            return json.loads(p.read_text(encoding="utf-8"))
        except Exception:
            return None

    def find(self, spoken: str):
        """Resolve a spoken name ('pirate pete') to a stored slug, or None.

        Matches by slug or by case-insensitive display name. Used so a switch
        only fires for personas that actually exist (avoids false triggers on
        normal roleplay like 'become a wizard')."""
        if not spoken or not spoken.strip():
            return None
        target_slug = self.slug(spoken)
        spoken_low = spoken.strip().lower()
        for s in self.list():
            if s == target_slug:
                return s
            d = self.get(s)
            if d and d.get("name", "").strip().lower() == spoken_low:
                return s
        return None

    # --- mutations --------------------------------------------------------
    def create(self, name: str, prompt: str, voice=None) -> dict:
        data = {
            "name": name.strip(),
            "prompt": prompt.strip(),
            "voice": voice,
            "memory": [],
            "created": _now(),
            "updated": _now(),
        }
        self.save(data)
        return data

    def save(self, data: dict):
        data["updated"] = _now()
        self._path(data["name"]).write_text(
            json.dumps(data, indent=2, ensure_ascii=False), encoding="utf-8"
        )

    def delete(self, name: str):
        p = self._path(name)
        if p.exists():
            p.unlink()

    def set_memory(self, name: str, messages: list):
        d = self.get(name)
        if not d:
            return
        # Persist only user/assistant turns, capped to the most recent.
        convo = [m for m in messages if m.get("role") in ("user", "assistant")]
        d["memory"] = convo[-MEMORY_CAP:]
        self.save(d)

    # --- active persona ---------------------------------------------------
    def set_active(self, name: str):
        self._state_path.write_text(
            json.dumps({"active": self.slug(name)}), encoding="utf-8"
        )

    def active_name(self):
        if self._state_path.exists():
            try:
                a = json.loads(self._state_path.read_text(encoding="utf-8")).get("active")
                if a and self.exists(a):
                    return a
            except Exception:
                pass
        names = self.list()
        return names[0] if names else None

    def active(self):
        n = self.active_name()
        return self.get(n) if n else None
