# One-command launcher for Mabu's whole brain stack, in dependency order:
#   llama-server (8080) -> WhisperLive (9090) -> Chatterbox (8123) -> Pipecat bot (7860)
#
#   .\run-all.ps1                 # default LLM (rocinante)
#   .\run-all.ps1 -Model qwen     # pick the LLM by registry name (see run-llm.ps1)
#
# Each service launches in its own window (so you can watch its logs) and is
# health-gated on its port before the next starts -- the pipecat bot only comes
# up once STT/LLM/TTS are ready. Already-running services are detected and
# skipped, so re-running this is safe. Stop everything with .\stop-all.ps1.

[CmdletBinding()]
param(
    [string] $Model = 'rocinante'
)

$root = $PSScriptRoot

function Test-Up([int]$port) {
    (Test-NetConnection 127.0.0.1 -Port $port -WarningAction SilentlyContinue).TcpTestSucceeded
}

function Wait-Up([int]$port, [string]$name, [int]$timeoutSec = 180) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    do {
        Start-Sleep -Seconds 3
        $ok = Test-Up $port
    } until ($ok -or (Get-Date) -gt $deadline)
    if ($ok) { Write-Host "  [up]      $name  ($port)" -ForegroundColor Green }
    else     { Write-Host "  [TIMEOUT] $name  ($port)" -ForegroundColor Red }
    return $ok
}

function Start-Svc([string]$script, [object[]]$svcArgs, [int]$port, [string]$name) {
    if (Test-Up $port) {
        Write-Host "  [skip]    $name already on $port" -ForegroundColor DarkGray
        return
    }
    Write-Host "  starting  $name ..." -ForegroundColor Cyan
    $psArgs = @('-NoExit', '-NoProfile', '-File', (Join-Path $root $script)) + $svcArgs
    Start-Process powershell -ArgumentList $psArgs -WorkingDirectory $root | Out-Null
    [void](Wait-Up $port $name)
}

Write-Host "=== Launching Mabu brain stack (LLM=$Model) ===" -ForegroundColor Cyan
# Propagate the chosen model to the pipecat bot (inherited by its child window)
# so its per-model stop tokens match what llama-server actually loaded.
$env:LLM_MODEL = $Model
Start-Svc 'run-llm.ps1'         @($Model) 8080 "llama-server [$Model]"
Start-Svc 'run-whisperlive.ps1' @()       9090 'WhisperLive STT'
Start-Svc 'run-chatterbox.ps1'  @()       8123 'Chatterbox TTS'
Start-Svc 'run-pipecat.ps1'     @()       7860 'Pipecat bot'

Write-Host ""
Write-Host "Stack status:" -ForegroundColor Cyan
foreach ($svc in @(@(8080,'llama-server'), @(9090,'WhisperLive'), @(8123,'Chatterbox'), @(7860,'Pipecat bot'))) {
    $state = if (Test-Up $svc[0]) { 'UP  ' } else { 'DOWN' }
    Write-Host ("  {0}  {1} ({2})" -f $state, $svc[1], $svc[0])
}
Write-Host ""
Write-Host "Device connects to the Pipecat bot at http://10.0.0.49:7860 ; browser test at http://localhost:7860"
Write-Host "Stop everything: .\stop-all.ps1"
