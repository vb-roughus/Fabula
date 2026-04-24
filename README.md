# Fabula

Self-hosted audiobook server inspired by Spotify/Jellyfin.

Fabula is a personal audiobook streaming platform consisting of:

- **Server** — ASP.NET Core (.NET 10) application that scans your audiobook library, exposes a REST API, and serves the web UI.
- **Web UI** — React + TypeScript single-page app, built into the server's `wwwroot` (planned).
- **Android** — Kotlin + Jetpack Compose app using Media3/ExoPlayer (planned).

## Status

- Phase 1 (backend skeleton) — **done**
- Phase 2 (web UI) — **done**
- Phase 3 (Android app) — next
- Phase 4 (metadata fetchers, auth) — later

## Project layout

```
Fabula/
├── server/
│   ├── Fabula.Api/     # ASP.NET Core host + REST endpoints + serves web UI
│   ├── Fabula.Core/    # Domain model, services, library scanner
│   └── Fabula.Data/    # EF Core DbContext, SQLite, migrations
├── web/                # (planned) React + Vite + TypeScript SPA
├── android/            # (planned) Kotlin / Jetpack Compose app
└── docs/
```

## Prerequisites

- .NET 10 SDK
- (later) Node.js 20+ for the web UI
- (later) Android Studio for the Android app

## Running

Two PowerShell helper scripts live at the repo root:

### Normal use — `.\run.ps1`

Builds the SPA into `server/Fabula.Api/wwwroot` and starts the .NET
server. Everything runs on a single origin: `http://localhost:5075`.

Use this whenever you just want to listen to audiobooks.

### Development — `.\dev.ps1`

Opens two PowerShell windows: the .NET backend on `http://localhost:5075`
and the Vite dev server on `http://localhost:5173` with hot reload.
Vite proxies `/api`, `/openapi` and `/health` to the backend, so the
SPA and API still share an origin.

Open `http://localhost:5173` for development. Changes in `web/src/**`
are reflected in the browser instantly without rebuild. Changes in
`server/**` require restarting the backend window.

### Manual / Linux / macOS

If you prefer not to use the PowerShell scripts:

```bash
# Production build + run
cd web && npm install && npm run build
cd ../server && dotnet run --project Fabula.Api

# Or development with hot reload
cd server && dotnet run --project Fabula.Api   # terminal 1
cd web && npm run dev                          # terminal 2
```

On first start the SQLite database is created automatically at
`server/Fabula.Api/data/fabula.db`.

### Useful endpoints

- `GET /health` — health check
- `GET /openapi/v1.json` — OpenAPI spec (Swagger)
- `GET /api/libraries` — list library folders
- `POST /api/libraries` — add a library folder (`{ "name": "Hörbücher", "path": "D:\\Audiobooks" }`)
- `POST /api/libraries/{id}/scan` — scan a library folder
- `GET /api/books?search=&page=1&pageSize=50` — list books
- `GET /api/books/{id}` — book detail (with chapters and file list)
- `GET /api/books/{id}/cover` — cover image
- `GET /api/stream/{audioFileId}` — audio stream (HTTP range supported)
- `GET /api/progress/{bookId}` — current playback progress
- `PUT /api/progress/{bookId}` — update playback progress

## Configuration

`server/Fabula.Api/appsettings.json`:

```json
{
  "Fabula": {
    "DataDirectory": "data",
    "CoversDirectory": null
  }
}
```

`DataDirectory` holds the SQLite database and (by default) the covers folder.
Override either with an absolute path.

## Roadmap

1. **Phase 1** — backend skeleton, library scanner, streaming, progress.
2. **Phase 2** — web UI (library browse, player with chapter nav, progress sync).
3. **Phase 3** — Android app (Media3/ExoPlayer, offline download).
4. **Phase 4** — metadata fetchers (Audible, Google Books), multi-user JWT auth, playlists.
