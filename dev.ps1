<#
  Fabula dev launcher

  Starts the .NET backend (ASP.NET Core, port 5075) and the Vite dev server
  (port 5173, with hot reload) each in their own PowerShell window.

  Open http://localhost:5173 in the browser. Vite proxies /api, /openapi and
  /health to the backend on 5075, so the SPA and the API share an origin.

  Close either window (Ctrl+C or X) to stop that process; the other keeps
  running.
#>

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path

$serverDir = Join-Path $root 'server'
$webDir    = Join-Path $root 'web'

if (-not (Test-Path $serverDir)) { throw "server/ not found at $serverDir" }
if (-not (Test-Path $webDir))    { throw "web/ not found at $webDir" }

if (-not (Test-Path (Join-Path $webDir 'node_modules'))) {
    Write-Host "node_modules missing - running 'npm install' first..." -ForegroundColor Yellow
    Push-Location $webDir
    try { npm install } finally { Pop-Location }
}

Write-Host "Starting backend on http://localhost:5075 ..." -ForegroundColor Cyan
Start-Process -FilePath 'powershell.exe' -ArgumentList @(
    '-NoExit', '-Command',
    "`$Host.UI.RawUI.WindowTitle = 'Fabula backend'; Set-Location '$serverDir'; dotnet run --project Fabula.Api"
)

Write-Host "Starting Vite dev server on http://localhost:5173 ..." -ForegroundColor Cyan
Start-Process -FilePath 'powershell.exe' -ArgumentList @(
    '-NoExit', '-Command',
    "`$Host.UI.RawUI.WindowTitle = 'Fabula web'; Set-Location '$webDir'; npm run dev"
)

Write-Host ""
Write-Host "Both started." -ForegroundColor Green
Write-Host "  Web UI (with hot reload): http://localhost:5173" -ForegroundColor Green
Write-Host "  Backend API:              http://localhost:5075" -ForegroundColor Green
Write-Host ""
Write-Host "Close either window to stop that process."
