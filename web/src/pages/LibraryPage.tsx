import { useInfiniteQuery } from '@tanstack/react-query';
import { useEffect, useMemo, useRef, useState } from 'react';
import { api } from '../api/client';
import type { BookSummary, LibraryType } from '../api/types';
import { BookCard } from '../components/BookCard';

type Filter = 'all' | LibraryType;

const PAGE_SIZE = 60;

/** The books of every page in order, without a book appearing twice. A book
 *  added between two requests moves the page boundary, so the last book of one
 *  page can turn up again as the first of the next. */
function mergePages(pages: { items: BookSummary[] }[]): BookSummary[] {
  const seen = new Set<number>();
  const books: BookSummary[] = [];
  for (const page of pages) {
    for (const book of page.items) {
      if (seen.has(book.id)) continue;
      seen.add(book.id);
      books.push(book);
    }
  }
  return books;
}

export function LibraryPage() {
  const [search, setSearch] = useState('');
  const [filter, setFilter] = useState<Filter>('all');

  // The server is asked once typing pauses, not on every key: a search reads
  // through the whole library's names.
  const [asked, setAsked] = useState('');
  useEffect(() => {
    const timer = setTimeout(() => setAsked(search.trim()), 300);
    return () => clearTimeout(timer);
  }, [search]);

  // Books in a series are left out of the plain listing -- they are reached
  // through the series -- but a search finds everything, so it is not narrowed.
  const searching = asked.length > 0;
  const type = filter === 'all' ? undefined : filter;

  const {
    data,
    isLoading,
    isError,
    error,
    fetchNextPage,
    hasNextPage,
    isFetchingNextPage,
    isFetchNextPageError
  } = useInfiniteQuery({
    queryKey: ['books', asked, filter],
    queryFn: ({ pageParam }) =>
      api.listBooks({
        search: asked,
        standalone: !searching,
        type,
        page: pageParam,
        pageSize: PAGE_SIZE
      }),
    initialPageParam: 1,
    // From the server's own total: the next page exists while the pages fetched
    // so far cover fewer books than it reports.
    getNextPageParam: (last) => (last.page * last.pageSize < last.total ? last.page + 1 : undefined),
    staleTime: 0
  });

  const books = useMemo(() => (data ? mergePages(data.pages) : []), [data]);
  const total = data?.pages[0]?.total ?? 0;

  // Asks for the next page when the end of the list scrolls into reach. Set up
  // again after every page, so one too short to push the end out of view is
  // followed by the next at once. Stands down after a failure: retrying on its
  // own would repeat as fast as it can fail.
  const sentinel = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    const element = sentinel.current;
    if (!element || !hasNextPage || isFetchingNextPage || isFetchNextPageError) return;
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0]?.isIntersecting) void fetchNextPage();
      },
      { rootMargin: '600px' }
    );
    observer.observe(element);
    return () => observer.disconnect();
  }, [hasNextPage, isFetchingNextPage, isFetchNextPageError, fetchNextPage, books.length]);

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
      {isError && !data && (
        <div className="text-red-400">Fehler: {(error as Error).message}</div>
      )}

      {data && books.length === 0 && searching && (
        <div className="text-ink-400">Nichts gefunden für „{asked}“.</div>
      )}

      {data && books.length === 0 && !searching && filter !== 'all' && (
        <div className="text-ink-400">In dieser Kategorie gibt es keine Titel ohne Serie.</div>
      )}

      {data && books.length === 0 && !searching && filter === 'all' && (
        <div className="text-ink-400">
          Keine Titel ohne Serie. Titel einer Serie findest du unter{' '}
          <a href="/series" className="text-accent-400 hover:underline">Serien</a> oder über die Suche.
          Neue Titel erscheinen nach einem Scan, den du unter{' '}
          <a href="/settings" className="text-accent-400 hover:underline">Einstellungen</a> startest.
        </div>
      )}

      {books.length > 0 && (
        <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6 gap-4">
          {books.map((book) => (
            <BookCard key={book.id} book={book} />
          ))}
        </div>
      )}

      {hasNextPage && !isFetchNextPageError && <div ref={sentinel} className="h-8" />}

      {isFetchingNextPage && (
        <div className="text-ink-400 text-sm mt-4">Lade weitere Titel...</div>
      )}

      {isFetchNextPageError && (
        <button
          type="button"
          onClick={() => void fetchNextPage()}
          className="mt-4 px-3 py-1.5 rounded-full text-sm ring-1 bg-ink-800 text-ink-200 ring-ink-700 hover:ring-ink-500"
        >
          Laden fehlgeschlagen – erneut versuchen
        </button>
      )}

      {data && (
        <div className="text-ink-400 text-xs mt-4">
          {books.length} von {total}
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
