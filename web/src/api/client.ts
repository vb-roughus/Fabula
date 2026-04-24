import type {
  BookDetail,
  BookSummary,
  Bookmark,
  LibraryFolder,
  PagedResult,
  Progress,
  ScanResult,
  SeriesDetail,
  SeriesSummary
} from './types';

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const res = await fetch(url, {
    headers: { 'Content-Type': 'application/json', ...(init?.headers ?? {}) },
    ...init
  });
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    throw new Error(`${res.status} ${res.statusText}${text ? `: ${text}` : ''}`);
  }
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

export const api = {
  listLibraries: () => request<LibraryFolder[]>('/api/libraries'),

  createLibrary: (name: string, path: string) =>
    request<LibraryFolder>('/api/libraries', {
      method: 'POST',
      body: JSON.stringify({ name, path })
    }),

  scanLibrary: (id: number) =>
    request<ScanResult>(`/api/libraries/${id}/scan`, { method: 'POST' }),

  deleteLibrary: (id: number) =>
    request<void>(`/api/libraries/${id}`, { method: 'DELETE' }),

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

  getProgress: (bookId: number) => request<Progress>(`/api/progress/${bookId}`),

  saveProgress: (bookId: number, position: string, finished: boolean, device: string) =>
    request<Progress>(`/api/progress/${bookId}`, {
      method: 'PUT',
      body: JSON.stringify({ position, finished, device })
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

export const streamUrl = (audioFileId: number) => `/api/stream/${audioFileId}`;
