import { useQuery } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { api } from '../api/client';
import type { LibraryType } from '../api/types';
import { BookCard } from '../components/BookCard';

type Filter = 'all' | LibraryType;

export function LibraryPage() {
  const [search, setSearch] = useState('');
  const [filter, setFilter] = useState<Filter>('all');
  const [page] = useState(1);

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ['books', search, page],
    queryFn: () => api.listBooks({ search, page, pageSize: 60 })
  });

  const filtered = useMemo(() => {
    if (!data) return null;
    if (filter === 'all') return data.items;
    return data.items.filter((b) => b.type === filter);
  }, [data, filter]);

  return (
    <div className="p-6 max-w-7xl mx-auto w-full">
      <div className="flex items-baseline justify-between mb-4 flex-wrap gap-3">
        <h1 className="text-2xl font-semibold text-ink-100">Bibliothek</h1>
        <input
          type="search"
          placeholder="Suchen..."
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          className="bg-ink-800 ring-1 ring-ink-600 rounded-lg px-3 py-2 text-sm w-64 focus:outline-none focus:ring-accent-500"
        />
      </div>

      <div className="flex gap-2 mb-6">
        <FilterChip active={filter === 'all'} onClick={() => setFilter('all')}>
          Alle
        </FilterChip>
        <FilterChip active={filter === 'Audiobook'} onClick={() => setFilter('Audiobook')}>
          Hörbücher
        </FilterChip>
        <FilterChip active={filter === 'RadioPlay'} onClick={() => setFilter('RadioPlay')}>
          Hörspiele
        </FilterChip>
      </div>

      {isLoading && <div className="text-ink-400">Lade Bibliothek...</div>}
      {isError && (
        <div className="text-red-400">Fehler: {(error as Error).message}</div>
      )}

      {data && data.items.length === 0 && (
        <div className="text-ink-400">
          Noch nichts in der Bibliothek. Lege eine Bibliothek unter{' '}
          <a href="/settings" className="text-accent-400 hover:underline">Einstellungen</a> an und starte einen Scan.
        </div>
      )}

      {filtered && filtered.length === 0 && data && data.items.length > 0 && (
        <div className="text-ink-400">In dieser Kategorie ist noch nichts vorhanden.</div>
      )}

      {filtered && filtered.length > 0 && (
        <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6 gap-4">
          {filtered.map((book) => (
            <BookCard key={book.id} book={book} />
          ))}
        </div>
      )}

      {data && (
        <div className="text-ink-400 text-xs mt-4">
          {filtered?.length ?? 0} von {data.total}
        </div>
      )}
    </div>
  );
}

function FilterChip({
  active,
  onClick,
  children
}: {
  active: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`px-3 py-1.5 rounded-full text-sm ring-1 transition ${
        active
          ? 'bg-accent-500 text-ink-900 ring-accent-500'
          : 'bg-ink-800 text-ink-200 ring-ink-700 hover:ring-ink-500'
      }`}
    >
      {children}
    </button>
  );
}
