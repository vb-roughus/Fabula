import { Link } from 'react-router-dom';
import { usePlayerContext } from '../hooks/playerContext';
import { formatTimeSpan } from '../lib/time';

const SPEEDS = [0.8, 0.9, 1.0, 1.1, 1.25, 1.5, 1.75, 2.0];

export function PlayerBar() {
  const { state, toggle, skip, seekInBook, setSpeed, jumpToChapter } = usePlayerContext();
  const { book, isPlaying, positionInBook, durationInBook, speed, currentChapter, nextChapter, previousChapter } = state;

  if (!book) return null;

  const pct = durationInBook > 0 ? (positionInBook / durationInBook) * 100 : 0;

  return (
    <div className="sticky bottom-0 bg-ink-800 ring-1 ring-ink-700 text-ink-100 px-4 py-3">
      <div className="max-w-7xl mx-auto flex items-center gap-4 flex-wrap md:flex-nowrap">
        <Link to={`/book/${book.id}`} className="flex items-center gap-3 min-w-0">
          {book.coverUrl ? (
            <img src={book.coverUrl} alt="" className="w-12 h-12 rounded object-cover" />
          ) : (
            <div className="w-12 h-12 rounded bg-ink-700" />
          )}
          <div className="min-w-0">
            <div className="text-sm font-medium truncate">{book.title}</div>
            {currentChapter && (
              <div className="text-xs text-ink-400 truncate">{currentChapter.title}</div>
            )}
          </div>
        </Link>

        <div className="flex items-center gap-1 order-3 md:order-none">
          <button
            onClick={() => previousChapter && jumpToChapter(previousChapter)}
            disabled={!previousChapter}
            title="Vorheriges Kapitel"
            className="p-2 rounded hover:bg-ink-700 disabled:opacity-40"
          >
            ⏮
          </button>
          <button
            onClick={() => skip(-30)}
            title="30 Sek. zurück"
            className="p-2 rounded hover:bg-ink-700"
          >
            ⏪30
          </button>
          <button
            onClick={toggle}
            className="bg-accent-500 hover:bg-accent-600 text-ink-900 w-10 h-10 rounded-full font-medium"
          >
            {isPlaying ? '⏸' : '▶'}
          </button>
          <button
            onClick={() => skip(30)}
            title="30 Sek. vor"
            className="p-2 rounded hover:bg-ink-700"
          >
            30⏩
          </button>
          <button
            onClick={() => nextChapter && jumpToChapter(nextChapter)}
            disabled={!nextChapter}
            title="Nächstes Kapitel"
            className="p-2 rounded hover:bg-ink-700 disabled:opacity-40"
          >
            ⏭
          </button>
        </div>

        <div className="flex-1 flex items-center gap-3 min-w-0 order-4 md:order-none w-full">
          <span className="text-xs tabular-nums text-ink-400 w-14 text-right">
            {formatTimeSpan(positionInBook)}
          </span>
          <input
            type="range"
            min={0}
            max={Math.max(1, durationInBook)}
            step={1}
            value={positionInBook}
            onChange={(e) => seekInBook(Number(e.target.value))}
            className="flex-1 accent-accent-500"
          />
          <span className="text-xs tabular-nums text-ink-400 w-14">
            {formatTimeSpan(durationInBook)}
          </span>
          <div className="hidden md:block w-24 h-1 rounded bg-ink-700 overflow-hidden relative" title={`${pct.toFixed(0)} %`}>
            <div className="absolute inset-y-0 left-0 bg-accent-500" style={{ width: `${pct}%` }} />
          </div>
        </div>

        <select
          value={speed}
          onChange={(e) => setSpeed(Number(e.target.value))}
          className="bg-ink-900 ring-1 ring-ink-600 rounded px-2 py-1 text-xs"
          title="Abspielgeschwindigkeit"
        >
          {SPEEDS.map((s) => (
            <option key={s} value={s}>
              {s.toFixed(2)}×
            </option>
          ))}
        </select>
      </div>
    </div>
  );
}
