import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { api } from '../api/client';
import { BookCard } from '../components/BookCard';

export function LibraryPage() {
  const [search, setSearch] = useState('');
  const [page] = useState(1);
  const { data, isLoading, isError, error } = useQuery({
    queryKey: ['books', search, page],
    queryFn: () => api.listBooks({ search, page, pageSize: 60 })
  });

  return (
    <div className="p-6 max-w-7xl mx-auto w-full">
      <div className="flex items-baseline justify-between mb-6 flex-wrap gap-3">
        <h1 className="text-2xl font-semibold text-ink-100">Hörbücher</h1>
        <input
          type="search"
          placeholder="Suchen..."
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          className="bg-ink-800 ring-1 ring-ink-600 rounded-lg px-3 py-2 text-sm w-64 focus:outline-none focus:ring-accent-500"
        />
      </div>

      {isLoading && <div className="text-ink-400">Lade Bibliothek...</div>}
      {isError && (
        <div className="text-red-400">Fehler: {(error as Error).message}</div>
      )}

      {data && data.items.length === 0 && (
        <div className="text-ink-400">
          Keine Hörbücher gefunden. Lege eine Bibliothek unter <a href="/settings" className="text-accent-400 hover:underline">Einstellungen</a> an und starte einen Scan.
        </div>
      )}

      {data && data.items.length > 0 && (
        <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6 gap-4">
          {data.items.map((book) => (
            <BookCard key={book.id} book={book} />
          ))}
        </div>
      )}

      {data && <div className="text-ink-400 text-xs mt-4">{data.total} Buch{data.total === 1 ? '' : 'er'}</div>}
    </div>
  );
}
