import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api } from '../api/client';
import { usePlayerContext } from '../hooks/playerContext';
import { formatDurationHours, formatTimeSpan, parseTimeSpan } from '../lib/time';

export function BookPage() {
  const { id } = useParams<{ id: string }>();
  const bookId = Number(id);
  const { state, loadBook, play, jumpToChapter, seekInBook } = usePlayerContext();
  const qc = useQueryClient();

  const { data: book, isLoading, isError, error } = useQuery({
    queryKey: ['book', bookId],
    queryFn: () => api.getBook(bookId),
    enabled: Number.isFinite(bookId)
  });

  const { data: bookmarks } = useQuery({
    queryKey: ['bookmarks', bookId],
    queryFn: () => api.listBookmarks(bookId),
    enabled: Number.isFinite(bookId)
  });

  const deleteBookmark = useMutation({
    mutationFn: (bookmarkId: number) => api.deleteBookmark(bookmarkId),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['bookmarks', bookId] })
  });

  const updateBookmark = useMutation({
    mutationFn: (args: { id: number; note: string | null }) => api.updateBookmark(args.id, args.note),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['bookmarks', bookId] })
  });

  const chaptersWithSeconds = useMemo(
    () =>
      (book?.chapters ?? []).map((c) => ({
        ...c,
        startSec: parseTimeSpan(c.start),
        endSec: parseTimeSpan(c.end)
      })),
    [book]
  );

  const chapterAt = (sec: number) =>
    chaptersWithSeconds.find((c) => sec >= c.startSec && sec < c.endSec) ?? null;

  useEffect(() => {
    if (book && state.book?.id !== book.id) {
      loadBook(book);
    }
  }, [book, state.book, loadBook]);

  if (isLoading) return <div className="p-6 text-ink-400">Lade...</div>;
  if (isError) return <div className="p-6 text-red-400">Fehler: {(error as Error).message}</div>;
  if (!book) return null;

  const totalSeconds = parseTimeSpan(book.duration);

  return (
    <div className="p-6 max-w-5xl mx-auto w-full">
      <div className="flex gap-6 flex-wrap md:flex-nowrap">
        <div className="w-full md:w-64 shrink-0 aspect-square rounded-xl overflow-hidden bg-ink-800 ring-1 ring-ink-700">
          {book.coverUrl ? (
            <img src={book.coverUrl} alt={book.title} className="w-full h-full object-cover" />
          ) : (
            <div className="w-full h-full flex items-center justify-center text-ink-400 font-serif italic text-center p-4">
              {book.title}
            </div>
          )}
        </div>

        <div className="flex-1 min-w-0">
          <h1 className="text-3xl font-semibold text-ink-100">{book.title}</h1>
          {book.subtitle && <div className="text-ink-300 mt-1">{book.subtitle}</div>}

          {book.authors.length > 0 && (
            <div className="text-ink-200 mt-3">von {book.authors.join(', ')}</div>
          )}
          {book.narrators.length > 0 && (
            <div className="text-ink-400 text-sm">gesprochen von {book.narrators.join(', ')}</div>
          )}
          {book.series && (
            <div className="text-accent-400 mt-2">
              {book.seriesId != null ? (
                <Link to={`/series/${book.seriesId}`} className="hover:underline">
                  {book.series}
                </Link>
              ) : (
                book.series
              )}
              {book.seriesPosition != null && ` – Teil ${book.seriesPosition}`}
            </div>
          )}

          <div className="flex gap-3 mt-4 text-ink-400 text-sm flex-wrap">
            <span>{formatDurationHours(totalSeconds)}</span>
            {book.publishYear && <span>· {book.publishYear}</span>}
            {book.publisher && <span>· {book.publisher}</span>}
          </div>

          <button
            onClick={play}
            className="mt-6 bg-accent-500 hover:bg-accent-600 text-ink-900 font-medium px-6 py-2 rounded-lg"
          >
            {state.book?.id === book.id && state.isPlaying ? 'Wird abgespielt' : 'Abspielen'}
          </button>

          {book.description && (
            <p className="text-ink-300 mt-6 leading-relaxed whitespace-pre-wrap">{book.description}</p>
          )}
        </div>
      </div>

      {bookmarks && bookmarks.length > 0 && (
        <section className="mt-10">
          <h2 className="text-lg font-semibold text-ink-100 mb-3">Lesezeichen</h2>
          <ul className="divide-y divide-ink-700 rounded-lg ring-1 ring-ink-700 bg-ink-800 overflow-hidden">
            {bookmarks.map((bm) => {
              const posSec = parseTimeSpan(bm.position);
              const chapter = chapterAt(posSec);
              return (
                <li key={bm.id} className="flex items-center gap-3 px-4 py-3 hover:bg-ink-700">
                  <button
                    onClick={() => {
                      if (state.book?.id !== book.id) loadBook(book);
                      seekInBook(posSec);
                      play();
                    }}
                    className="flex-1 min-w-0 text-left"
                  >
                    <div className="flex items-baseline gap-2">
                      <span className="text-accent-400 tabular-nums">{formatTimeSpan(posSec)}</span>
                      {chapter && <span className="text-ink-400 text-sm truncate">· {chapter.title}</span>}
                    </div>
                    {bm.note && <div className="text-ink-200 text-sm mt-1 truncate">{bm.note}</div>}
                    <div className="text-ink-400 text-xs mt-0.5">
                      {new Date(bm.createdAt).toLocaleString()}
                    </div>
                  </button>
                  <button
                    onClick={(e) => {
                      e.stopPropagation();
                      const next = window.prompt('Notiz bearbeiten:', bm.note ?? '');
                      if (next !== null) updateBookmark.mutate({ id: bm.id, note: next.trim() || null });
                    }}
                    title="Notiz bearbeiten"
                    className="text-ink-400 hover:text-ink-100 p-1"
                  >
                    ✎
                  </button>
                  <button
                    onClick={(e) => {
                      e.stopPropagation();
                      if (confirm('Lesezeichen löschen?')) deleteBookmark.mutate(bm.id);
                    }}
                    title="Löschen"
                    className="text-ink-400 hover:text-red-400 p-1"
                  >
                    ✕
                  </button>
                </li>
              );
            })}
          </ul>
        </section>
      )}

      {book.chapters.length > 0 && (
        <section className="mt-10">
          <h2 className="text-lg font-semibold text-ink-100 mb-3">Kapitel</h2>
          <ul className="divide-y divide-ink-700 rounded-lg ring-1 ring-ink-700 bg-ink-800 overflow-hidden">
            {book.chapters.map((c) => {
              const isActive = state.book?.id === book.id && state.currentChapter?.index === c.index;
              return (
                <li key={c.index}>
                  <button
                    onClick={() => {
                      if (state.book?.id !== book.id) loadBook(book);
                      jumpToChapter(c);
                      play();
                    }}
                    className={`w-full flex items-center justify-between text-left px-4 py-3 hover:bg-ink-700 ${
                      isActive ? 'bg-ink-700 text-accent-400' : ''
                    }`}
                  >
                    <span className="flex items-baseline gap-3 min-w-0">
                      <span className="text-ink-400 text-xs tabular-nums w-6 text-right">{c.index + 1}</span>
                      <span className="truncate">{c.title}</span>
                    </span>
                    <span className="text-ink-400 text-xs tabular-nums ml-3">
                      {formatTimeSpan(parseTimeSpan(c.start))}
                    </span>
                  </button>
                </li>
              );
            })}
          </ul>
        </section>
      )}
    </div>
  );
}
