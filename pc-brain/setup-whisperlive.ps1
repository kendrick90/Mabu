# Setup: WhisperLive (faster-whisper / CTranslate2 backend, CUDA) on the PC brain.
# ASCII-only by design (Windows PowerShell 5.1 chokes on non-ASCII).
#
# Idempotent: clones WhisperLive into ./whisperlive if missing, creates a venv
# there, and installs a MINIMAL dependency set for the faster_whisper backend
# only -- skipping the openai-whisper / transformers / openvino / tensorrt
# extras in requirements/server.txt (heavy, no clean Python 3.13 wheels, not
# needed for the CTranslate2 GPU path).
#
# torch is installed WITH CUDA (cu124) on purpose:
#   1. WhisperLive's server.py imports torch unconditionally and uses
#      torch.cuda.is_available() / get_device_capability() to pick the device.
#   2. torch's bundled cuBLAS/cuDNN DLLs satisfy CTranslate2's GPU runtime,
#      which sidesteps the standalone nvidia-*-cu12 DLL-loading dance on Windows.
# The actual transcription runs on CTranslate2 (an optimized precompiled GPU
# runner), NOT torch -- torch is just device-detection glue here.

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$wl   = Join-Path $root "whisperlive"
$venv = Join-Path $wl ".venv"
$py   = Join-Path $venv "Scripts\python.exe"

if (-not (Test-Path $wl)) {
    Write-Host "=== Cloning WhisperLive into $wl ===" -ForegroundColor Cyan
    git clone --depth 1 https://github.com/collabora/WhisperLive.git $wl
}

Write-Host "=== Creating venv at $venv ===" -ForegroundColor Cyan
if (-not (Test-Path $venv)) { python -m venv $venv }

Write-Host "=== Upgrading pip ===" -ForegroundColor Cyan
& $py -m pip install --upgrade pip

Write-Host "=== Installing CUDA torch (cu124) ===" -ForegroundColor Cyan
& $py -m pip install torch --index-url https://download.pytorch.org/whl/cu124

Write-Host "=== Installing WhisperLive faster_whisper deps ===" -ForegroundColor Cyan
# numpy>=2 (NOT the <2 pin from requirements/server.txt): on Python 3.13 the
#   old numpy 1.26.x has no real cp313 wheel and pip pulls a broken MINGW build
#   that crashes on import. numpy 2.x has proper 3.13 wheels and works with
#   faster-whisper / ctranslate2 / onnxruntime. The <2 pin was for the
#   openai-whisper extras we don't install.
# requests: faster-whisper imports it directly, but huggingface-hub 1.x no
#   longer pulls it transitively.
& $py -m pip install `
    "faster-whisper==1.2.0" `
    "websockets" `
    "numpy>=2" `
    "requests" `
    "scipy" `
    "soundfile" `
    "av" `
    "fastapi" `
    "uvicorn" `
    "python-multipart" `
    "numba" `
    "kaldialign"

# Verification calls below: numpy/torch emit benign warnings to stderr, which
# PowerShell 5.1 would escalate to a terminating error under "Stop". Relax to
# "Continue" so a stderr warning doesn't abort the (already-installed) setup.
$ErrorActionPreference = "Continue"

Write-Host "=== Verifying torch + CUDA ===" -ForegroundColor Cyan
& $py -c "import torch; print('torch', torch.__version__, 'cuda', torch.cuda.is_available(), torch.cuda.get_device_name(0) if torch.cuda.is_available() else 'NO-GPU')"

Write-Host "=== Verifying faster_whisper + ctranslate2 ===" -ForegroundColor Cyan
& $py -c "import faster_whisper, ctranslate2; print('faster_whisper', faster_whisper.__version__, 'ctranslate2', ctranslate2.__version__, 'cuda_devices', ctranslate2.get_cuda_device_count())"

Write-Host "=== Verifying WhisperLive server imports ===" -ForegroundColor Cyan
Push-Location $wl
& $py -c "from whisper_live.server import TranscriptionServer; print('WhisperLive server import OK')"
Pop-Location

Write-Host "=== DONE -- run with .\run-whisperlive.ps1 ===" -ForegroundColor Green
