$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$stateFile = Join-Path $projectRoot '.runtime/share/state.json'
if (!(Test-Path $stateFile)) { Write-Output 'No recorded sharing session.'; exit 0 }
$state = Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json
foreach ($kind in @('tunnel', 'server')) {
    $processId = $state."${kind}Pid"
    $started = [DateTime]::Parse($state."${kind}Started").ToUniversalTime()
    $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
    # Do not stop unrelated processes if Windows has reused a PID.
    if ($null -ne $process -and $process.StartTime.ToUniversalTime() -eq $started) {
        Stop-Process -Id $processId
    }
}
Remove-Item -LiteralPath $stateFile
Write-Output 'Sharing stopped. Saved app data is retained in .runtime/share/wooaeyoung.db.'
