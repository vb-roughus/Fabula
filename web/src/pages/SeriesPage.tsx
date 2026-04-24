import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { SeriesSummary } from '../api/types';

export function SeriesPage() {
  const qc = useQueryClient();
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [editingId, setEditingId] = useState<number | null>(null);
  const [editName, setEditName] = useState('');
  const [editDescription, setEditDescription] = useState('');

  const { data: series, isLoading } = useQuery({
    queryKey: ['series'],
    queryFn: api.listSeries
  });

  const createMutation = useMutation({
    mutationFn: () => api.createSeries(name.trim(), description.trim() || null),
    onSuccess: () => {
      setName('');
      setDescription('');
      qc.invalidateQueries({ queryKey: ['series'] });
    }
  });

  const updateMutation = useMutation({
    mutationFn: (s: SeriesSummary) =>
      api.updateSeries(s.id, editName.trim(), editDescription.trim() || null),
    onSuccess: () => {
      setEditingId(null);
      qc.invalidateQueries({ queryKey: ['series'] });
      qc.invalidateQueries({ queryKey: ['books'] });
    }
  });

  const deleteMutation = useMutation({
    mutationFn: (id: number) => api.deleteSeries(id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['series'] });
      qc.invalidateQueries({ queryKey: ['books'] });
    }
  });

  const beginEdit = (s: SeriesSummary) => {
    setEditingId(s.id);
    setEditName(s.name);
    setEditDescription(s.description ?? '');
  };

  return (
    <div className="p-6 max-w-3xl mx-auto w-full">
      <h1 className="text-2xl font-semibold mb-6">Serien</h1>

      <section className="bg-ink-800 ring-1 ring-ink-700 rounded-lg p-4 mb-6">
        <h2 className="text-lg font-semibold mb-3">Neue Serie anlegen</h2>
        <div className="flex flex-col gap-3">
          <input
            placeholder="Name (z. B. Harry Potter)"
            value={name}
            onChange={(e) => setName(e.target.value)}
            className="bg-ink-900 ring-1 ring-ink-600 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-accent-500"
          />
          <textarea
            placeholder="Beschreibung (optional)"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            rows={2}
            className="bg-ink-900 ring-1 ring-ink-600 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-accent-500"
          />
          <button
            disabled={createMutation.isPending || !name.trim()}
            onClick={() => createMutation.mutate()}
            className="bg-accent-500 hover:bg-accent-600 disabled:bg-ink-600 disabled:text-ink-400 text-ink-900 font-medium px-4 py-2 rounded-lg self-start"
          >
            {createMutation.isPending ? 'Lege an...' : 'Anlegen'}
          </button>
          {createMutation.isError && (
            <div className="text-red-400 text-sm">{(createMutation.error as Error).message}</div>
          )}
        </div>
      </section>

      <section>
        <h2 className="text-lg font-semibold mb-3">Vorhandene Serien</h2>
        {isLoading && <div className="text-ink-400">Lade...</div>}
        {series && series.length === 0 && <div className="text-ink-400">Noch keine Serien.</div>}
        <ul className="space-y-2">
          {series?.map((s) => (
            <li key={s.id} className="bg-ink-800 ring-1 ring-ink-700 rounded-lg p-4">
              {editingId === s.id ? (
                <div className="flex flex-col gap-2">
                  <input
                    value={editName}
                    onChange={(e) => setEditName(e.target.value)}
                    className="bg-ink-900 ring-1 ring-ink-600 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-accent-500"
                  />
                  <textarea
                    value={editDescription}
                    onChange={(e) => setEditDescription(e.target.value)}
                    rows={2}
                    className="bg-ink-900 ring-1 ring-ink-600 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-accent-500"
                  />
                  <div className="flex gap-2">
                    <button
                      disabled={updateMutation.isPending || !editName.trim()}
                      onClick={() => updateMutation.mutate(s)}
                      className="px-3 py-1.5 rounded bg-accent-500 hover:bg-accent-600 disabled:bg-ink-600 disabled:text-ink-400 text-ink-900 text-sm font-medium"
                    >
                      Speichern
                    </button>
                    <button
                      onClick={() => setEditingId(null)}
                      className="px-3 py-1.5 rounded bg-ink-700 hover:bg-ink-600 text-sm"
                    >
                      Abbrechen
                    </button>
                  </div>
                  {updateMutation.isError && updateMutation.variables?.id === s.id && (
                    <div className="text-red-400 text-sm">{(updateMutation.error as Error).message}</div>
                  )}
                </div>
              ) : (
                <div className="flex items-center gap-3 flex-wrap">
                  <div className="flex-1 min-w-0">
                    <Link
                      to={`/series/${s.id}`}
                      className="font-medium hover:text-accent-400"
                    >
                      {s.name}
                    </Link>
                    {s.description && (
                      <div className="text-ink-400 text-sm truncate">{s.description}</div>
                    )}
                    <div className="text-ink-400 text-xs mt-1">
                      {s.bookCount} {s.bookCount === 1 ? 'Hörbuch' : 'Hörbücher'}
                    </div>
                  </div>
                  <button
                    onClick={() => beginEdit(s)}
                    className="px-3 py-1.5 rounded bg-ink-700 hover:bg-ink-600 text-sm"
                  >
                    Bearbeiten
                  </button>
                  <button
                    onClick={() => {
                      if (confirm(`Serie "${s.name}" wirklich entfernen? Zugeordnete Hörbücher bleiben erhalten.`))
                        deleteMutation.mutate(s.id);
                    }}
                    className="px-3 py-1.5 rounded bg-ink-700 hover:bg-red-600 text-sm"
                  >
                    Löschen
                  </button>
                </div>
              )}
            </li>
          ))}
        </ul>
      </section>
    </div>
  );
}
