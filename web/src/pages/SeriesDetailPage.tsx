import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { api } from '../api/client';

export function SeriesDetailPage() {
  const { id } = useParams<{ id: string }>();
  const seriesId = Number(id);
  const qc = useQueryClient();

  const { data: series, isLoading, isError, error } = useQuery({
    queryKey: ['series', seriesId],
    queryFn: () => api.getSeries(seriesId),
    enabled: Number.isFinite(seriesId)
  });

  const reorder = useMutation({
    mutationFn: () => api.reorderSeries(seriesId),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['series', seriesId] });
      qc.invalidateQueries({ queryKey: ['series'] });
      qc.invalidateQueries({ queryKey: ['books'] });
    }
  });

  if (isLoading) return <div className="p-6 text-ink-400">Lade...</div>;
  if (isError) return <div className="p-6 text-red-400">Fehler: {(error as Error).message}</div>;
  if (!series) return null;

  return (
    <div className="p-6 max-w-5xl mx-auto w-full">
      <Link to="/series" className="text-ink-400 hover:text-ink-100 text-sm">
        ← Alle Serien
      </Link>
      <h1 className="text-3xl font-semibold mt-2">{series.name}</h1>
      {series.description && (
        <p className="text-ink-300 mt-2 whitespace-pre-wrap">{series.description}</p>
      )}

      <section className="mt-6">
        <div className="flex items-baseline justify-between gap-3 mb-3 flex-wrap">
          <h2 className="text-lg font-semibold">
            Hörbücher ({series.books.length})
          </h2>
          {series.books.length > 0 && (
            <div className="flex items-center gap-3">
              {reorder.isSuccess && !reorder.isPending && (
                <span className="text-ink-400 text-xs">
                  {reorder.data.updated > 0
                    ? `${reorder.data.updated} aktualisiert`
                    : 'Bereits aktuell'}
                </span>
              )}
              <button
                onClick={() => reorder.mutate()}
                disabled={reorder.isPending}
                title="Positionen aus den Ordnernamen neu ableiten (manuelle Werte bleiben erhalten)."
                className="text-sm px-3 py-1.5 rounded bg-ink-700 hover:bg-ink-600 disabled:bg-ink-800 disabled:text-ink-500"
              >
                {reorder.isPending ? 'Berechnet...' : 'Reihenfolge neu berechnen'}
              </button>
            </div>
          )}
        </div>
        {reorder.isError && (
          <div className="text-red-400 text-sm mb-3">
            {(reorder.error as Error).message}
          </div>
        )}
        {series.books.length === 0 && (
          <div className="text-ink-400 text-sm">
            Noch keine Hörbücher zugeordnet. Zuweisung erfolgt auf der Detailseite eines Hörbuchs.
          </div>
        )}
        <ul className="divide-y divide-ink-700 rounded-lg ring-1 ring-ink-700 bg-ink-800 overflow-hidden">
          {series.books.map((b) => (
            <li key={b.id}>
              <Link
                to={`/book/${b.id}`}
                className="flex items-center gap-3 px-4 py-3 hover:bg-ink-700"
              >
                <div className="w-12 h-12 rounded bg-ink-900 ring-1 ring-ink-700 overflow-hidden shrink-0">
                  {b.coverUrl && (
                    <img src={b.coverUrl} alt={b.title} className="w-full h-full object-cover" />
                  )}
                </div>
                <div className="flex-1 min-w-0">
                  <div className="truncate">{b.title}</div>
                  {b.authors.length > 0 && (
                    <div className="text-ink-400 text-xs truncate">{b.authors.join(', ')}</div>
                  )}
                </div>
                {b.position != null && (
                  <span className="text-ink-400 text-xs tabular-nums ml-3">
                    Teil {b.position}
                  </span>
                )}
              </Link>
            </li>
          ))}
        </ul>
      </section>
    </div>
  );
}
