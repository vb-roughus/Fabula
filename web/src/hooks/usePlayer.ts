import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { BookDetail, Chapter } from '../api/types';
import { api, streamUrl } from '../api/client';
import { parseTimeSpan, toTimeSpanString } from '../lib/time';

// Identifier for this browser/device, stable for the session.
function getDeviceId(): string {
  let id = localStorage.getItem('fabula.deviceId');
  if (!id) {
    id = `web-${Math.random().toString(36).slice(2, 10)}`;
    localStorage.setItem('fabula.deviceId', id);
  }
  return id;
}

export interface PlayerState {
  book: BookDetail | null;
  isPlaying: boolean;
  positionInBook: number; // seconds from start of book
  durationInBook: number;
  speed: number;
  currentChapter: Chapter | null;
  nextChapter: Chapter | null;
  previousChapter: Chapter | null;
}

interface FileRange {
  id: number;
  start: number;
  end: number;
}

export function usePlayer() {
  // Lazy-initialised audio element. A useState lazy init gives us a stable
  // reference that exists before any effect runs, so event listeners can be
  // attached immediately (a ref would still be null on the first effect run).
  const [audio] = useState<HTMLAudioElement>(() => {
    const el = new Audio();
    el.preload = 'metadata';
    return el;
  });
  const [book, setBook] = useState<BookDetail | null>(null);
  const [isPlaying, setIsPlaying] = useState(false);
  const [positionInBook, setPositionInBook] = useState(0);
  const [speed, setSpeedState] = useState(1);

  const fileRanges: FileRange[] = useMemo(() => {
    if (!book) return [];
    return book.files.map((f) => {
      const start = parseTimeSpan(f.offsetInBook);
      return { id: f.id, start, end: start + parseTimeSpan(f.duration) };
    });
  }, [book]);

  const durationInBook = useMemo(() => parseTimeSpan(book?.duration ?? '00:00:00'), [book]);

  const currentFile = useMemo(() => {
    if (!fileRanges.length) return null;
    return fileRanges.find((r) => positionInBook < r.end) ?? fileRanges[fileRanges.length - 1];
  }, [fileRanges, positionInBook]);

  const chaptersWithSeconds = useMemo(() => {
    if (!book) return [] as (Chapter & { startSec: number; endSec: number })[];
    return book.chapters.map((c) => ({
      ...c,
      startSec: parseTimeSpan(c.start),
      endSec: parseTimeSpan(c.end)
    }));
  }, [book]);

  const currentChapter = useMemo(() => {
    return chaptersWithSeconds.find((c) => positionInBook >= c.startSec && positionInBook < c.endSec) ?? null;
  }, [chaptersWithSeconds, positionInBook]);

  const nextChapter = useMemo(() => {
    if (!currentChapter) return chaptersWithSeconds[0] ?? null;
    return chaptersWithSeconds[currentChapter.index + 1] ?? null;
  }, [currentChapter, chaptersWithSeconds]);

  const previousChapter = useMemo(() => {
    if (!currentChapter) return null;
    return chaptersWithSeconds[currentChapter.index - 1] ?? null;
  }, [currentChapter, chaptersWithSeconds]);

  // Load a book and its saved progress.
  const loadBook = useCallback(async (detail: BookDetail) => {
    setBook(detail);
    setIsPlaying(false);
    try {
      const progress = await api.getProgress(detail.id);
      setPositionInBook(parseTimeSpan(progress.position));
    } catch {
      setPositionInBook(0);
    }
  }, []);

  // Seek to a book-wide position by loading the matching file and setting its currentTime.
  const seekInBook = useCallback(
    (absSeconds: number) => {
      if (!book || !fileRanges.length) return;
      const clamped = Math.max(0, Math.min(absSeconds, durationInBook));
      const range = fileRanges.find((r) => clamped < r.end) ?? fileRanges[fileRanges.length - 1];
      const desiredSrc = streamUrl(range.id);
      const localSeek = clamped - range.start;
      if (!audio.src.endsWith(desiredSrc)) {
        audio.src = desiredSrc;
        const onMeta = () => {
          audio.currentTime = Math.max(0, localSeek);
          audio.removeEventListener('loadedmetadata', onMeta);
        };
        audio.addEventListener('loadedmetadata', onMeta);
      } else {
        audio.currentTime = Math.max(0, localSeek);
      }
      setPositionInBook(clamped);
    },
    [audio, book, fileRanges, durationInBook]
  );

  const play = useCallback(() => {
    if (!book) return;
    if (!audio.src && currentFile) {
      audio.src = streamUrl(currentFile.id);
      const onMeta = () => {
        audio.currentTime = Math.max(0, positionInBook - currentFile.start);
        audio.playbackRate = speed;
        audio.play().catch(() => {});
        audio.removeEventListener('loadedmetadata', onMeta);
      };
      audio.addEventListener('loadedmetadata', onMeta);
    } else {
      audio.playbackRate = speed;
      audio.play().catch(() => {});
    }
  }, [audio, book, currentFile, positionInBook, speed]);

  const pause = useCallback(() => {
    audio.pause();
  }, [audio]);

  const toggle = useCallback(() => {
    if (isPlaying) pause();
    else play();
  }, [isPlaying, pause, play]);

  const setSpeed = useCallback(
    (value: number) => {
      setSpeedState(value);
      audio.playbackRate = value;
    },
    [audio]
  );

  const skip = useCallback(
    (seconds: number) => seekInBook(positionInBook + seconds),
    [positionInBook, seekInBook]
  );

  const jumpToChapter = useCallback(
    (chapter: Chapter) => seekInBook(parseTimeSpan(chapter.start)),
    [seekInBook]
  );

  // Audio element event wiring and auto-advance between files.
  useEffect(() => {
    if (!currentFile) return;

    const onPlay = () => setIsPlaying(true);
    const onPause = () => setIsPlaying(false);
    const onTimeUpdate = () => {
      setPositionInBook(currentFile.start + audio.currentTime);
    };
    const onEnded = () => {
      const idx = fileRanges.findIndex((r) => r.id === currentFile.id);
      const next = fileRanges[idx + 1];
      if (next) {
        audio.src = streamUrl(next.id);
        audio.playbackRate = speed;
        audio.play().catch(() => {});
      } else {
        setIsPlaying(false);
      }
    };

    audio.addEventListener('play', onPlay);
    audio.addEventListener('pause', onPause);
    audio.addEventListener('timeupdate', onTimeUpdate);
    audio.addEventListener('ended', onEnded);
    return () => {
      audio.removeEventListener('play', onPlay);
      audio.removeEventListener('pause', onPause);
      audio.removeEventListener('timeupdate', onTimeUpdate);
      audio.removeEventListener('ended', onEnded);
    };
  }, [audio, fileRanges, currentFile, speed]);

  // Debounced progress save.
  const lastSavedRef = useRef<{ pos: number; bookId: number | null }>({ pos: -10, bookId: null });
  useEffect(() => {
    if (!book) return;
    const timer = setInterval(() => {
      const last = lastSavedRef.current;
      if (
        last.bookId === book.id &&
        Math.abs(last.pos - positionInBook) < 2
      ) return;
      const finished = positionInBook >= durationInBook - 1;
      api.saveProgress(book.id, toTimeSpanString(positionInBook), finished, getDeviceId()).catch(() => {});
      lastSavedRef.current = { pos: positionInBook, bookId: book.id };
    }, 4000);

    const onHide = () => {
      if (!book) return;
      const finished = positionInBook >= durationInBook - 1;
      // sendBeacon cannot attach Authorization headers, so use fetch with
      // keepalive: true. Browsers keep the request alive past the unload
      // even though the page is going away.
      const token = localStorage.getItem('fabula.token');
      fetch(`/api/progress/${book.id}`, {
        method: 'PUT',
        keepalive: true,
        headers: {
          'Content-Type': 'application/json',
          ...(token ? { Authorization: `Bearer ${token}` } : {})
        },
        body: JSON.stringify({
          position: toTimeSpanString(positionInBook),
          finished,
          device: getDeviceId()
        })
      }).catch(() => {});
    };
    document.addEventListener('visibilitychange', onHide);
    return () => {
      clearInterval(timer);
      document.removeEventListener('visibilitychange', onHide);
    };
  }, [book, positionInBook, durationInBook]);

  const state: PlayerState = {
    book,
    isPlaying,
    positionInBook,
    durationInBook,
    speed,
    currentChapter,
    nextChapter,
    previousChapter
  };

  return {
    state,
    loadBook,
    play,
    pause,
    toggle,
    skip,
    seekInBook,
    setSpeed,
    jumpToChapter
  };
}
