"""Tiny HTTP control API for Mabu's brain, embedded in the pipecat bot process.

Lets you SEE and DRIVE personas/voices programmatically (no voice needed) --
the "what's going on" view plus scripted switching. Runs on its own port in a
daemon thread; reads/writes go through the live PersonaControl (commands are
queued and applied on the pipeline loop, so no cross-thread races).

  GET  /status                 -> {active, voice, mode, personas, voices}
  GET  /personas               -> {personas: [...]}
  GET  /voices                 -> {voices: [...]}
  POST /switch   {"name": ...}             -> switch persona live
  POST /voice    {"name": ...}             -> set current persona's voice live
  POST /create   {"name", "prompt", "activate"?}  -> create persona (+ optional switch)

Examples:
  curl localhost:7861/status
  curl -XPOST localhost:7861/switch -d '{"name":"pirate pete"}'
"""
import json
import os
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from loguru import logger

# Set by the pipecat bot each time a pipeline starts, so the API always points
# at the live PersonaControl / PersonaManager.
CTRL = {"persona_ctl": None, "manager": None, "voices_dir": None}

_started = False
_lock = threading.Lock()


def _voices():
    d = CTRL.get("voices_dir")
    names = []
    if d and os.path.isdir(d):
        names = [os.path.splitext(f)[0] for f in os.listdir(d) if f.lower().endswith(".wav")]
    return ["default"] + sorted(set(names))


class _Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):  # silence per-request stderr spam
        pass

    def _send(self, code, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _pc(self):
        return CTRL.get("persona_ctl"), CTRL.get("manager")

    def do_GET(self):
        pc, mgr = self._pc()
        if mgr is None:
            return self._send(503, {"error": "no active session"})
        if self.path.rstrip("/") == "/status":
            st = pc.status() if pc else {}
            st["voices"] = _voices()
            return self._send(200, st)
        if self.path.rstrip("/") == "/personas":
            return self._send(200, {"personas": mgr.display_names()})
        if self.path.rstrip("/") == "/voices":
            return self._send(200, {"voices": _voices()})
        return self._send(404, {"error": "not found"})

    def do_POST(self):
        pc, mgr = self._pc()
        if pc is None:
            return self._send(503, {"error": "no active session"})
        try:
            n = int(self.headers.get("Content-Length", 0))
            data = json.loads(self.rfile.read(n) or b"{}")
        except Exception as e:
            return self._send(400, {"error": f"bad JSON: {e}"})

        path = self.path.rstrip("/")
        if path == "/switch":
            if not data.get("name"):
                return self._send(400, {"error": "name required"})
            pc.submit_command({"action": "switch", "name": data["name"]})
            return self._send(200, {"ok": True, "queued": "switch", "name": data["name"]})
        if path == "/voice":
            if not data.get("name"):
                return self._send(400, {"error": "name required"})
            pc.submit_command({"action": "voice", "name": data["name"]})
            return self._send(200, {"ok": True, "queued": "voice", "name": data["name"]})
        if path == "/create":
            if not (data.get("name") and data.get("prompt")):
                return self._send(400, {"error": "name and prompt required"})
            pc.submit_command({
                "action": "create",
                "name": data["name"],
                "prompt": data["prompt"],
                "activate": bool(data.get("activate")),
            })
            return self._send(200, {"ok": True, "queued": "create", "name": data["name"]})
        return self._send(404, {"error": "not found"})


def start_control_server(host="0.0.0.0", port=7861):
    """Start the control server once (idempotent across pipeline restarts)."""
    global _started
    with _lock:
        if _started:
            return
        server = ThreadingHTTPServer((host, port), _Handler)
        t = threading.Thread(target=server.serve_forever, name="control-api", daemon=True)
        t.start()
        _started = True
        logger.info(f"[control] API on http://{host}:{port} (status/switch/voice/create)")
