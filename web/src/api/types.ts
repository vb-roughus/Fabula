export interface LibraryFolder {
  id: number;
  name: string;
  path: string;
  lastScanAt: string | null;
}

export interface BookSummary {
  id: number;
  title: string;
  subtitle: string | null;
  authors: string[];
  narrators: string[];
  series: string | null;
  seriesPosition: number | null;
  duration: string; // "HH:MM:SS" TimeSpan
  coverUrl: string | null;
}

export interface BookDetail extends BookSummary {
  description: string | null;
  language: string | null;
  publisher: string | null;
  publishYear: number | null;
  isbn: string | null;
  asin: string | null;
  chapters: Chapter[];
  files: AudioFileInfo[];
}

export interface Chapter {
  index: number;
  title: string;
  start: string;
  end: string;
}

export interface AudioFileInfo {
  id: number;
  trackIndex: number;
  duration: string;
  offsetInBook: string;
}

export interface PagedResult<T> {
  items: T[];
  total: number;
  page: number;
  pageSize: number;
}

export interface Progress {
  bookId: number;
  position: string;
  finished: boolean;
  updatedAt: string | null;
  device: string | null;
}

export interface ScanResult {
  booksAdded: number;
  booksUpdated: number;
  booksRemoved: number;
  filesScanned: number;
}

export interface Bookmark {
  id: number;
  bookId: number;
  position: string; // TimeSpan "HH:MM:SS"
  note: string | null;
  createdAt: string;
}
