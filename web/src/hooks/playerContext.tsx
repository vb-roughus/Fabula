import { createContext, useContext } from 'react';
import type { ReactNode } from 'react';
import { usePlayer } from './usePlayer';

type PlayerApi = ReturnType<typeof usePlayer>;

const PlayerContext = createContext<PlayerApi | null>(null);

export function PlayerProvider({ children }: { children: ReactNode }) {
  const player = usePlayer();
  return <PlayerContext.Provider value={player}>{children}</PlayerContext.Provider>;
}

export function usePlayerContext(): PlayerApi {
  const ctx = useContext(PlayerContext);
  if (!ctx) throw new Error('usePlayerContext must be used within PlayerProvider');
  return ctx;
}
