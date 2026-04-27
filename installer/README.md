# Fabula Windows installer

Cuts a single setup `.exe` that

- installs Fabula into `C:\Program Files\Fabula`,
- registers it as a Windows service (`Fabula`, auto-start, restart on
  failure),
- opens TCP 5075 in the Windows Firewall,
- creates a persistent data folder at `C:\ProgramData\Fabula\data` (kept
  across upgrades and uninstall),
- adds Start-menu shortcuts to the web UI, the data folder, and the
  uninstaller.

Re-running the same setup `.exe` performs an in-place upgrade: the
service is stopped, files are replaced, and the service is started
again. The data folder is preserved.

## Prerequisites

- Windows with the **.NET 10 SDK** installed (`dotnet --version` ≥ 10).
  The installer ships the runtime self-contained, so the target machine
  doesn't need the SDK.
- **Node.js 20+** (`npm --version`). The build script runs `npm ci` and
  `npm run build` in `web/` so the Vite SPA gets bundled into
  `server/Fabula.Api/wwwroot` before publish.
- **Inno Setup 6** (https://jrsoftware.org/isinfo.php). The build script
  auto-detects `ISCC.exe` in the standard install locations.

## Build

```powershell
cd installer
.\build-installer.ps1 -Version 0.1.0
```

Output: `artifacts\installer\Fabula-Setup-<version>.exe`.

Pass `-SkipWebBuild` if you already have a fresh `wwwroot` and want to
skip the npm step.

## Silent install / unattended

```powershell
Fabula-Setup-0.1.0.exe /VERYSILENT /SUPPRESSMSGBOXES /NORESTART
```

## Uninstall

Either via *Apps & features* in Windows or by running the registered
uninstaller:

```powershell
& "C:\Program Files\Fabula\unins000.exe" /VERYSILENT
```

The data folder under `C:\ProgramData\Fabula` is intentionally **not**
removed.

## How updates work

Each release builds a new `Fabula-Setup-<version>.exe`. Running the new
exe on a machine that already has Fabula installed:

1. Detects the existing install via the stable `AppId` in `Fabula.iss`.
2. Stops the `Fabula` service.
3. Overwrites the install dir with the new files.
4. Re-registers the service definition (idempotent).
5. Starts the service.

No manual uninstall is required between versions.
