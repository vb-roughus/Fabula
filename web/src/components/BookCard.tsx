import { Link } from 'react-router-dom';
import type { BookSummary } from '../api/types';
import { formatDurationHours } from '../lib/time';
import { parseTimeSpan } from '../lib/time';

export function BookCard({ book }: { book: BookSummary }) {
  return (
    <Link
      to={`/book/${book.id}`}
      className="group flex flex-col rounded-xl bg-ink-800 hover:bg-ink-700 transition-colors overflow-hidden ring-1 ring-ink-700 hover:ring-accent-500"
    >
      <div className="aspect-square bg-ink-700 overflow-hidden flex items-center justify-center">
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
        <div className="text-ink-400 text-xs mt-2">{formatDurationHours(parseTimeSpan(book.duration))}</div>
      </div>
    </Link>
  );
}
