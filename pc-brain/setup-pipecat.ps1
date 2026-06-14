# Setup: Pipecat orchestrator on the PC brain. ASCII-only.
#
# Pipecat runs the whole voice pipeline in one process with semantic turn-taking
# (SmartTurn) + automatic barge-in. We reuse our existing servers where it helps:
#   - LLM:  OpenAI-compatible service pointed at llama-server (8080)
#   - TTS:  a custom service that calls our Chatterbox server (8123) over HTTP
#   - STT:  Pipecat's built-in faster-whisper (reuses the cached large-v3-turbo)
#   - VAD/turn: Silero + SmartTurn (local, in this venv)
#   - transport: SmallWebRTC (the official Android Kotlin SDK speaks this)
#
# CUDA torch is needed for Silero/SmartTurn/faster-whisper; installed first so
# they run on the 4090.

# NOT "Stop": pip writes warnings (e.g. unknown-extra) to stderr, which Windows
# PowerShell escalates to a terminating error under Stop -- that killed the
# pipecat install mid-run. Continue + verify at the end instead.
$ErrorActionPreference = "Continue"
$root = $PSScriptRoot
$dir  = Join-Path $root "pipecat"
$venv = Join-Path $dir ".venv"
$py   = Join-Path $venv "Scripts\python.exe"

New-Item -ItemType Directory -Force -Path $dir | Out-Null

Write-Host "=== Creating venv at $venv ===" -ForegroundColor Cyan
if (-not (Test-Path $venv)) { python -m venv $venv }

Write-Host "=== Upgrading pip ===" -ForegroundColor Cyan
& $py -m pip install --upgrade pip

Write-Host "=== Installing CUDA torch (cu124) ===" -ForegroundColor Cyan
& $py -m pip install torch torchaudio --index-url https://download.pytorch.org/whl/cu124

Write-Host "=== Installing pipecat-ai + extras ===" -ForegroundColor Cyan
# silero (VAD), webrtc (SmallWebRTC transport), whisper (faster-whisper STT),
# openai (LLM client -> llama-server), local-smart-turn (SmartTurn v3 semantic
# turn detection). Plus the dev-runner web stack + prebuilt WebRTC UI.
& $py -m pip install "pipecat-ai[silero,webrtc,whisper,openai,local-smart-turn]" `
    aiohttp fastapi uvicorn pipecat-ai-prebuilt

# Strip emoji from pipecat's import banner + runner prints -- they mojibake on
# the Windows cp1252 console (and crashed before PYTHONUTF8). Targets only those
# two files; idempotent.
Write-Host "=== Stripping emoji from pipecat banner/runner ===" -ForegroundColor Cyan
$strip = @'
import os, pipecat
base = os.path.dirname(pipecat.__file__)
for f in [os.path.join(base, "__init__.py"), os.path.join(base, "runner", "run.py")]:
    if not os.path.isfile(f): continue
    s = open(f, encoding="utf-8").read()
    c = "".join(ch for ch in s if ord(ch) < 128)
    if c != s: open(f, "w", encoding="utf-8").write(c); print("de-emoji ->", f)
'@
$strip | & $py -

Write-Host "=== Verifying ===" -ForegroundColor Cyan
$cuda = & $py -c "import torch; print(torch.cuda.is_available())" 2>$null
if ($cuda -notmatch "True") {
    Write-Host "torch lost CUDA -- reinstalling cu124" -ForegroundColor Yellow
    & $py -m pip install --force-reinstall torch torchaudio --index-url https://download.pytorch.org/whl/cu124
}
& $py -c "import torch; print('torch', torch.__version__, 'cuda', torch.cuda.is_available())"
& $py -c "import pipecat; print('pipecat', getattr(pipecat,'__version__','?'))"

Write-Host "=== DONE -- run with .\run-pipecat.ps1 ===" -ForegroundColor Green
