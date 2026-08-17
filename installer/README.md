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

## App-Update-Konfiguration (In-App-Updates)

Der Installer zeigt eine optionale Seite **"App-Updates"**, auf der du das
GitHub-Repository der App-Releases (Vorgabe `vb-roughus/Fabula`) und – bei
einem privaten Repo – ein Zugriffstoken eintragen kannst. Die Werte werden
nach `C:\ProgramData\Fabula\fabula.settings.json` geschrieben:

```json
{
  "Fabula": {
    "UpdateRepo": "vb-roughus/Fabula",
    "UpdateGithubToken": "github_pat_…"
  }
}
```

Diese Datei liegt bewusst im `ProgramData`-Ordner (nicht unter Program
Files) und **überlebt Upgrades** – `appsettings.*` würden bei jedem
Reinstall überschrieben. Der Server lädt sie zuletzt, sie hat also Vorrang
vor den mitgelieferten Vorgaben. Bei einem Reinstall werden die Felder aus
der vorhandenen Datei vorbefüllt; ein stiller Install (`/VERYSILENT`) lässt
eine bestehende Datei unangetastet.

Token nachträglich ändern: am besten in der Web-UI oder in der App unter
*Einstellungen → App-Update* – das wird sofort übernommen und in dieselbe
Datei zurückgeschrieben. Die Datei direkt zu bearbeiten geht auch, wirkt
aber **erst nach einem Neustart des Dienstes**: die Felder werden beim Start
einmal gelesen, `reloadOnChange` erreicht sie nicht. Alternativ funktioniert
auf beliebigen Plattformen die Umgebungsvariable `FABULA_SETTINGS_FILE`, die
auf eine eigene Settings-Datei zeigt.

Seit dem Server-Selbstupdate ist diese Datei **nur noch für Administratoren
und SYSTEM lesbar und schreibbar**, ebenso `secrets.json` daneben, und der
oberste Ordner `C:\ProgramData\Fabula` gibt normalen Benutzern nur noch
Leserechte. Grund: `UpdateRepo` entscheidet, woher eine Setup-Datei geladen
wird, die der Server anschließend als SYSTEM ausführt – wer die Datei
ändern kann, könnte damit beliebigen Code als SYSTEM starten. Die Ordner
`data\`, `data\covers\` und `logs\` bleiben unverändert beschreibbar. Zum
Bearbeiten der Datei brauchst du also einen Editor „als Administrator".

## Prerequisites

- Windows with the **.NET 10 SDK** installed (`dotnet --version` ≥ 10).
  The installer ships the runtime self-contained, so the target machine
  doesn't need the SDK.
- **Node.js 20+** (`npm --version`). The build script runs `npm ci` and
  `npm run build` in `web/` so the Vite SPA gets bundled into
  `server/Fabula.Api/wwwroot` before publish.
- **Inno Setup 6** (https://jrsoftware.org/isinfo.php). The build script
  auto-detects `ISCC.exe` in the standard install locations.

## Build (automatisch)

Normalerweise musst du gar nichts von Hand bauen: der Workflow
**Windows Installer** (`.github/workflows/windows-installer.yml`) baut den
Installer bei jedem Push auf `main`, der `server/**`, `web/**` oder
`installer/**` berührt, und veröffentlicht ihn als GitHub-Release mit dem
Tag `win-v0.3.<run_number>`. Über *Actions → Windows Installer → Run
workflow* lässt er sich jederzeit auch manuell starten, wahlweise mit einer
selbst gewählten Version.

Die Installer-Releases sind bewusst **nicht** als „Latest" markiert. Der
Fabula-Server spiegelt die Android-App über
`https://api.github.com/repos/<repo>/releases/latest` und erwartet dort ein
`version.json` und eine `.apk`. Würde ein Installer-Release den
„Latest"-Zeiger übernehmen, lieferte der Server stillschweigend weiter die
alte APK aus. Deshalb: getrenntes Tag-Präfix (`win-v*` statt `apk-v*`),
`make_latest: false`, und keine Asset-Namen, die mit dem APK-Release
kollidieren.

## Build (lokal)

Für Zwischenstände, die kein Release werden sollen:

```powershell
cd installer
.\build-installer.ps1 -Version 0.1.0
```

Output: `artifacts\installer\Fabula-Setup-<version>.exe`.

Pass `-SkipWebBuild` if you already have a fresh `wwwroot` and want to
skip the npm step.

Die Version muss dreiteilig sein (`x.y.z`) — das Skript leitet daraus
`AssemblyVersion=<version>.0` ab.

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

### Aus der Web-UI oder der App auslösen

Genau dieser Ablauf lässt sich auch fernauslösen: *Einstellungen →
Server-Update* (in der Web-UI ein eigener Abschnitt, in der App ein
Admin-Eintrag). Der Server sucht dann das neueste `win-v*`-Release, lädt die
Setup-Datei, **prüft sie gegen die veröffentlichte `.sha256`** und übergibt
an ein kleines Wrapper-Skript, das den Installer still ausführt.

Zwei Dinge, die man dazu wissen sollte:

- **Der Dienst startet dabei neu**, laufende Wiedergabe bricht für etwa eine
  halbe Minute ab. Deshalb passiert das nur auf ausdrücklichen Knopfdruck,
  nie automatisch.
- **Der Wrapper startet den Dienst notfalls selbst.** Scheitert der Installer
  nachdem er den Dienst gestoppt hat, wäre der Server sonst weg – und mit ihm
  die Oberfläche, über die man es reparieren würde. In diesem Fall läuft
  weiterhin die alte Version und der Status meldet den Rückgabecode.

Vor der Übergabe wird `data\fabula.db` nach `data\backups\` kopiert. Die
EF-Migrationen laufen beim Start und nur vorwärts; diese Kopie ist der
einzige Weg zurück auf eine ältere Version.

Zum Nachsehen, falls etwas schiefging:

- `C:\ProgramData\Fabula\server-updates\server-update.json` – Zustand,
  Ausgangs- und Zielversion, Fehlertext
- `C:\ProgramData\Fabula\server-updates\server-update-result.json` –
  Rückgabecode des Installers
- `C:\ProgramData\Fabula\logs\server-update-<zeitstempel>.log` – das
  Protokoll von Inno Setup selbst

Läuft der Server nicht als Dienst (etwa `dotnet run` in der Entwicklung) oder
nicht unter Windows, meldet der Endpunkt `supported: false` und die
Oberflächen bieten die Aktion gar nicht an – dort bleibt es beim manuellen
Ausführen der Setup-Datei.
