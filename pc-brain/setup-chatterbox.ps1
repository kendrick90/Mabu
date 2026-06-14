# Setup: Chatterbox TTS (Resemble AI) on the PC brain. ASCII-only.
#
# Chatterbox is pip-installable on native Windows (no conda / no pynini, unlike
# CosyVoice). It's a PyTorch model: voice cloning from a short sample + emotion
# exaggeration control. We install CUDA torch first so it runs on the 4090, then
# chatterbox-tts. Inference is fully local.

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$dir  = Join-Path $root "chatterbox"
$venv = Join-Path $dir ".venv"
$py   = Join-Path $venv "Scripts\python.exe"

New-Item -ItemType Directory -Force -Path $dir | Out-Null

Write-Host "=== Creating venv at $venv ===" -ForegroundColor Cyan
if (-not (Test-Path $venv)) { python -m venv $venv }

Write-Host "=== Upgrading pip ===" -ForegroundColor Cyan
& $py -m pip install --upgrade pip

Write-Host "=== Installing CUDA torch (cu124) ===" -ForegroundColor Cyan
& $py -m pip install torch torchaudio --index-url https://download.pytorch.org/whl/cu124

Write-Host "=== Installing chatterbox-tts + server deps ===" -ForegroundColor Cyan
& $py -m pip install chatterbox-tts fastapi uvicorn

# torch may have been downgraded to a CPU build by chatterbox's deps; if CUDA
# isn't visible, force the CUDA build back in.
$ErrorActionPreference = "Continue"
Write-Host "=== Verifying torch CUDA ===" -ForegroundColor Cyan
$cuda = & $py -c "import torch; print(torch.cuda.is_available())" 2>$null
if ($cuda -notmatch "True") {
    Write-Host "torch lost CUDA -- reinstalling cu124 build" -ForegroundColor Yellow
    & $py -m pip install --force-reinstall torch torchaudio --index-url https://download.pytorch.org/whl/cu124
}

Write-Host "=== Final verify ===" -ForegroundColor Cyan
& $py -c "import torch; print('torch', torch.__version__, 'cuda', torch.cuda.is_available(), torch.cuda.get_device_name(0) if torch.cuda.is_available() else 'NO-GPU')"
& $py -c "import chatterbox; print('chatterbox import OK')"

Write-Host "=== DONE -- run with .\run-chatterbox.ps1 ===" -ForegroundColor Green
