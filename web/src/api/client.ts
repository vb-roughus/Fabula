import type {
  BookDetail,
  BookSummary,
  LibraryFolder,
  PagedResult,
  Progress,
  ScanResult
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

  getProgress: (bookId: number) => request<Progress>(`/api/progress/${bookId}`),

  saveProgress: (bookId: number, position: string, finished: boolean, device: string) =>
    request<Progress>(`/api/progress/${bookId}`, {
      method: 'PUT',
      body: JSON.stringify({ position, finished, device })
    })
};

export const streamUrl = (audioFileId: number) => `/api/stream/${audioFileId}`;
