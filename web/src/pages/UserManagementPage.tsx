import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { api } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import type { UserDetail } from '../api/types';

export function UserManagementPage() {
  const auth = useAuth();
  const qc = useQueryClient();

  const { data: users, isLoading } = useQuery({
    queryKey: ['users'],
    queryFn: api.listUsers
  });

  const [name, setName] = useState('');
  const [pwd, setPwd] = useState('');
  const [isAdmin, setIsAdmin] = useState(false);

  const createMutation = useMutation({
    mutationFn: () => api.createUser(name.trim(), pwd, isAdmin),
    onSuccess: () => {
      setName('');
      setPwd('');
      setIsAdmin(false);
      qc.invalidateQueries({ queryKey: ['users'] });
    }
  });

  const deleteMutation = useMutation({
    mutationFn: (id: number) => api.deleteUser(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['users'] })
  });

  const adminMutation = useMutation({
    mutationFn: ({ id, isAdmin }: { id: number; isAdmin: boolean }) =>
      api.setUserAdmin(id, isAdmin),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['users'] })
  });

  const resetMutation = useMutation({
    mutationFn: ({ id, password }: { id: number; password: string }) =>
      api.adminResetPassword(id, password)
  });

  if (!auth.user?.isAdmin) {
    return (
      <div className="p-6 max-w-2xl mx-auto w-full text-ink-300">
        Nur Administratoren können Benutzer verwalten.
      </div>
    );
  }

  return (
    <div className="p-6 max-w-3xl mx-auto w-full">
      <h1 className="text-2xl font-semibold mb-6">Benutzerverwaltung</h1>

      <section className="bg-ink-800 ring-1 ring-ink-700 rounded-lg p-4 mb-6">
        <h2 className="text-lg font-semibold mb-3">Neuen Benutzer anlegen</h2>
        <div className="flex flex-col gap-3">
          <input
            placeholder="Benutzername"
            value={name}
            onChange={(e) => setName(e.target.value)}
            className="bg-ink-900 ring-1 ring-ink-600 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-accent-500"
          />
          <input
            type="password"
            placeholder="Passwort (min. 6 Zeichen)"
            value={pwd}
            onChange={(e) => setPwd(e.target.value)}
            className="bg-ink-900 ring-1 ring-ink-600 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-accent-500"
          />
          <label className="flex items-center gap-2 text-sm text-ink-300">
            <input type="checkbox" checked={isAdmin} onChange={(e) => setIsAdmin(e.target.checked)} />
            Administrator
          </label>
          <button
            disabled={createMutation.isPending || !name.trim() || pwd.length < 6}
            onClick={() => createMutation.mutate()}
            className="bg-accent-500 hover:bg-accent-600 disabled:bg-ink-600 disabled:text-ink-400 text-ink-900 font-medium px-4 py-2 rounded-lg self-start"
          >
            {createMutation.isPending ? 'Lege an…' : 'Anlegen'}
          </button>
          {createMutation.isError && (
            <div className="text-red-400 text-sm">{(createMutation.error as Error).message}</div>
          )}
        </div>
      </section>

      <section>
        <h2 className="text-lg font-semibold mb-3">Bestehende Benutzer</h2>
        {isLoading && <div className="text-ink-400">Lade…</div>}
        <ul className="space-y-2">
          {users?.map((u) => (
            <UserRow
              key={u.id}
              user={u}
              isSelf={u.id === auth.user?.id}
              onToggleAdmin={(isAdmin) => adminMutation.mutate({ id: u.id, isAdmin })}
              onDelete={() => {
                if (confirm(`Benutzer "${u.username}" wirklich entfernen?`)) deleteMutation.mutate(u.id);
              }}
              onResetPassword={(password) => resetMutation.mutate({ id: u.id, password })}
              resetSuccess={resetMutation.isSuccess && resetMutation.variables?.id === u.id}
              resetError={resetMutation.isError && resetMutation.variables?.id === u.id
                ? (resetMutation.error as Error).message
                : null}
            />
          ))}
        </ul>
      </section>
    </div>
  );
}

function UserRow({
  user,
  isSelf,
  onToggleAdmin,
  onDelete,
  onResetPassword,
  resetSuccess,
  resetError
}: {
  user: UserDetail;
  isSelf: boolean;
  onToggleAdmin: (isAdmin: boolean) => void;
  onDelete: () => void;
  onResetPassword: (password: string) => void;
  resetSuccess: boolean;
  resetError: string | null;
}) {
  const [pwd, setPwd] = useState('');

  return (
    <li className="bg-ink-800 ring-1 ring-ink-700 rounded-lg p-4">
      <div className="flex items-center gap-3 flex-wrap">
        <div className="flex-1 min-w-0">
          <div className="font-medium">
            {user.username}
            {isSelf && <span className="ml-2 text-ink-400 text-xs">(du)</span>}
          </div>
          <div className="text-ink-400 text-xs">
            {user.isAdmin ? 'Administrator' : 'Benutzer'} · seit {new Date(user.createdAt).toLocaleDateString()}
          </div>
        </div>
        <button
          disabled={isSelf}
          onClick={() => onToggleAdmin(!user.isAdmin)}
          className="px-3 py-1.5 rounded bg-ink-700 hover:bg-ink-600 disabled:opacity-50 text-sm"
        >
          {user.isAdmin ? 'Admin entfernen' : 'Zum Admin machen'}
        </button>
        <button
          disabled={isSelf}
          onClick={onDelete}
          className="px-3 py-1.5 rounded bg-ink-700 hover:bg-red-600 disabled:opacity-50 text-sm"
        >
          Löschen
        </button>
      </div>
      <div className="flex items-center gap-2 mt-3">
        <input
          type="password"
          placeholder="Neues Passwort"
          value={pwd}
          onChange={(e) => setPwd(e.target.value)}
          className="bg-ink-900 ring-1 ring-ink-600 rounded-lg px-3 py-1.5 text-sm focus:outline-none focus:ring-accent-500 flex-1"
        />
        <button
          disabled={pwd.length < 6}
          onClick={() => {
            onResetPassword(pwd);
            setPwd('');
          }}
          className="px-3 py-1.5 rounded bg-ink-700 hover:bg-ink-600 disabled:opacity-50 text-sm"
        >
          Passwort setzen
        </button>
      </div>
      {resetError && <div className="text-red-400 text-sm mt-2">{resetError}</div>}
      {resetSuccess && <div className="text-green-400 text-sm mt-2">Passwort gesetzt.</div>}
    </li>
  );
}
