import { NavLink, Route, Routes } from 'react-router-dom';
import { LibraryPage } from './pages/LibraryPage';
import { BookPage } from './pages/BookPage';
import { SettingsPage } from './pages/SettingsPage';
import { PlayerBar } from './components/PlayerBar';

export default function App() {
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
              to="/settings"
              className={({ isActive }) => (isActive ? 'text-ink-100' : 'text-ink-400 hover:text-ink-100')}
            >
              Einstellungen
            </NavLink>
          </nav>
        </div>
      </header>

      <main className="flex-1">
        <Routes>
          <Route path="/" element={<LibraryPage />} />
          <Route path="/book/:id" element={<BookPage />} />
          <Route path="/settings" element={<SettingsPage />} />
        </Routes>
      </main>

      <PlayerBar />
    </div>
  );
}
