import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { api } from '../api/client';
import type {
  AppUpdateCheck,
  LibraryFolder,
  LibraryType,
  ScanStatus,
  ServerUpdateCheck,
  ServerUpdateState
} from '../api/types';
import { LIBRARY_TYPE_LABEL } from '../api/types';
import { useAuth } from '../auth/AuthContext';

export function SettingsPage() {
  const qc = useQueryClient();
  const auth = useAuth();
  const [name, setName] = useState('');
  const [path, setPath] = useState('');
  const [type, setType] = useState<LibraryType>('Audiobook');

  const { data: libraries, isLoading } = useQuery({
    queryKey: ['libraries'],
    queryFn: api.listLibraries
  });

  const createMutation = useMutation({
    mutationFn: () => api.createLibrary(name.trim(), path.trim(), type),
    onSuccess: () => {
      setName('');
      setPath('');
      setType('Audiobook');
      qc.invalidateQueries({ queryKey: ['libraries'] });
    }
  });

  const updateMutation = useMutation({
    mutationFn: (args: { id: number; type: LibraryType }) =>
      api.updateLibrary(args.id, { type: args.type }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['libraries'] });
      qc.invalidateQueries({ queryKey: ['books'] });
    }
  });

  const deleteMutation = useMutation({
    mutationFn: (id: number) => api.deleteLibrary(id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['libraries'] });
      qc.invalidateQueries({ queryKey: ['books'] });
    }
  });

  return (
    <div className="p-6 max-w-3xl mx-auto w-full">
      <h1 className="text-2xl font-semibold mb-6">Einstellungen</h1>

      <section className="bg-ink-800 ring-1 ring-ink-700 rounded-lg p-4 mb-6">
        <h2 className="text-lg font-semibold mb-3">Bibliothek hinzufügen</h2>
        <div className="flex flex-col gap-3">
          <input
            placeholder="Name (z. B. Hörbücher)"
            value={name}
            onChange={(e) => setName(e.target.value)}
            className="bg-ink-900 ring-1 ring-ink-600 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-accent-500"
          />
          <input
            placeholder="Pfad (z. B. D:\\Audiobooks oder /media/audiobooks)"
            value={path}
            onChange={(e) => setPath(e.target.value)}
            className="bg-ink-900 ring-1 ring-ink-600 rounded-lg px-3 py-2 text-sm font-mono focus:outline-none focus:ring-accent-500"
          />
          <select
            value={type}
            onChange={(e) => setType(e.target.value as LibraryType)}
            className="bg-ink-900 ring-1 ring-ink-600 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-accent-500"
          >
            <option value="Audiobook">Hörbücher</option>
            <option value="RadioPlay">Hörspiele</option>
          </select>
          <button
            disabled={createMutation.isPending || !name.trim() || !path.trim()}
            onClick={() => createMutation.mutate()}
            className="bg-accent-500 hover:bg-accent-600 disabled:bg-ink-600 disabled:text-ink-400 text-ink-900 font-medium px-4 py-2 rounded-lg self-start"
          >
            {createMutation.isPending ? 'Lege an...' : 'Hinzufügen'}
          </button>
          {createMutation.isError && (
            <div className="text-red-400 text-sm">{(createMutation.error as Error).message}</div>
          )}
        </div>
      </section>

      {auth.user?.isAdmin && <ServerUpdateSection />}
      {auth.user?.isAdmin && <AppUpdateSection />}

      <section>
        <h2 className="text-lg font-semibold mb-3">Vorhandene Bibliotheken</h2>
        {isLoading && <div className="text-ink-400">Lade...</div>}
        {libraries && libraries.length === 0 && <div className="text-ink-400">Noch keine Bibliotheken.</div>}
        <ul className="space-y-2">
          {libraries?.map((lib) => (
            <LibraryRow
              key={lib.id}
              lib={lib}
              onChangeType={(t) => updateMutation.mutate({ id: lib.id, type: t })}
              onDelete={() => {
                if (confirm(`Bibliothek "${lib.name}" wirklich entfernen?`)) deleteMutation.mutate(lib.id);
              }}
            />
          ))}
        </ul>
      </section>
    </div>
  );
}

