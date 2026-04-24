import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { api } from '../api/client';
import { usePlayerContext } from '../hooks/playerContext';
import { formatDurationHours, formatTimeSpan, parseTimeSpan } from '../lib/time';

export function BookPage() {
  const { id } = useParams<{ id: string }>();
  const bookId = Number(id);
  const qc = useQueryClient();
  const { state, loadBook, play, jumpToChapter } = usePlayerContext();

  const { data: book, isLoading, isError, error } = useQuery({
    queryKey: ['book', bookId],
    queryFn: () => api.getBook(bookId),
    enabled: Number.isFinite(bookId)
  });

  useEffect(() => {
    if (book && state.book?.id !== book.id) {
      loadBook(book);
    }
  }, [book, state.book, loadBook]);

  const [editingSeries, setEditingSeries] = useState(false);
  const [selectedSeriesId, setSelectedSeriesId] = useState<string>('');
  const [positionInput, setPositionInput] = useState<string>('');

  const { data: seriesList } = useQuery({
    queryKey: ['series'],
    queryFn: api.listSeries,
    enabled: editingSeries
  });

  const assignMutation = useMutation({
    mutationFn: () => {
      const sid = selectedSeriesId ? Number(selectedSeriesId) : null;
      const pos = positionInput.trim() === '' ? null : Number(positionInput.replace(',', '.'));
      return api.assignBookSeries(bookId, sid, Number.isFinite(pos as number) ? (pos as number) : null);
    },
    onSuccess: () => {
      setEditingSeries(false);
      qc.invalidateQueries({ queryKey: ['book', bookId] });
      qc.invalidateQueries({ queryKey: ['books'] });
      qc.invalidateQueries({ queryKey: ['series'] });
    }
  });

  const startEditSeries = () => {
    if (!book) return;
    setSelectedSeriesId(book.seriesId != null ? String(book.seriesId) : '');
    setPositionInput(book.seriesPosition != null ? String(book.seriesPosition) : '');
    setEditingSeries(true);
  };

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
          <div className="mt-2">
            {!editingSeries && book.series && (
              <div className="text-accent-400">
                {book.series}
                {book.seriesPosition != null && ` – Teil ${book.seriesPosition}`}
              </div>
            )}
            {!editingSeries && (
              <button
                onClick={startEditSeries}
                className="text-ink-400 hover:text-ink-100 text-sm underline underline-offset-2"
              >
                {book.series ? 'Serie ändern' : 'Serie zuweisen'}
              </button>
            )}
            {editingSeries && (
              <div className="flex flex-col gap-2 mt-1 max-w-md">
                <select
                  value={selectedSeriesId}
                  onChange={(e) => setSelectedSeriesId(e.target.value)}
                  className="bg-ink-900 ring-1 ring-ink-600 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-accent-500"
                >
                  <option value="">– Keine Serie –</option>
                  {seriesList?.map((s) => (
                    <option key={s.id} value={s.id}>
                      {s.name}
                    </option>
                  ))}
                </select>
                {selectedSeriesId && (
                  <input
                    placeholder="Position in der Serie (z. B. 1, 2, 3.5)"
                    value={positionInput}
                    onChange={(e) => setPositionInput(e.target.value)}
                    inputMode="decimal"
                    className="bg-ink-900 ring-1 ring-ink-600 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-accent-500"
                  />
                )}
                <div className="flex gap-2">
                  <button
                    disabled={assignMutation.isPending}
                    onClick={() => assignMutation.mutate()}
                    className="px-3 py-1.5 rounded bg-accent-500 hover:bg-accent-600 disabled:bg-ink-600 disabled:text-ink-400 text-ink-900 text-sm font-medium"
                  >
                    {assignMutation.isPending ? 'Speichert...' : 'Speichern'}
                  </button>
                  <button
                    onClick={() => setEditingSeries(false)}
                    className="px-3 py-1.5 rounded bg-ink-700 hover:bg-ink-600 text-sm"
                  >
                    Abbrechen
                  </button>
                </div>
                {assignMutation.isError && (
                  <div className="text-red-400 text-sm">
                    {(assignMutation.error as Error).message}
                  </div>
                )}
              </div>
            )}
          </div>

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
