import { Link } from 'react-router-dom';
import type { BookSummary } from '../api/types';
import { formatDurationHours, parseTimeSpan } from '../lib/time';

export function BookCard({ book }: { book: BookSummary }) {
  const durationSec = parseTimeSpan(book.duration);
  const positionSec = book.progress ? parseTimeSpan(book.progress.position) : 0;
  const finished = book.progress?.finished ?? false;
  const pct = durationSec > 0 ? Math.min(100, Math.max(0, (positionSec / durationSec) * 100)) : 0;
  const started = positionSec > 1;

  return (
    <Link
      to={`/book/${book.id}`}
      className="group flex flex-col rounded-xl bg-ink-800 hover:bg-ink-700 transition-colors overflow-hidden ring-1 ring-ink-700 hover:ring-accent-500"
    >
      <div className="relative aspect-square bg-ink-700 overflow-hidden flex items-center justify-center">
        {book.coverUrl ? (
          <img
            src={book.coverUrl}
            alt={book.title}
            className="w-full h-full object-cover group-hover:scale-105 transition-transform"
          />
        ) : (
          <div className="text-ink-400 text-xs text-center px-4 font-serif italic">
            {book.title}
          </div>
        )}

        {finished && (
          <div className="absolute top-2 right-2 bg-accent-500 text-ink-900 text-[10px] font-semibold uppercase tracking-wide px-1.5 py-0.5 rounded">
            Fertig
          </div>
        )}

        {started && !finished && (
          <div className="absolute inset-x-0 bottom-0 h-1 bg-ink-900/60">
            <div className="h-full bg-accent-500" style={{ width: `${pct}%` }} />
          </div>
        )}
      </div>

      <div className="p-3">
        <div className="text-ink-100 text-sm font-medium leading-tight line-clamp-2">
          {book.title}
        </div>
        {book.authors.length > 0 && (
          <div className="text-ink-400 text-xs mt-1 line-clamp-1">{book.authors.join(', ')}</div>
        )}
        {book.series && (
          <div className="text-accent-400 text-xs mt-1">
            {book.series}
            {book.seriesPosition != null && ` #${book.seriesPosition}`}
          </div>
        )}
        <div className="text-ink-400 text-xs mt-2 flex items-center gap-2">
          <span>{formatDurationHours(durationSec)}</span>
          {started && !finished && (
            <span className="text-accent-400">· {Math.round(pct)}%</span>
          )}
        </div>
      </div>
    </Link>
  );
}