function AppUpdateSection() {
  const qc = useQueryClient();
  const { data: config } = useQuery({
    queryKey: ['app-update-config'],
    queryFn: api.getUpdateConfig
  });

  const [repo, setRepo] = useState('');
  const [token, setToken] = useState('');
  const [savedMsg, setSavedMsg] = useState<string | null>(null);
  const [check, setCheck] = useState<AppUpdateCheck | null>(null);

  useEffect(() => {
    setRepo(config?.repo ?? '');
  }, [config?.repo]);

  const saveMutation = useMutation({
    mutationFn: () => api.setUpdateConfig(repo.trim() || null, token.trim() || null),
    onSuccess: (c) => {
      qc.setQueryData(['app-update-config'], c);
      setToken('');
      setCheck(null);
      setSavedMsg('Gespeichert.');
    }
  });

  const checkMutation = useMutation({
    mutationFn: () => api.checkUpdateNow(),
    onSuccess: (r) => setCheck(r)
  });

  return (
    <section className="bg-ink-800 ring-1 ring-ink-700 rounded-lg p-4 mb-6">
      <h2 className="text-lg font-semibold mb-1">App-Updates</h2>
      <p className="text-ink-400 text-sm mb-3">
        Aus welchem GitHub-Repository der Server neue Android-App-Versionen spiegelt. Wird sofort
        übernommen (kein Neustart nötig).
      </p>
      {config?.currentVersionName && (
        <div className="text-ink-300 text-sm mb-3">
          Aktuell gespiegelt: {config.currentVersionName} (Build {config.currentVersionCode})
        </div>
      )}
      <div className="flex flex-col gap-3">
        <input
          placeholder="GitHub-Repository (owner/name)"
          value={repo}
          onChange={(e) => {
            setRepo(e.target.value);
            setSavedMsg(null);
          }}
          className="bg-ink-900 ring-1 ring-ink-600 rounded-lg px-3 py-2 text-sm font-mono focus:outline-none focus:ring-accent-500"
        />
        <input
          type="password"
          autoComplete="off"
          placeholder={config?.hasToken ? '•••• gesetzt – leer lassen = unverändert' : 'GitHub-Token (nur für privates Repo)'}
          value={token}
          onChange={(e) => {
            setToken(e.target.value);
            setSavedMsg(null);
          }}
          className="bg-ink-900 ring-1 ring-ink-600 rounded-lg px-3 py-2 text-sm font-mono focus:outline-none focus:ring-accent-500"
        />
        <div className="flex gap-2">
          <button
            disabled={saveMutation.isPending}
            onClick={() => saveMutation.mutate()}
            className="bg-accent-500 hover:bg-accent-600 disabled:bg-ink-600 disabled:text-ink-400 text-ink-900 font-medium px-4 py-2 rounded-lg"
          >
            {saveMutation.isPending ? 'Speichere...' : 'Speichern'}
          </button>
          <button
            disabled={checkMutation.isPending}
            onClick={() => checkMutation.mutate()}
            className="px-4 py-2 rounded-lg bg-ink-700 hover:bg-ink-600 disabled:opacity-60 text-sm"
          >
            {checkMutation.isPending ? 'Teste...' : 'Verbindung testen'}
          </button>
        </div>
        {savedMsg && <div className="text-ink-400 text-sm">{savedMsg}</div>}
        {saveMutation.isError && (
          <div className="text-red-400 text-sm">{(saveMutation.error as Error).message}</div>
        )}
        {check && (
          <div className={`text-sm ${check.ok ? 'text-accent-400' : 'text-red-400'}`}>{check.message}</div>
        )}
        {checkMutation.isError && (
          <div className="text-red-400 text-sm">{(checkMutation.error as Error).message}</div>
        )}
      </div>
    </section>
  );
}

/** States during which the server is busy and may vanish mid-request. */
const SERVER_UPDATE_BUSY: ServerUpdateState[] = ['Downloading', 'Verifying', 'Installing'];
const isBusy = (state: ServerUpdateState | undefined) =>
  state !== undefined && SERVER_UPDATE_BUSY.includes(state);

