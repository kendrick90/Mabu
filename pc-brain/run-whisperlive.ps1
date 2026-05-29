# Run: WhisperLive WebSocket ASR server on the PC brain (GPU 0 / RTX 4090).
# ASCII-only by design.
#
# The model size (e.g. large-v3-turbo) is chosen by the CLIENT in its
# WebSocket handshake, not here -- the server loads it on first connect and
# caches the CTranslate2 conversion under ~/.cache/whisper-live/.
#
# --raw_pcm_input: accept raw int16 PCM straight from Android AudioRecord
#   (no float32 conversion needed on the device side).
# CUDA_VISIBLE_DEVICES=0: use only the RTX 4090; hide the Quadro M6000
#   (matches the llama-server convention).

# NOT "Stop": the Python server logs to stderr (Python logging's default), and
# under "Stop" PowerShell 5.1 escalates the first stderr line to a terminating
# NativeCommandError, killing the server. "Continue" lets it log and run.
$ErrorActionPreference = "Continue"
$root = $PSScriptRoot
$wl   = Join-Path $root "whisperlive"
$py   = Join-Path $wl ".venv\Scripts\python.exe"

$port = if ($args.Count -ge 1) { $args[0] } else { 9090 }

$env:CUDA_VISIBLE_DEVICES = "0"
# The large-v3-turbo weights are cached locally after first download; inference
# is fully on-GPU via CTranslate2. Force offline so faster-whisper doesn't ping
# HuggingFace for a revision check on each model load (faster reconnects, works
# with no internet). Delete this line if you ever need to pull a new model.
$env:HF_HUB_OFFLINE = "1"

Write-Host "=== WhisperLive on 0.0.0.0:$port (GPU 0, raw PCM int16) ===" -ForegroundColor Cyan
Push-Location $wl
try {
    # Python logging goes to stderr; under Windows PowerShell native stderr
    # renders as red ErrorRecords even with 2>&1. Coerce each line to a plain
    # string (ForEach "$_") so the server window shows normal white log text.
    # --max_connection_time: WhisperLive defaults to 300s and then disconnects
    #   the client. Mabu holds one long-lived always-on connection, so bump it
    #   way up (24h) -- otherwise the session drops every 5 minutes.
    & $py run_server.py --port $port --backend faster_whisper --raw_pcm_input --max_connection_time 86400 2>&1 |
        ForEach-Object { "$_" }
} finally {
    Pop-Location
}
