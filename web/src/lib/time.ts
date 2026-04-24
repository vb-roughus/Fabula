// Server returns TimeSpan as "HH:MM:SS" or "HH:MM:SS.fffffff" or "D.HH:MM:SS".
export function parseTimeSpan(ts: string): number {
  if (!ts) return 0;
  let rest = ts;
  let days = 0;
  const dotIdx = rest.indexOf('.');
  const colonIdx = rest.indexOf(':');
  if (dotIdx > -1 && dotIdx < colonIdx) {
    days = parseInt(rest.slice(0, dotIdx), 10);
    rest = rest.slice(dotIdx + 1);
  }
  const [h, m, s] = rest.split(':');
  const seconds = parseFloat(s ?? '0');
  return days * 86400 + parseInt(h, 10) * 3600 + parseInt(m, 10) * 60 + seconds;
}

export function formatTimeSpan(totalSeconds: number): string {
  const s = Math.max(0, Math.floor(totalSeconds));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  const pad = (n: number) => n.toString().padStart(2, '0');
  return h > 0 ? `${h}:${pad(m)}:${pad(sec)}` : `${pad(m)}:${pad(sec)}`;
}

// Convert seconds to .NET-compatible TimeSpan string "HH:MM:SS.fff"
export function toTimeSpanString(totalSeconds: number): string {
  const s = Math.max(0, totalSeconds);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s - h * 3600 - m * 60;
  const pad = (n: number) => n.toString().padStart(2, '0');
  const secStr = sec.toFixed(3).padStart(6, '0');
  return `${pad(h)}:${pad(m)}:${secStr}`;
}

export function formatDurationHours(totalSeconds: number): string {
  const h = Math.floor(totalSeconds / 3600);
  const m = Math.floor((totalSeconds % 3600) / 60);
  if (h === 0) return `${m} min`;
  return m === 0 ? `${h} h` : `${h} h ${m} min`;
}
