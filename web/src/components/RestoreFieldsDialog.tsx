import { useMutation } from '@tanstack/react-query';
import { useState } from 'react';
import { api } from '../api/client';
import type { LockedField } from '../api/types';

/**
 * Choosing which taken-over fields to give back to the files. All ticked to
 * start with: this is the way back, and most of the time the whole way is
 * wanted. Only the chosen fields change; everything else on the book stays.
 */
export function RestoreFieldsDialog({
  bookId,
  locked,
  onClose,
  onRestored
}: {
  bookId: number;
  locked: LockedField[];
  onClose: () => void;
  onRestored: () => void;
}) {
  const [chosen, setChosen] = useState<Set<string>>(new Set(locked.map((f) => f.key)));

  const restore = useMutation({
    mutationFn: () => api.restoreMetadata(bookId, [...chosen]),
    onSuccess: () => {
      onRestored();
      onClose();
    }
  });

  const toggle = (key: string) =>
    setChosen((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });

  return (
    <div className="fixed inset-0 z-50 bg-black/60 flex items-start justify-center p-4" onClick={onClose}>
      <div
        className="bg-ink-900 ring-1 ring-ink-700 rounded-xl w-full max-w-md p-5 mt-20"
        onClick={(e) => e.stopPropagation()}
      >
        <h2 className="text-lg font-semibold text-ink-100 mb-2">Übernommene Felder freigeben</h2>
        <p className="text-xs text-ink-400 mb-3">
          Die gewählten Felder bekommen wieder den Wert aus den Dateien und werden bei einem Scan wieder
          aktualisiert. Alles andere am Buch bleibt, wie es ist.
        </p>
        <div className="flex flex-col gap-1 mb-4">
          {locked.map((field) => (
            <label key={field.key} className="flex items-center gap-3 text-sm text-ink-100 cursor-pointer">
              <input
                type="checkbox"
                checked={chosen.has(field.key)}
                onChange={() => toggle(field.key)}
                className="accent-accent-500"
              />
              {field.label}
            </label>
          ))}
        </div>
        {restore.error && <div className="text-red-400 text-sm mb-3">{(restore.error as Error).message}</div>}
        <div className="flex gap-2">
          <button
            onClick={() => restore.mutate()}
            disabled={chosen.size === 0 || restore.isPending}
            className="px-3 py-1.5 rounded-lg bg-accent-500 text-ink-900 text-sm font-medium disabled:opacity-50"
          >
            {restore.isPending ? 'Wird freigegeben…' : 'Freigeben'}
          </button>
          <button onClick={onClose} className="px-3 py-1.5 rounded-lg text-ink-300 hover:text-ink-100 text-sm">
            Abbrechen
          </button>
        </div>
      </div>
    </div>
  );
}
