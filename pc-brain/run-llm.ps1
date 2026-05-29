# Launch llama-server for Mabu's brain, with a NAMED-MODEL REGISTRY so you can
# swap the LLM by name instead of editing paths:
#
#   .\run-llm.ps1 rocinante     # decensored RP/TTRPG brain (default)
#   .\run-llm.ps1 qwen          # the original safe Qwen 2.5 7B
#   .\run-llm.ps1 C:\path\to\some-model.gguf   # any GGUF by path
#
# Models live INSIDE the repo at pc-brain/models/ (gitignored -- multi-GB GGUFs
# aren't tracked, but they no longer sprawl into C:\Users\...\Tools\downloads).
# Pinned to the RTX 4090 (GPU 0); the M6000 is hidden so we don't offload to it.
#
# The pipecat bot / Pipecat OpenAILLMService just point at http://...:8080/v1 and
# don't care which model is loaded (llama-server --jinja uses the GGUF's own chat
# template), so swapping models needs no code change -- restart this script.

[CmdletBinding()]
param(
    [string] $Model = 'rocinante',
    [int]    $Port  = 8080,
    # All transformer layers on the GPU. A 12B Q4_K_M needs ~9 GB incl. KV cache;
    # the 4090 has room once WhisperLive + Chatterbox are loaded.
    [int]    $NGpuLayers = 99,
    [int]    $CtxSize = 8192
)

$root      = $PSScriptRoot
$modelsDir = Join-Path $root 'models'

# llama-server binaries are a separate tool install (not Mabu data). Point this
# elsewhere if yours lives somewhere else.
$LlamaServerExe = 'C:\Users\user\Tools\llama-server\llama-server.exe'

# --- Model registry -------------------------------------------------------
# name -> @{ Path; Template }. Template picks the chat format:
#   ''       => trust the GGUF's embedded template via --jinja (Qwen etc.)
#   'chatml' => force llama.cpp's built-in ChatML (some GGUFs ship a NAME like
#               'mistral-v7-tekken' as their template string, which --jinja
#               can't render -> garbage output; an explicit template fixes it).
# Add a line here whenever you drop a new GGUF into pc-brain/models/.
$registry = @{
    # Decensored RP / adventure brain (TheDrummer, Mistral-Nemo 12B). The pick
    # TTRPG / character-roleplay users reach for; refusals removed. Its card
    # recommends "ChatML for RP", and its embedded template won't render, so
    # we force ChatML explicitly.
    'rocinante' = @{ Path = (Join-Path $modelsDir 'Rocinante-12B-v1.1-Q4_K_M.gguf'); Template = 'chatml' }
    # Original safe assistant model. Embedded Qwen template is good -> --jinja.
    # Still on the old Tools path; move it into pc-brain/models/ to consolidate.
    'qwen'      = @{ Path = 'C:\Users\user\Tools\downloads\qwen2.5-7b-q4_k_m.gguf'; Template = '' }
}

# Resolve: a registry name first, otherwise treat $Model as a literal path.
$template = ''
if ($registry.ContainsKey($Model)) {
    $modelPath = $registry[$Model].Path
    $template  = $registry[$Model].Template
} elseif (Test-Path $Model) {
    $modelPath = $Model
} else {
    Write-Host "Unknown model '$Model'." -ForegroundColor Red
    Write-Host "Known names: $($registry.Keys -join ', ')  (or pass a .gguf path)" -ForegroundColor Yellow
    exit 1
}

if (-not (Test-Path $modelPath)) {
    Write-Host "Model file not found: $modelPath" -ForegroundColor Red
    if ($Model -eq 'rocinante') {
        Write-Host "Download it with: hf download TheDrummer/Rocinante-12B-v1.1-GGUF Rocinante-12B-v1.1-Q4_K_M.gguf --local-dir .\models" -ForegroundColor Yellow
    }
    exit 1
}

$env:CUDA_VISIBLE_DEVICES = '0'  # 4090 only; hide the slower M6000

Write-Host "=== llama-server [$Model] ===" -ForegroundColor Cyan
Write-Host "  file:    $modelPath"
Write-Host "  host:    0.0.0.0:$Port"
Write-Host "  GPU 0 (RTX 4090), -ngl $NGpuLayers, ctx $CtxSize tokens"
Write-Host "  template:$(if ($template) { $template } else { 'embedded (--jinja)' })"
Write-Host "  Mabu/pipecat reach this at http://10.0.0.49:$Port (or localhost)"
Write-Host ""

$llamaArgs = @(
    '--model', $modelPath,
    '--host', '0.0.0.0',
    '--port', $Port,
    '--n-gpu-layers', $NGpuLayers,
    '--ctx-size', $CtxSize,
    '--metrics'
)
if ($template) {
    $llamaArgs += @('--chat-template', $template)   # forced built-in template
} else {
    $llamaArgs += '--jinja'                          # GGUF's embedded template
}

& $LlamaServerExe @llamaArgs
