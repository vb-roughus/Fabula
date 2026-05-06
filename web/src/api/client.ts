import type {
  AuthResponse,
  AuthUser,
  BookDetail,
  BookSummary,
  Bookmark,
  LibraryFolder,
  LibraryType,
  PagedResult,
  Progress,
  ScanStatus,
  SeriesDetail,
  SeriesSummary,
  SetupStatus,
  UserDetail
} from './types';

const TOKEN_KEY = 'fabula.token';
const UNAUTHORIZED_EVENT = 'fabula:unauthorized';

export const tokenStore = {
  get(): string | null {
    return localStorage.getItem(TOKEN_KEY);
  },
  set(token: string | null) {
    if (token) localStorage.setItem(TOKEN_KEY, token);
    else localStorage.removeItem(TOKEN_KEY);
  }
};

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const token = tokenStore.get();
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(init?.headers as Record<string, string> | undefined)
  };
  if (token) headers['Authorization'] = `Bearer ${token}`;

  const res = await fetch(url, { ...init, headers });

  if (res.status === 401) {
    // Don't fire on the auth probes themselves -- that would loop.
    if (!url.startsWith('/api/auth/login') && !url.startsWith('/api/setup')) {
      window.dispatchEvent(new Event(UNAUTHORIZED_EVENT));
    }
  }

  if (!res.ok) {
    const text = await res.text().catch(() => '');
    let detail = text;
    if (text) {
      try {
        const parsed = JSON.parse(text);
        if (parsed && typeof parsed === 'object' && typeof parsed.error === 'string') {
          detail = parsed.error;
        }
      } catch {
        // not JSON; fall back to the raw body
      }
    }
    throw new Error(`${res.status} ${res.statusText}${detail ? `: ${detail}` : ''}`);
  }
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

export const onUnauthorized = (handler: () => void): (() => void) => {
  window.addEventListener(UNAUTHORIZED_EVENT, handler);
  return () => window.removeEventListener(UNAUTHORIZED_EVENT, handler);
};

export const api = {
  // --- auth ----------------------------------------------------------------
  getSetupStatus: () => request<SetupStatus>('/api/setup'),

  setup: (username: string, password: string) =>
    request<AuthResponse>('/api/setup', {
      method: 'POST',
      body: JSON.stringify({ username, password })
    }),

  login: (username: string, password: string) =>
    request<AuthResponse>('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username, password })
    }),

  getMe: () => request<AuthUser>('/api/auth/me'),

  changeMyPassword: (currentPassword: string, newPassword: string) =>
    request<void>('/api/me/password', {
      method: 'POST',
      body: JSON.stringify({ currentPassword, newPassword })
    }),

  listUsers: () => request<UserDetail[]>('/api/users'),

  createUser: (username: string, password: string, isAdmin: boolean) =>
    request<UserDetail>('/api/users', {
      method: 'POST',
      body: JSON.stringify({ username, password, isAdmin })
    }),

  deleteUser: (id: number) => request<void>(`/api/users/${id}`, { method: 'DELETE' }),

  setUserAdmin: (id: number, isAdmin: boolean) =>
    request<void>(`/api/users/${id}/admin`, {
      method: 'POST',
      body: JSON.stringify({ isAdmin })
    }),

  adminResetPassword: (id: number, newPassword: string) =>
    request<void>(`/api/users/${id}/password`, {
      method: 'POST',
      body: JSON.stringify({ newPassword })
    }),

  // --- libraries / scanning -----------------------------------------------
  listLibraries: () => request<LibraryFolder[]>('/api/libraries'),

  createLibrary: (name: string, path: string, type: LibraryType = 'Audiobook') =>
    request<LibraryFolder>('/api/libraries', {
      method: 'POST',
      body: JSON.stringify({ name, path, type })
    }),

  updateLibrary: (id: number, payload: { name?: string; type?: LibraryType }) =>
    request<LibraryFolder>(`/api/libraries/${id}`, {
      method: 'PATCH',
      body: JSON.stringify(payload)
    }),

  scanLibrary: (id: number) =>
    request<ScanStatus>(`/api/libraries/${id}/scan`, { method: 'POST' }),

  getScanStatus: (id: number) =>
    request<ScanStatus>(`/api/libraries/${id}/scan`),

  deleteLibrary: (id: number) =>
    request<void>(`/api/libraries/${id}`, { method: 'DELETE' }),

  // --- catalog ------------------------------------------------------------
  listBooks: (params: { search?: string; page: number; pageSize: number }) => {
    const qs = new URLSearchParams();
    if (params.search) qs.set('search', params.search);
    qs.set('page', String(params.page));
    qs.set('pageSize', String(params.pageSize));
    return request<PagedResult<BookSummary>>(`/api/books?${qs.toString()}`);
  },

  getBook: (id: number) => request<BookDetail>(`/api/books/${id}`),

  assignBookSeries: (bookId: number, seriesId: number | null, seriesPosition: number | null) =>
    request<void>(`/api/books/${bookId}/series`, {
      method: 'PUT',
      body: JSON.stringify({ seriesId, seriesPosition })
    }),

  listSeries: () => request<SeriesSummary[]>('/api/series'),

  getSeries: (id: number) => request<SeriesDetail>(`/api/series/${id}`),

  createSeries: (name: string, description: string | null) =>
    request<SeriesSummary>('/api/series', {
      method: 'POST',
      body: JSON.stringify({ name, description })
    }),

  updateSeries: (id: number, name: string, description: string | null) =>
    request<SeriesSummary>(`/api/series/${id}`, {
      method: 'PUT',
      body: JSON.stringify({ name, description })
    }),

  deleteSeries: (id: number) =>
    request<void>(`/api/series/${id}`, { method: 'DELETE' }),

  reorderSeries: (id: number) =>
    request<{ updated: number }>(`/api/series/${id}/reorder`, { method: 'POST' }),

  getProgress: (bookId: number) => request<Progress>(`/api/progress/${bookId}`),

  saveProgress: (bookId: number, position: string, finished: boolean, device: string) =>
    request<Progress>(`/api/progress/${bookId}`, {
      method: 'PUT',
      body: JSON.stringify({ position, finished, device })
    }),

  setFinished: (bookId: number, finished: boolean, device: string) =>
    request<Progress>(`/api/progress/${bookId}/finished`, {
      method: 'POST',
      body: JSON.stringify({ finished, device })
    }),

  listBookmarks: (bookId: number) =>
    request<Bookmark[]>(`/api/books/${bookId}/bookmarks`),

  createBookmark: (bookId: number, position: string, note: string | null) =>
    request<Bookmark>(`/api/books/${bookId}/bookmarks`, {
      method: 'POST',
      body: JSON.stringify({ position, note })
    }),

  updateBookmark: (id: number, note: string | null) =>
    request<Bookmark>(`/api/bookmarks/${id}`, {
      method: 'PATCH',
      body: JSON.stringify({ note })
    }),

  deleteBookmark: (id: number) =>
    request<void>(`/api/bookmarks/${id}`, { method: 'DELETE' })
};

// `<audio src=...>` cannot send custom headers, so the JWT travels as a
// query parameter. The server only honors ?access_token= for /api/stream.
export const streamUrl = (audioFileId: number) => {
  const t = tokenStore.get();
  const base = `/api/stream/${audioFileId}`;
  return t ? `${base}?access_token=${encodeURIComponent(t)}` : base;
};
