import { useMutation } from '@tanstack/react-query';
import { useState } from 'react';
import { api } from '../api/client';
import type { BookDetail, MetadataCandidate, MetadataField, MetadataPreview } from '../api/types';
import { formatDurationHours, parseTimeSpan } from '../lib/time';

/**
 * Looking a book up in Audible's catalogue and taking over chosen fields.
 * Admin-only; the book page offers it to admins alone.
 *
 * Two steps in one dialog: the search with its results, then -- for the
 * result picked -- a field-by-field comparison. Nothing is written until the
 * button on the second step.
 */
export function MetadataSearchDialog({
  book,
  onClose,
  onApplied
}: {
  book: BookDetail;
  onClose: () => void;
  onApplied: () => void;
}) {
  // Start from what the book already says. An ASIN from the tags finds exactly
  // the right record.
  const [title, setTitle] = useState(book.title);
  const [author, setAuthor] = useState(book.authors[0] ?? '');
  const [asin, setAsin] = useState(book.asin ?? '');
  const [preview, setPreview] = useState<MetadataPreview | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [notTaken, setNotTaken] = useState<string[] | null>(null);

  const bookSeconds = parseTimeSpan(book.duration);

  const search = useMutation({
    mutationFn: () => api.searchMetadata(book.id, { title, author, asin })
  });

  const open = useMutation({
    mutationFn: (candidate: MetadataCandidate) => api.previewMetadata(book.id, candidate.asin),
    onSuccess: (loaded) => {
      // Empty fields start ticked; overwriting what the book has is the
      // admin's call.
      setSelected(new Set(loaded.fields.filter((f) => f.preselected).map((f) => f.key)));
      setPreview(loaded);
    }
  });

  const apply = useMutation({
    mutationFn: (current: MetadataPreview) => api.applyMetadata(book.id, current.asin, [...selected]),
    onSuccess: (result, current) => {
      onApplied();
      if (result.skipped.length === 0) {
        onClose();
      } else {
        setNotTaken(result.skipped.map((key) => current.fields.find((f) => f.key === key)?.label ?? key));
      }
    }
  });

  const toggle = (key: string) =>
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });

  const canSearch = !search.isPending && (title.trim() || author.trim() || asin.trim());
  const errorText = (search.error ?? open.error ?? apply.error) as Error | null;

  return (
    <div
      className="fixed inset-0 z-50 bg-black/60 flex items-start justify-center overflow-y-auto p-4"
      onClick={onClose}
    >
      <div
        className="bg-ink-900 ring-1 ring-ink-700 rounded-xl w-full max-w-2xl p-5 mt-10 mb-10"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between mb-4">
          <h2 className="text-lg font-semibold text-ink-100">
            {preview ? 'Felder übernehmen' : 'Metadaten suchen'}
          </h2>
          <button onClick={onClose} className="text-ink-400 hover:text-ink-100 text-sm">
            Schliessen
          </button>
        </div>

        {notTaken && (
          <div className="mb-4 rounded-lg bg-ink-800 ring-1 ring-ink-600 p-3 text-sm text-ink-200">
            Übernommen. Nicht übernommen: {notTaken.join(', ')} – Audible hatte dafür nichts, das
            Cover liess sich nicht laden, oder ein Band sollte ohne Serie übernommen werden.
            <div className="mt-2">
              <button onClick={onClose} className="text-accent-400 hover:underline">
                Schliessen
              </button>
            </div>
          </div>
        )}

        {!preview && (
          <form
            className="flex flex-col gap-2"
            onSubmit={(e) => {
              e.preventDefault();
              if (canSearch) search.mutate();
            }}
          >
            <Input label="Titel" value={title} onChange={setTitle} />
            <Input label="Autor" value={author} onChange={setAuthor} />
            <Input label="ASIN (optional)" value={asin} onChange={setAsin} />
            <p className="text-xs text-ink-400">
              Mit einer ASIN wird genau dieser Audible-Eintrag geladen, sonst wird nach Titel und Autor
              gesucht. Treffer mit passender Laufzeit stehen oben.
            </p>
            <div>
              <button
                type="submit"
                disabled={!canSearch}
                className="px-3 py-1.5 rounded-lg bg-accent-500 text-ink-900 text-sm font-medium disabled:opacity-50"
              >
                {search.isPending ? 'Suche…' : 'Bei Audible suchen'}
              </button>
            </div>
          </form>
        )}

        {errorText && <div className="text-red-400 text-sm mt-3">{errorText.message}</div>}

        {!preview && search.data && (
          <div className="mt-4 flex flex-col gap-2">
            {search.data.length === 0 && (
              <div className="text-ink-400 text-sm">
                Nichts gefunden. Ein kürzerer Titel oder ohne Autor findet oft mehr.
              </div>
            )}
            {search.data.map((candidate) => (
              <button
                key={candidate.asin}
                onClick={() => !open.isPending && open.mutate(candidate)}
                className="flex gap-3 text-left rounded-lg bg-ink-800 ring-1 ring-ink-700 hover:ring-ink-500 p-2"
              >
                <Cover url={candidate.coverUrl} size="w-16 h-16" />
                <div className="min-w-0 flex-1 text-sm">
                  <div className="font-medium text-ink-100">{candidate.title ?? candidate.asin}</div>
                  {candidate.subtitle && <div className="text-ink-300 truncate">{candidate.subtitle}</div>}
                  {candidate.authors.length > 0 && <div className="text-ink-200">{candidate.authors.join(', ')}</div>}
                  {candidate.narrators.length > 0 && (
                    <div className="text-ink-400 truncate">Gelesen von {candidate.narrators.join(', ')}</div>
                  )}
                  {candidate.series && <div className="text-accent-400">{candidate.series}</div>}
                  <div className="flex items-center gap-2 text-ink-400 text-xs mt-0.5">
                    {[
                      candidate.runtimeMinutes != null ? formatDurationHours(candidate.runtimeMinutes * 60) : null,
                      candidate.year
                    ]
                      .filter((part) => part != null)
                      .join(' · ')}
                    {candidate.runtimeMatches && <RuntimeBadge />}
                  </div>
                </div>
                {open.isPending && open.variables?.asin === candidate.asin && (
                  <span className="text-ink-400 text-xs self-center">Lade…</span>
                )}
              </button>
            ))}
          </div>
        )}

        {preview && (
          <div className="flex flex-col gap-3">
            <div className="flex items-center gap-3">
              <Cover url={preview.coverUrl} size="w-20 h-20" />
              <div className="text-sm">
                <div className="font-medium text-ink-100 text-base">{preview.title ?? preview.asin}</div>
                {preview.runtimeMinutes != null && (
                  <div className="text-ink-400">
                    Audible: {formatDurationHours(preview.runtimeMinutes * 60)} · Dieses Buch:{' '}
                    {formatDurationHours(bookSeconds)}
                  </div>
                )}
                {preview.runtimeMatches ? (
                  <RuntimeBadge />
                ) : (
                  preview.runtimeMinutes != null && (
                    <div className="text-red-400 text-xs">Laufzeit weicht ab – evtl. andere Ausgabe</div>
                  )
                )}
              </div>
            </div>

            <p className="text-xs text-ink-400">
              Leere Felder sind angehakt. Was das Buch schon hat, wird nur ersetzt, wenn du es anhakst.
              Übernommene Felder bleiben auch bei einem neuen Scan erhalten.
            </p>

            {preview.fields.length === 0 && (
              <div className="text-ink-400 text-sm">Dieser Eintrag bringt nichts Neues.</div>
            )}

            {preview.fields.length > 0 && (
              // At the top, with the button beside it: taking everything is
              // two clicks, without scrolling past a long description.
              <SelectAllRow
                allKeys={preview.fields.map((f) => f.key)}
                selected={selected}
                onChange={setSelected}
                onApply={() => apply.mutate(preview)}
                disabled={apply.isPending || notTaken != null}
              />
            )}

            {preview.fields.map((field) => (
              <FieldRow
                key={field.key}
                field={field}
                checked={selected.has(field.key)}
                onToggle={() => toggle(field.key)}
              />
            ))}

            <div className="flex gap-2 mt-1">
              <button
                onClick={() => apply.mutate(preview)}
                disabled={selected.size === 0 || apply.isPending || notTaken != null}
                className="px-3 py-1.5 rounded-lg bg-accent-500 text-ink-900 text-sm font-medium disabled:opacity-50"
              >
                {apply.isPending
                  ? 'Wird übernommen…'
                  : selected.size === 1
                    ? '1 Feld übernehmen'
                    : `${selected.size} Felder übernehmen`}
              </button>
              <button
                onClick={() => setPreview(null)}
                className="px-3 py-1.5 rounded-lg text-ink-300 hover:text-ink-100 text-sm"
              >
                Zurück zu den Treffern
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

/**
 * A search field with a bin that empties it in one click. The fields arrive
 * filled from the book, and a filled ASIN decides the whole search -- so
 * clearing one has to be easy. The bin only shows while there is something
 * to clear.
 */
function Input({ label, value, onChange }: { label: string; value: string; onChange: (v: string) => void }) {
  return (
    <label className="flex flex-col gap-1 text-sm text-ink-300">
      {label}
      <div className="relative">
        <input
          value={value}
          onChange={(e) => onChange(e.target.value)}
          className="w-full bg-ink-800 ring-1 ring-ink-600 rounded-lg px-3 py-2 pr-9 text-ink-100 focus:outline-none focus:ring-accent-500"
        />
        {value && (
          <button
            type="button"
            onClick={() => onChange('')}
            title={`${label} leeren`}
            aria-label={`${label} leeren`}
            className="absolute right-2 top-1/2 -translate-y-1/2 text-ink-400 hover:text-ink-100"
          >
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
              strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <path d="M3 6h18" />
              <path d="M8 6V4h8v2" />
              <path d="M19 6l-1 14H6L5 6" />
              <path d="M10 11v6M14 11v6" />
            </svg>
          </button>
        )}
      </div>
    </label>
  );
}

/** "Alle übernehmen": ticks every field, or none once all are ticked. */
function SelectAllRow({
  allKeys,
  selected,
  onChange,
  onApply,
  disabled
}: {
  allKeys: string[];
  selected: Set<string>;
  onChange: (next: Set<string>) => void;
  onApply: () => void;
  disabled: boolean;
}) {
  const all = allKeys.every((key) => selected.has(key));
  const some = selected.size > 0 && !all;
  return (
    <div className="flex items-center justify-between gap-3 rounded-lg bg-ink-800 ring-1 ring-ink-700 p-3">
      <label className="flex items-center gap-3 text-sm text-ink-100 cursor-pointer">
        <input
          type="checkbox"
          checked={all}
          ref={(el) => {
            if (el) el.indeterminate = some;
          }}
          onChange={() => onChange(all ? new Set() : new Set(allKeys))}
          className="accent-accent-500"
        />
        Alle übernehmen
      </label>
      <button
        onClick={onApply}
        disabled={disabled || selected.size === 0}
        className="px-3 py-1.5 rounded-lg bg-accent-500 text-ink-900 text-sm font-medium disabled:opacity-50"
      >
        Übernehmen
      </button>
    </div>
  );
}

function RuntimeBadge() {
  return (
    <span className="inline-block rounded bg-accent-500 text-ink-900 text-[11px] font-medium px-1.5 py-0.5">
      Laufzeit passt
    </span>
  );
}

function Cover({ url, size }: { url: string | null; size: string }) {
  return (
    <div className={`${size} shrink-0 rounded-md overflow-hidden bg-ink-700`}>
      {url && <img src={url} alt="" className="w-full h-full object-cover" />}
    </div>
  );
}

function FieldRow({
  field,
  checked,
  onToggle
}: {
  field: MetadataField;
  checked: boolean;
  onToggle: () => void;
}) {
  return (
    <label className="flex gap-3 rounded-lg bg-ink-800 ring-1 ring-ink-700 p-3 cursor-pointer">
      <input type="checkbox" checked={checked} onChange={onToggle} className="mt-1 accent-accent-500" />
      <div className="min-w-0 flex-1 text-sm">
        <div className="text-accent-400 font-medium">{field.label}</div>
        {field.key === 'cover' ? (
          <div className="flex gap-4 mt-1">
            <div className="text-center text-xs text-ink-400">
              <Cover url={field.current} size="w-20 h-20" />
              Bisher
            </div>
            <div className="text-center text-xs text-ink-400">
              <Cover url={field.proposed} size="w-20 h-20" />
              Neu
            </div>
          </div>
        ) : (
          <>
            <div className={`text-ink-100 whitespace-pre-line ${field.key === 'description' ? 'line-clamp-6' : 'line-clamp-3'}`}>
              {field.proposed}
            </div>
            <div className="text-ink-400 text-xs mt-0.5 line-clamp-2">
              {field.current ? `Bisher: ${field.current}` : 'Bisher leer'}
            </div>
          </>
        )}
      </div>
    </label>
  );
}
