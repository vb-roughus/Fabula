import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { api } from '../api/client';

export function SettingsPage() {
  const qc = useQueryClient();
  const [name, setName] = useState('');
  const [path, setPath] = useState('');

  const { data: libraries, isLoading } = useQuery({
    queryKey: ['libraries'],
    queryFn: api.listLibraries
  });

  const createMutation = useMutation({
    mutationFn: () => api.createLibrary(name.trim(), path.trim()),
    onSuccess: () => {
      setName('');
      setPath('');
      qc.invalidateQueries({ queryKey: ['libraries'] });
    }
  });

  const scanMutation = useMutation({
    mutationFn: (id: number) => api.scanLibrary(id),
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

      <section>
        <h2 className="text-lg font-semibold mb-3">Vorhandene Bibliotheken</h2>
        {isLoading && <div className="text-ink-400">Lade...</div>}
        {libraries && libraries.length === 0 && <div className="text-ink-400">Noch keine Bibliotheken.</div>}
        <ul className="space-y-2">
          {libraries?.map((lib) => (
            <li key={lib.id} className="bg-ink-800 ring-1 ring-ink-700 rounded-lg p-4 flex items-center gap-3 flex-wrap">
              <div className="flex-1 min-w-0">
                <div className="font-medium">{lib.name}</div>
                <div className="text-ink-400 text-xs font-mono truncate">{lib.path}</div>
                {lib.lastScanAt && (
                  <div className="text-ink-400 text-xs mt-1">
                    Zuletzt gescannt: {new Date(lib.lastScanAt).toLocaleString()}
                  </div>
                )}
              </div>
              <button
                disabled={scanMutation.isPending}
                onClick={() => scanMutation.mutate(lib.id)}
                className="px-3 py-1.5 rounded bg-ink-700 hover:bg-ink-600 text-sm"
              >
                {scanMutation.isPending && scanMutation.variables === lib.id ? 'Scannt...' : 'Scannen'}
              </button>
              <button
                onClick={() => {
                  if (confirm(`Bibliothek "${lib.name}" wirklich entfernen?`)) deleteMutation.mutate(lib.id);
                }}
                className="px-3 py-1.5 rounded bg-ink-700 hover:bg-red-600 text-sm"
              >
                Löschen
              </button>
            </li>
          ))}
        </ul>
        {scanMutation.isSuccess && scanMutation.data && (
          <div className="text-ink-300 text-sm mt-3">
            Scan abgeschlossen: {scanMutation.data.booksAdded} neu, {scanMutation.data.booksUpdated} aktualisiert, {scanMutation.data.booksRemoved} entfernt ({scanMutation.data.filesScanned} Dateien).
          </div>
        )}
      </section>
    </div>
  );
}
