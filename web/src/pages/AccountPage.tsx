import { useState } from 'react';
import { useAuth } from '../auth/AuthContext';
import { api } from '../api/client';

export function AccountPage() {
  const auth = useAuth();
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState(false);

  const mismatch = confirm.length > 0 && newPassword !== confirm;
  const tooShort = newPassword.length > 0 && newPassword.length < 6;

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setSuccess(false);
    setBusy(true);
    try {
      await api.changeMyPassword(currentPassword, newPassword);
      setCurrentPassword('');
      setNewPassword('');
      setConfirm('');
      setSuccess(true);
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="p-6 max-w-2xl mx-auto w-full">
      <h1 className="text-2xl font-semibold mb-6">Mein Konto</h1>
      <section className="bg-ink-800 ring-1 ring-ink-700 rounded-lg p-4 mb-6">
        <div className="text-ink-300">
          Angemeldet als <span className="font-semibold">{auth.user?.username}</span>
          {auth.user?.isAdmin && <span className="ml-2 text-accent-400 text-xs">Admin</span>}
        </div>
      </section>

      <section className="bg-ink-800 ring-1 ring-ink-700 rounded-lg p-4">
        <h2 className="text-lg font-semibold mb-3">Passwort ändern</h2>
        <form onSubmit={submit} className="flex flex-col gap-3">
          <input
            type="password"
            placeholder="Aktuelles Passwort"
            value={currentPassword}
            onChange={(e) => setCurrentPassword(e.target.value)}
            className="bg-ink-900 ring-1 ring-ink-600 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-accent-500"
          />
          <input
            type="password"
            placeholder="Neues Passwort (min. 6 Zeichen)"
            value={newPassword}
            onChange={(e) => setNewPassword(e.target.value)}
            className="bg-ink-900 ring-1 ring-ink-600 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-accent-500"
          />
          <input
            type="password"
            placeholder="Neues Passwort bestätigen"
            value={confirm}
            onChange={(e) => setConfirm(e.target.value)}
            className="bg-ink-900 ring-1 ring-ink-600 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-accent-500"
          />
          {(error || mismatch || tooShort) && (
            <div className="text-red-400 text-sm">
              {error ?? (mismatch ? 'Passwörter stimmen nicht überein.' : 'Passwort muss mindestens 6 Zeichen haben.')}
            </div>
          )}
          {success && <div className="text-green-400 text-sm">Passwort aktualisiert.</div>}
          <button
            type="submit"
            disabled={busy || !currentPassword || newPassword.length < 6 || mismatch}
            className="bg-accent-500 hover:bg-accent-600 disabled:bg-ink-600 disabled:text-ink-400 text-ink-900 font-medium px-4 py-2 rounded-lg self-start"
          >
            {busy ? 'Speichere…' : 'Speichern'}
          </button>
        </form>
      </section>
    </div>
  );
}
