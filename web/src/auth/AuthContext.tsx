import { createContext, useContext, useEffect, useState, useCallback, type ReactNode } from 'react';
import { api, onUnauthorized, tokenStore } from '../api/client';
import type { AuthUser } from '../api/types';

interface AuthContextValue {
  user: AuthUser | null;
  loading: boolean;
  needsSetup: boolean;
  login: (username: string, password: string) => Promise<void>;
  setup: (username: string, password: string) => Promise<void>;
  logout: () => void;
  refreshMe: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(null);
  const [loading, setLoading] = useState(true);
  const [needsSetup, setNeedsSetup] = useState(false);

  const bootstrap = useCallback(async () => {
    setLoading(true);
    try {
      const status = await api.getSetupStatus();
      if (status.needsSetup) {
        setNeedsSetup(true);
        setUser(null);
        return;
      }
      setNeedsSetup(false);
      const token = tokenStore.get();
      if (!token) {
        setUser(null);
        return;
      }
      try {
        setUser(await api.getMe());
      } catch {
        // Stale token / server-side revoke. Drop it and fall through to
        // login.
        tokenStore.set(null);
        setUser(null);
      }
    } catch {
      // Server unreachable -- leave the existing state alone, the UI will
      // surface the failure on the next call.
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    bootstrap();
  }, [bootstrap]);

  useEffect(() => {
    return onUnauthorized(() => {
      tokenStore.set(null);
      setUser(null);
    });
  }, []);

  const login = useCallback(async (username: string, password: string) => {
    const res = await api.login(username, password);
    tokenStore.set(res.token);
    setUser(res.user);
    setNeedsSetup(false);
  }, []);

  const setup = useCallback(async (username: string, password: string) => {
    const res = await api.setup(username, password);
    tokenStore.set(res.token);
    setUser(res.user);
    setNeedsSetup(false);
  }, []);

  const logout = useCallback(() => {
    tokenStore.set(null);
    setUser(null);
  }, []);

  const refreshMe = useCallback(async () => {
    setUser(await api.getMe());
  }, []);

  return (
    <AuthContext.Provider value={{ user, loading, needsSetup, login, setup, logout, refreshMe }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside <AuthProvider>');
  return ctx;
}
