import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { api } from '../api/client';

export function SeriesPage() {
  const { data, isLoading, isError, error } = useQuery({
    queryKey: ['series'],
    queryFn: api.listSeries
  });

  return (
    <div className="p-6 max-w-7xl mx-auto w-full">
      <h1 className="text-2xl font-semibold text-ink-100 mb-6">Serien</h1>

      {isLoading && <div className="text-ink-400">Lade Serien...</div>}
      {isError && <div className="text-red-400">Fehler: {(error as Error).message}</div>}

      {data && data.length === 0 && (
        <div className="text-ink-400">
          Noch keine Serien. Serien werden aus dem Grouping-Tag deiner Hörbuch-Dateien erkannt
          (z. B. „Perry Rhodan # 1"). Scanne eine Bibliothek, um sie anzulegen.
        </div>
      )}

      {data && data.length > 0 && (
        <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6 gap-4">
          {data.map((s) => (
            <Link
              key={s.id}
              to={`/series/${s.id}`}
              className="group flex flex-col rounded-xl bg-ink-800 hover:bg-ink-700 transition-colors overflow-hidden ring-1 ring-ink-700 hover:ring-accent-500"
            >
              <div className="aspect-square bg-ink-700 overflow-hidden flex items-center justify-center">
                {s.coverUrl ? (
                  <img
                    src={s.coverUrl}
                    alt={s.name}
                    className="w-full h-full object-cover group-hover:scale-105 transition-transform"
                  />
                ) : (
                  <div className="text-ink-400 text-xs text-center px-4 font-serif italic">{s.name}</div>
                )}
              </div>
              <div className="p-3">
                <div className="text-ink-100 text-sm font-medium leading-tight line-clamp-2">{s.name}</div>
                <div className="text-ink-400 text-xs mt-2">
                  {s.bookCount} Band{s.bookCount === 1 ? '' : 'e'}
                </div>
              </div>
            </Link>
          ))}
        </div>
      )}
    </div>
  );
}
