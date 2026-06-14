# Run: Chatterbox TTS server on the PC brain (GPU 0 / RTX 4090). ASCII-only.
#
# Serves POST /tts -> raw int16 PCM for RemoteTts.kt on the device. Pins to the
# 4090 and hides the Quadro M6000 (matches the llama-server / WhisperLive
# convention). Drop a voice.wav next to chatterbox_server.py to clone a voice.

$ErrorActionPreference = "Continue"
$root = $PSScriptRoot
$py   = Join-Path $root "chatterbox\.venv\Scripts\python.exe"

$port = if ($args.Count -ge 1) { $args[0] } else { 8123 }

$env:CUDA_VISIBLE_DEVICES = "0"
$env:CHATTERBOX_PORT = "$port"
# Chatterbox's sampler prints a tqdm progress bar ("Sampling: ...") to stderr
# per utterance; PowerShell flags those native-stderr lines as red
# "RemoteException" noise. Disable tqdm -- purely cosmetic, no effect on output.
$env:TQDM_DISABLE = "1"

Write-Host "=== Chatterbox TTS on 0.0.0.0:$port (GPU 0) ===" -ForegroundColor Cyan
# Coerce stderr (uvicorn/torch logging) to plain strings so the window isn't red.
& $py "$root\chatterbox_server.py" 2>&1 | ForEach-Object { "$_" }
