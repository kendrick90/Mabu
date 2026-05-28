# Clone llama.cpp into mabu-android/app/src/main/cpp/llama.cpp/. The full
# llama.cpp source is too big to vendor in this repo (~185 MB even shallow
# cloned), so it's gitignored. Run this once after a fresh clone.
#
# Pin to a known-good commit if upstream churn breaks the build -- pass
# a commit / tag via -Ref.

[CmdletBinding()]
param(
    [string] $Ref = ""
)

$ErrorActionPreference = 'Stop'
$dest = 'mabu-android/app/src/main/cpp/llama.cpp'

if (Test-Path $dest) {
    Write-Host "llama.cpp already exists at $dest. Skipping clone."
} else {
    Write-Host "Cloning llama.cpp into $dest..."
    git clone --depth 1 https://github.com/ggerganov/llama.cpp.git $dest
}

if ($Ref) {
    Write-Host "Checking out $Ref"
    Push-Location $dest
    git fetch --depth 1 origin $Ref
    git checkout $Ref
    Pop-Location
}

Push-Location $dest
$commit = git log -1 --format='%h %ai %s'
Pop-Location
Write-Host "llama.cpp at: $commit"

Write-Host ""
Write-Host "Next: download a model (Qwen2.5-0.5B-Instruct Q4_K_M recommended) and adb push"
Write-Host "  it to the device at /data/local/tmp/qwen.gguf"
Write-Host "  (or the Mabu app's external files dir)."
