import { NavLink, Route, Routes } from 'react-router-dom';
import { LibraryPage } from './pages/LibraryPage';
import { BookPage } from './pages/BookPage';
import { SeriesPage } from './pages/SeriesPage';
import { SeriesDetailPage } from './pages/SeriesDetailPage';
import { SettingsPage } from './pages/SettingsPage';
import { LoginPage } from './pages/LoginPage';
import { SetupPage } from './pages/SetupPage';
import { AccountPage } from './pages/AccountPage';
import { UserManagementPage } from './pages/UserManagementPage';
import { PlayerBar } from './components/PlayerBar';
import { useAuth } from './auth/AuthContext';

export default function App() {
  const auth = useAuth();

  if (auth.loading) {
    return (
      <div className="min-h-screen flex items-center justify-center text-ink-400">Lade…</div>
    );
  }

  if (auth.needsSetup) return <SetupPage />;
  if (!auth.user) return <LoginPage />;

  return (
    <div className="min-h-full flex flex-col">
      <header className="bg-ink-800 border-b border-ink-700">
        <div className="max-w-7xl mx-auto px-4 flex items-center gap-6 h-14">
          <NavLink to="/" className="text-accent-400 font-serif text-xl font-semibold">
            Fabula
          </NavLink>
          <nav className="flex gap-4 text-sm">
            <NavLink
              to="/"
              end
              className={({ isActive }) => (isActive ? 'text-ink-100' : 'text-ink-400 hover:text-ink-100')}
            >
              Bibliothek
            </NavLink>
            <NavLink
              to="/series"
              className={({ isActive }) => (isActive ? 'text-ink-100' : 'text-ink-400 hover:text-ink-100')}
            >
              Serien
            </NavLink>
            <NavLink
              to="/settings"
              className={({ isActive }) => (isActive ? 'text-ink-100' : 'text-ink-400 hover:text-ink-100')}
            >
              Einstellungen
            </NavLink>
          </nav>
          <div className="flex-1" />
          <nav className="flex gap-3 items-center text-sm">
            {auth.user.isAdmin && (
              <NavLink
                to="/users"
                className={({ isActive }) => (isActive ? 'text-ink-100' : 'text-ink-400 hover:text-ink-100')}
              >
                Benutzer
              </NavLink>
            )}
            <NavLink
              to="/account"
              className={({ isActive }) => (isActive ? 'text-ink-100' : 'text-ink-400 hover:text-ink-100')}
            >
              {auth.user.username}
            </NavLink>
            <button
              onClick={() => auth.logout()}
              className="text-ink-400 hover:text-ink-100"
            >
              Abmelden
            </button>
          </nav>
        </div>
      </header>

      <main className="flex-1">
        <Routes>
          <Route path="/" element={<LibraryPage />} />
          <Route path="/book/:id" element={<BookPage />} />
          <Route path="/series" element={<SeriesPage />} />
          <Route path="/series/:id" element={<SeriesDetailPage />} />
          <Route path="/settings" element={<SettingsPage />} />
          <Route path="/account" element={<AccountPage />} />
          {auth.user.isAdmin && <Route path="/users" element={<UserManagementPage />} />}
        </Routes>
      </main>

      <PlayerBar />
    </div>
  );
}
