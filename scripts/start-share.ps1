param([int]$Port = 18756)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$runtimeDir = Join-Path $projectRoot '.runtime/share'
$tunnelExe = Join-Path $projectRoot '.runtime/tools/cloudflared.exe'
$stateFile = Join-Path $runtimeDir 'state.json'
if (!(Test-Path $tunnelExe)) { throw 'Cloudflare cloudflared must be installed in .runtime/tools/cloudflared.exe.' }
if (!(Test-Path (Join-Path $projectRoot 'frontend/dist/index.html'))) { throw 'Run npm --prefix frontend run build first.' }
if (Test-Path $stateFile) { throw 'A sharing session exists. Run scripts/stop-share.ps1 before starting another.' }
New-Item -ItemType Directory -Force -Path $runtimeDir | Out-Null
$pythonExe = (Get-Command python -CommandType Application | Select-Object -First 1).Source
$server = $null
$tunnel = $null
$savedEnv = @{}
$shareEnv = @{
    WOOAEYOUNG_PUBLIC_SERVER = '1'
    WOOAEYOUNG_DB = (Join-Path $runtimeDir 'wooaeyoung.db')
    WOOAEYOUNG_CORS_ORIGINS = ''
    ANTHROPIC_API_KEY = ''
    PYTHONUNBUFFERED = '1'
}
try {
    foreach ($key in $shareEnv.Keys) {
        $savedEnv[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
        [Environment]::SetEnvironmentVariable($key, $shareEnv[$key], 'Process')
    }
    $server = Start-Process -FilePath $pythonExe -ArgumentList @('-m','backend','--host','127.0.0.1','--port',"$Port") -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtimeDir 'server.out.log') -RedirectStandardError (Join-Path $runtimeDir 'server.err.log')
    $ready = $false
    for ($i = 0; $i -lt 30; $i++) {
        Start-Sleep -Seconds 1
        if ($server.HasExited) { throw 'App server stopped. Check .runtime/share/server.err.log.' }
        try {
            $health = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/health" -TimeoutSec 2
            if ($health.status -eq 'ok') { $ready = $true; break }
        } catch { }
    }
    if (!$ready) { throw 'App server did not become ready.' }
    # Only this loopback app port is published. Existing desktop data and paid AI keys are excluded.
    $tunnelLog = Join-Path $runtimeDir 'tunnel.err.log'
    $tunnel = Start-Process -FilePath $tunnelExe -ArgumentList @('tunnel','--no-autoupdate','--url',"http://127.0.0.1:$Port",'--protocol','http2') -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtimeDir 'tunnel.out.log') -RedirectStandardError $tunnelLog
    $publicUrl = $null
    for ($i = 0; $i -lt 50; $i++) {
        Start-Sleep -Seconds 1
        if ($server.HasExited -or $tunnel.HasExited) { throw 'Sharing process stopped. Check .runtime/share logs.' }
        $logText = Get-Content $tunnelLog -Raw -ErrorAction SilentlyContinue
        if ($logText -match 'https://[a-z0-9-]+\.trycloudflare\.com') { $publicUrl = $Matches[0]; break }
    }
    if (!$publicUrl) { throw 'Cloudflare did not return a sharing URL.' }
    @{
        url = $publicUrl
        port = $Port
        serverPid = $server.Id
        serverStarted = $server.StartTime.ToUniversalTime().ToString('o')
        tunnelPid = $tunnel.Id
        tunnelStarted = $tunnel.StartTime.ToUniversalTime().ToString('o')
        createdAt = [DateTime]::UtcNow.ToString('o')
    } | ConvertTo-Json | Set-Content -LiteralPath $stateFile -Encoding UTF8
    Write-Output "Sharing URL: $publicUrl"
    Write-Output 'Temporary: keep this PC awake. Stop with scripts/stop-share.ps1.'
} catch {
    if ($null -ne $tunnel -and !$tunnel.HasExited) { Stop-Process -Id $tunnel.Id -ErrorAction SilentlyContinue }
    if ($null -ne $server -and !$server.HasExited) { Stop-Process -Id $server.Id -ErrorAction SilentlyContinue }
    throw
} finally {
    foreach ($key in $savedEnv.Keys) { [Environment]::SetEnvironmentVariable($key, $savedEnv[$key], 'Process') }
}
