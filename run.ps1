<#
  Fabula production launcher

  Builds the web UI into server/Fabula.Api/wwwroot, then starts the .NET
  backend. The server serves both the API and the SPA on port 5075.

  Use this when you just want to use Fabula. For development with hot
  reload, run .\dev.ps1 instead.
#>

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path

$webDir    = Join-Path $root 'web'
$serverDir = Join-Path $root 'server'

if (-not (Test-Path (Join-Path $webDir 'node_modules'))) {
    Write-Host "Installing web dependencies..." -ForegroundColor Yellow
    Push-Location $webDir
    try { npm install } finally { Pop-Location }
}

Write-Host "Building web UI..." -ForegroundColor Cyan
Push-Location $webDir
try { npm run build } finally { Pop-Location }

Write-Host ""
Write-Host "Starting server on http://0.0.0.0:5075 (reachable from other devices on your LAN) ..." -ForegroundColor Cyan
Push-Location $serverDir
try {
    $env:ASPNETCORE_URLS = 'http://0.0.0.0:5075'
    dotnet run --project Fabula.Api --no-launch-profile
} finally { Pop-Location }
