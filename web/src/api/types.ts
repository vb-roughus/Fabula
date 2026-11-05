export type LibraryType = 'Audiobook' | 'RadioPlay';

export interface LibraryFolder {
  id: number;
  name: string;
  path: string;
  type: LibraryType;
  lastScanAt: string | null;
}

export interface BookSummary {
  id: number;
  title: string;
  subtitle: string | null;
  authors: string[];
  narrators: string[];
  seriesId: number | null;
  series: string | null;
  seriesPosition: number | null;
  duration: string; // "HH:MM:SS" TimeSpan
  coverUrl: string | null;
  type: LibraryType;
  libraryFolderId: number;
  libraryFolderName: string;
  /** ISO-8601 timestamp the scanner first discovered the book. Present on
   *  list/summary responses; omitted from the book detail payload. */
  addedAt?: string;
  progress: ProgressSummary | null;
}

export interface ProgressSummary {
  position: string;
  finished: boolean;
  updatedAt: string;
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
  /** Fields taken over from a catalogue and locked against rescans. Absent on older servers. */
  lockedFields?: LockedField[];
}

export const LIBRARY_TYPE_LABEL: Record<LibraryType, string> = {
  Audiobook: 'Hörbücher',
  RadioPlay: 'Hörspiele'
};

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
  sizeBytes: number;
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

export type ScanState = 'Idle' | 'Running' | 'Completed' | 'Failed' | 'Cancelled';

export interface ScanStatus {
  libraryFolderId: number;
  state: ScanState;
  startedAt: string;
  finishedAt: string | null;
  result: ScanResult | null;
  error: string | null;
}

export interface Bookmark {
  id: number;
  bookId: number;
  position: string; // TimeSpan "HH:MM:SS"
  note: string | null;
  createdAt: string;
}

export interface SeriesSummary {
  id: number;
  name: string;
  description: string | null;
  bookCount: number;
  coverUrl: string | null;
}

export interface SeriesBook {
  id: number;
  title: string;
  authors: string[];
  position: number | null;
  coverUrl: string | null;
}

export interface SeriesDetail {
  id: number;
  name: string;
  description: string | null;
  books: SeriesBook[];
}

// --- auth ----------------------------------------------------------------

export interface AuthUser {
  id: number;
  username: string;
  isAdmin: boolean;
}

export interface AuthResponse {
  token: string;
  user: AuthUser;
}

export interface SetupStatus {
  needsSetup: boolean;
}

export interface UserDetail extends AuthUser {
  createdAt: string;
}

// --- in-app update -------------------------------------------------------

export interface AppUpdateConfig {
  repo: string | null;
  hasToken: boolean;
  checkMinutes: number;
  currentVersionCode: number | null;
  currentVersionName: string | null;
}

export interface AppUpdateCheck {
  configured: boolean;
  ok: boolean;
  message: string;
  versionCode: number | null;
  versionName: string | null;
}

// --- server self-update --------------------------------------------------

export type ServerUpdateState =
  | 'Idle'
  | 'Downloading'
  | 'Verifying'
  | 'Installing'
  | 'Succeeded'
  | 'Failed';

export interface ServerUpdateStatus {
  state: ServerUpdateState;
  fromVersion: string | null;
  toVersion: string | null;
  startedAtUtc: string | null;
  handoffAtUtc: string | null;
  message: string | null;
}

export interface ServerUpdateInfo {
  /** False on a dev run or a non-Windows host: there is no service to restart. */
  supported: boolean;
  unsupportedReason: string | null;
  currentVersion: string | null;
  latestVersion: string | null;
  available: boolean;
  status: ServerUpdateStatus;
}

export interface ServerUpdateCheck {
  configured: boolean;
  ok: boolean;
  message: string;
  latestVersion: string | null;
}

// --- metadata from Audible ----------------------------------------------------

/** One search hit from Audible's catalogue, best fit first. */
export interface MetadataCandidate {
  asin: string;
  title: string | null;
  subtitle: string | null;
  authors: string[];
  narrators: string[];
  /** Display text, e.g. "Harry Potter · Band 3". */
  series: string | null;
  runtimeMinutes: number | null;
  year: number | null;
  coverUrl: string | null;
  /** The runtime fits the book's own length -- the strongest sign of the right edition. */
  runtimeMatches: boolean;
}

/** One field as it is and as the catalogue would have it. For "cover" both are image URLs. */
export interface MetadataField {
  key: string;
  label: string;
  current: string | null;
  proposed: string | null;
  preselected: boolean;
}

export interface MetadataPreview {
  asin: string;
  title: string | null;
  coverUrl: string | null;
  runtimeMinutes: number | null;
  runtimeMatches: boolean;
  fields: MetadataField[];
  /** What the server has to say beside the fields -- why no chapters are offered, say. Absent on older servers. */
  notes?: string[];
}

export interface ApplyMetadataResult {
  taken: string[];
  /** Ticked but not taken: nothing in the catalogue, or the cover would not download. */
  skipped: string[];
}

export interface LockedField {
  key: string;
  label: string;
}

export interface RestoreMetadataResult {
  restored: string[];
  /** False when the locks were released but the files could not be read. */
  filesRead: boolean;
}
