<#
.SYNOPSIS
    Cuts a Fabula Windows installer (single .exe).

.DESCRIPTION
    Publishes the ASP.NET Core server self-contained for win-x64 and runs
    Inno Setup against installer\Fabula.iss to produce
    artifacts\installer\Fabula-Setup-<version>.exe.

.PARAMETER Version
    Product version (default 0.1.0). Stamped into the EXE and installer
    filename.

.PARAMETER Configuration
    Build configuration (default Release).

.PARAMETER InnoSetupCompiler
    Path to ISCC.exe. If omitted, the script searches the standard
    install locations.

.EXAMPLE
    .\build-installer.ps1 -Version 0.2.0
#>
[CmdletBinding()]
param(
    [string]$Version = "0.1.0",
    [string]$Configuration = "Release",
    [string]$InnoSetupCompiler
)

$ErrorActionPreference = "Stop"
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$serverProj = Join-Path $repoRoot "server\Fabula.Api\Fabula.Api.csproj"
$publishDir = Join-Path $repoRoot "artifacts\publish\win-x64"
$installerOut = Join-Path $repoRoot "artifacts\installer"
$issScript = Join-Path $PSScriptRoot "Fabula.iss"

if (-not (Test-Path $serverProj)) {
    throw "Server project not found at $serverProj."
}

if (-not $InnoSetupCompiler) {
    $candidates = @(
        "C:\Program Files (x86)\Inno Setup 6\ISCC.exe",
        "C:\Program Files\Inno Setup 6\ISCC.exe"
    )
    $InnoSetupCompiler = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
}
if (-not $InnoSetupCompiler -or -not (Test-Path $InnoSetupCompiler)) {
    throw "ISCC.exe not found. Install Inno Setup 6 from https://jrsoftware.org/isinfo.php or pass -InnoSetupCompiler."
}

Write-Host "==> Cleaning previous publish output" -ForegroundColor Cyan
if (Test-Path $publishDir) { Remove-Item -Recurse -Force $publishDir }
New-Item -ItemType Directory -Force -Path $installerOut | Out-Null

Write-Host "==> Publishing Fabula.Api ($Configuration, win-x64, self-contained)" -ForegroundColor Cyan
$publishArgs = @(
    "publish", $serverProj,
    "-c", $Configuration,
    "-r", "win-x64",
    "--self-contained", "true",
    "/p:PublishSingleFile=false",
    "/p:DebugType=None",
    "/p:DebugSymbols=false",
    "/p:Version=$Version",
    "/p:AssemblyVersion=$Version.0",
    "/p:FileVersion=$Version.0",
    "-o", $publishDir
)
& dotnet @publishArgs
if ($LASTEXITCODE -ne 0) { throw "dotnet publish failed (exit $LASTEXITCODE)." }

Write-Host "==> Compiling installer" -ForegroundColor Cyan
& $InnoSetupCompiler `
    "/DAppVersion=$Version" `
    "/DPublishDir=$publishDir" `
    "/DOutputDir=$installerOut" `
    $issScript
if ($LASTEXITCODE -ne 0) { throw "Inno Setup compile failed (exit $LASTEXITCODE)." }

$exe = Join-Path $installerOut ("Fabula-Setup-$Version.exe")
Write-Host ""
Write-Host "Installer ready: $exe" -ForegroundColor Green
