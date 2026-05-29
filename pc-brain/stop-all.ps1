# Stop Mabu's whole brain stack (counterpart to run-all.ps1).
# Kills by process identity (command line / exe), so it works regardless of how
# the services were launched -- separate windows, run-all, or by hand.

$pyScripts = @('pipecat_bot.py', 'chatterbox_server.py', 'run_server.py')  # WhisperLive = run_server.py

Get-CimInstance Win32_Process -Filter "name='python.exe'" | ForEach-Object {
    $cl = $_.CommandLine
    if ($cl -and ($pyScripts | Where-Object { $cl -like "*$_*" })) {
        Write-Host "stopping python: $($_.ProcessId)"
        try { Stop-Process -Id $_.ProcessId -Force } catch {}
    }
}

Get-Process llama-server -ErrorAction SilentlyContinue | ForEach-Object {
    Write-Host "stopping llama-server: $($_.Id)"
    try { Stop-Process -Id $_.Id -Force } catch {}
}

Write-Host "Done. (Service windows may remain open; close them manually.)"