function ServerUpdateSection() {
  const qc = useQueryClient();
  const [check, setCheck] = useState<ServerUpdateCheck | null>(null);

  const { data: info } = useQuery({
    queryKey: ['server-update'],
    queryFn: api.getServerUpdate
  });

  // Polled separately from the info above: this one costs the server nothing,
  // while the info query may ask GitHub. `retry: false` matters -- react-query
  // would otherwise back off exactly when we want to keep knocking.
  const {
    data: status,
    isError: statusUnreachable
  } = useQuery({
    queryKey: ['server-update-status'],
    queryFn: api.getServerUpdateStatus,
    refetchInterval: (query) => (isBusy(query.state.data?.state) ? 2000 : false),
    retry: false
  });

  const state = status?.state ?? info?.status.state;
  const message = status?.message ?? info?.status.message;

  // While the service restarts, every request fails. That is the expected
  // middle of a successful update, not an error -- so it keeps polling and says
  // so. react-query holds on to the last successful data, which is what keeps
  // the interval alive through the outage.
  const restarting = statusUnreachable && isBusy(state);

  // Once it settles, the version in the info block is stale.
  useEffect(() => {
    if (state === 'Succeeded' || state === 'Failed') {
      qc.invalidateQueries({ queryKey: ['server-update'] });
    }
  }, [state, qc]);

  const startMutation = useMutation({
    mutationFn: () => api.startServerUpdate(),
    onSuccess: (s) => {
      setCheck(null);
      qc.setQueryData(['server-update-status'], s);
    }
  });

  const checkMutation = useMutation({
    mutationFn: () => api.checkServerUpdateNow(),
    onSuccess: (r) => {
      setCheck(r);
      qc.invalidateQueries({ queryKey: ['server-update'] });
    }
  });

  const busy = isBusy(state) || startMutation.isPending;

  return (
    <section className="bg-ink-800 ring-1 ring-ink-700 rounded-lg p-4 mb-6">
      <h2 className="text-lg font-semibold mb-1">Server-Update</h2>
      <p className="text-ink-400 text-sm mb-3">
        Installiert den Windows-Installer aus den Releases direkt auf dem Server. Der Dienst wird
        dabei gestoppt und neu gestartet – laufende Wiedergabe bricht für etwa eine halbe Minute ab.
      </p>

      <div className="text-ink-300 text-sm mb-3">
        Installiert: {info?.currentVersion ?? 'unbekannt'}
        {info?.latestVersion && <> · Verfügbar: {info.latestVersion}</>}
        {info?.available && <span className="text-accent-400"> · Update verfügbar</span>}
      </div>

      {info && !info.supported ? (
        <div className="text-ink-400 text-sm">{info.unsupportedReason}</div>
      ) : (
        <div className="flex flex-col gap-3">
          <div className="flex gap-2">
            <button
              disabled={busy || !info?.available}
              onClick={() => {
                if (
                  confirm(
                    `Server auf Version ${info?.latestVersion} aktualisieren?\n\n` +
                      'Der Dienst wird dabei gestoppt und neu gestartet. Laufende Wiedergabe bricht ab.'
                  )
                ) {
                  startMutation.mutate();
                }
              }}
              className="bg-accent-500 hover:bg-accent-600 disabled:bg-ink-600 disabled:text-ink-400 text-ink-900 font-medium px-4 py-2 rounded-lg"
            >
              {busy ? 'Aktualisiere...' : 'Jetzt aktualisieren'}
            </button>
            <button
              disabled={checkMutation.isPending || busy}
              onClick={() => checkMutation.mutate()}
              className="px-4 py-2 rounded-lg bg-ink-700 hover:bg-ink-600 disabled:opacity-60 text-sm"
            >
              {checkMutation.isPending ? 'Suche...' : 'Nach Update suchen'}
            </button>
          </div>

          {restarting ? (
            <div className="text-ink-300 text-sm">
              Server startet neu... Diese Seite meldet sich von selbst, sobald er wieder da ist.
            </div>
          ) : (
            state &&
            state !== 'Idle' && (
              <div
                className={`text-sm ${
                  state === 'Failed'
                    ? 'text-red-400'
                    : state === 'Succeeded'
                      ? 'text-accent-400'
                      : 'text-ink-300'
                }`}
              >
                {message ?? state}
              </div>
            )
          )}

          {check && !check.ok && <div className="text-red-400 text-sm">{check.message}</div>}
          {check && check.ok && !info?.available && (
            <div className="text-ink-300 text-sm">{check.message}</div>
          )}
          {startMutation.isError && (
            <div className="text-red-400 text-sm">{(startMutation.error as Error).message}</div>
          )}
          {checkMutation.isError && (
            <div className="text-red-400 text-sm">{(checkMutation.error as Error).message}</div>
          )}
        </div>
      )}
    </section>
  );
}

