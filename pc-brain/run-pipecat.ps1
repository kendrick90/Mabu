# Run: Anima's Pipecat brain (PC). ASCII-only.
#
# Serves the SmallWebRTC dev runner: a browser test UI + the WebRTC offer
# endpoint that the Pipecat Android SDK also connects to. Pins to GPU 0.
# Requires llama-server (8080) + Chatterbox (8123) running.

$ErrorActionPreference = "Continue"
$root = $PSScriptRoot
$py   = Join-Path $root "pipecat\.venv\Scripts\python.exe"

# Force Python UTF-8 mode: the pipecat runner library prints emoji we don't
# control ("Bot ready!", arrows). PYTHONUTF8 stops Python from crashing on the
# write; the console-encoding lines below make those UTF-8 bytes RENDER instead
# of showing mojibake (the legacy cp437/cp1252 console shows "Bot ready" as
# garbage otherwise). chcp + Console.OutputEncoding together cover both the
# native code page and PowerShell's own re-encoding through the pipe.
$env:PYTHONUTF8 = "1"
$env:PYTHONIOENCODING = "utf-8"
try {
    chcp 65001 > $null
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    $OutputEncoding = [System.Text.Encoding]::UTF8
} catch { }
$env:CUDA_VISIBLE_DEVICES = "0"
$env:LLAMA_URL      = "http://localhost:8080/v1"
$env:CHATTERBOX_URL = "http://localhost:8123"
# Default LLM model label (llama-server serves whatever GGUF is loaded; the
# name is mostly cosmetic for the OpenAI-compatible request).
$env:LLM_MODEL      = "qwen2.5-7b-instruct"

$port = if ($args.Count -ge 1) { $args[0] } else { 7860 }

Write-Host "=== Anima Pipecat brain on http://0.0.0.0:$port (GPU 0, SmallWebRTC) ===" -ForegroundColor Cyan
Write-Host "Open http://localhost:$port in a browser to test (mic), or point the Android SDK here." -ForegroundColor Cyan
# Coerce stderr (loguru/uvicorn) to plain strings so the window isn't all red.
& $py "$root\pipecat_bot.py" --transport webrtc --host 0.0.0.0 --port $port 2>&1 | ForEach-Object { "$_" }
