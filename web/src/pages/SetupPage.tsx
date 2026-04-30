import { useState } from 'react';
import { useAuth } from '../auth/AuthContext';

export function SetupPage() {
  const auth = useAuth();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const validationError =
    password.length > 0 && password.length < 6
      ? 'Passwort muss mindestens 6 Zeichen haben.'
      : password !== confirm && confirm.length > 0
        ? 'Passwörter stimmen nicht überein.'
        : null;

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      await auth.setup(username.trim(), password);
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="min-h-screen flex items-center justify-center px-4">
      <form
        onSubmit={submit}
        className="w-full max-w-sm bg-ink-800 ring-1 ring-ink-700 rounded-xl p-6 flex flex-col gap-4"
      >
        <h1 className="text-accent-400 font-serif text-2xl font-semibold">Fabula einrichten</h1>
        <p className="text-ink-400 text-sm">
          Lege das erste Administrator-Konto an. Du kannst später weitere Benutzer hinzufügen.
        </p>
        <input
          autoFocus
          autoComplete="username"
          placeholder="Benutzername"
          value={username}
          onChange={(e) => setUsername(e.target.value)}
          className="bg-ink-900 ring-1 ring-ink-600 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-accent-500"
        />
        <input
          type="password"
          autoComplete="new-password"
          placeholder="Passwort (min. 6 Zeichen)"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          className="bg-ink-900 ring-1 ring-ink-600 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-accent-500"
        />
        <input
          type="password"
          autoComplete="new-password"
          placeholder="Passwort bestätigen"
          value={confirm}
          onChange={(e) => setConfirm(e.target.value)}
          className="bg-ink-900 ring-1 ring-ink-600 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-accent-500"
        />
        {(validationError || error) && (
          <div className="text-red-400 text-sm">{validationError ?? error}</div>
        )}
        <button
          type="submit"
          disabled={busy || !username.trim() || !!validationError || password.length < 6 || password !== confirm}
          className="bg-accent-500 hover:bg-accent-600 disabled:bg-ink-600 disabled:text-ink-400 text-ink-900 font-medium px-4 py-2 rounded-lg"
        >
          {busy ? 'Lege an…' : 'Konto anlegen'}
        </button>
      </form>
    </div>
  );
}