function LibraryRow({
  lib,
  onChangeType,
  onDelete
}: {
  lib: LibraryFolder;
  onChangeType: (type: LibraryType) => void;
  onDelete: () => void;
}) {
  const qc = useQueryClient();

  const { data: status } = useQuery({
    queryKey: ['scan-status', lib.id],
    queryFn: () => api.getScanStatus(lib.id),
    refetchInterval: (query) => (query.state.data?.state === 'Running' ? 1500 : false),
    refetchOnWindowFocus: true
  });

  const scanMutation = useMutation({
    mutationFn: () => api.scanLibrary(lib.id),
    onSuccess: (data) => {
      qc.setQueryData(['scan-status', lib.id], data);
    }
  });

  // When the scan transitions out of Running, refresh the dependent data.
  const finishedAt = status?.finishedAt ?? null;
  const state = status?.state;
  useEffect(() => {
    if (state === 'Completed' || state === 'Failed' || state === 'Cancelled') {
      qc.invalidateQueries({ queryKey: ['libraries'] });
      qc.invalidateQueries({ queryKey: ['books'] });
      qc.invalidateQueries({ queryKey: ['series'] });
    }
  }, [state, finishedAt, qc]);

  const isRunning = state === 'Running' || scanMutation.isPending;

  return (
    <li className="bg-ink-800 ring-1 ring-ink-700 rounded-lg p-4">
      <div className="flex items-center gap-3 flex-wrap">
        <div className="flex-1 min-w-0">
          <div className="font-medium">{lib.name}</div>
          <div className="text-ink-400 text-xs font-mono truncate">{lib.path}</div>
          <div className="text-ink-400 text-xs mt-1">{LIBRARY_TYPE_LABEL[lib.type]}</div>
          {lib.lastScanAt && (
            <div className="text-ink-400 text-xs mt-1">
              Zuletzt gescannt: {new Date(lib.lastScanAt).toLocaleString()}
            </div>
          )}
        </div>
        <select
          value={lib.type}
          onChange={(e) => onChangeType(e.target.value as LibraryType)}
          className="bg-ink-900 ring-1 ring-ink-600 rounded-lg px-2 py-1.5 text-sm focus:outline-none focus:ring-accent-500"
          title="Typ ändern"
        >
          <option value="Audiobook">Hörbücher</option>
          <option value="RadioPlay">Hörspiele</option>
        </select>
        <button
          disabled={isRunning}
          onClick={() => scanMutation.mutate()}
          className="px-3 py-1.5 rounded bg-ink-700 hover:bg-ink-600 disabled:opacity-60 text-sm"
        >
          {isRunning ? 'Scannt...' : 'Scannen'}
        </button>
        <button
          onClick={onDelete}
          className="px-3 py-1.5 rounded bg-ink-700 hover:bg-red-600 text-sm"
        >
          Löschen
        </button>
      </div>
      <ScanStatusLine status={status} />
    </li>
  );
}

function ScanStatusLine({ status }: { status: ScanStatus | undefined }) {
  if (!status || status.state === 'Idle') return null;

  if (status.state === 'Running') {
    return (
      <div className="text-ink-300 text-sm mt-3">
        Scan läuft im Hintergrund... Du kannst die Seite verlassen, der Scan wird zu Ende geführt.
      </div>
    );
  }

  if (status.state === 'Completed' && status.result) {
    const r = status.result;
    return (
      <div className="text-ink-300 text-sm mt-3">
        Scan abgeschlossen: {r.booksAdded} neu, {r.booksUpdated} aktualisiert, {r.booksRemoved} entfernt ({r.filesScanned} Dateien).
      </div>
    );
  }

  if (status.state === 'Failed') {
    return <div className="text-red-400 text-sm mt-3">Scan fehlgeschlagen: {status.error ?? 'Unbekannter Fehler'}</div>;
  }

  if (status.state === 'Cancelled') {
    return <div className="text-ink-400 text-sm mt-3">Scan wurde abgebrochen.</div>;
  }

  return null;
}
