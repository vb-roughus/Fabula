using Fabula.Core.Domain;
using Microsoft.Extensions.Logging;

namespace Fabula.Core.Services;

public class LibraryScanner(
    ILibraryRepository repository,
    IAudioMetadataReader metadataReader,
    ICoverStore coverStore,
    ILogger<LibraryScanner> logger) : ILibraryScanner
{
    private static readonly HashSet<string> SupportedExtensions = new(StringComparer.OrdinalIgnoreCase)
    {
        ".mp3", ".m4a", ".m4b", ".flac", ".ogg", ".opus", ".wav", ".aac"
    };

    public async Task<ScanResult> ScanAsync(int libraryFolderId, CancellationToken cancellationToken = default)
    {
        var folder = await repository.GetLibraryFolderAsync(libraryFolderId, cancellationToken)
            ?? throw new InvalidOperationException($"Library folder {libraryFolderId} not found.");

        if (!Directory.Exists(folder.Path))
            throw new DirectoryNotFoundException($"Library path does not exist: {folder.Path}");

        logger.LogInformation("Scanning library '{Name}' at {Path}", folder.Name, folder.Path);

        var audioFiles = Directory
            .EnumerateFiles(folder.Path, "*", SearchOption.AllDirectories)
            .Where(f => SupportedExtensions.Contains(Path.GetExtension(f)))
            .ToList();

        var grouped = audioFiles
            .GroupBy(f => Path.GetDirectoryName(f) ?? folder.Path)
            .ToList();

        int added = 0, updated = 0, unchanged = 0;

        foreach (var bookDir in grouped)
        {
            cancellationToken.ThrowIfCancellationRequested();

            var files = bookDir.OrderBy(f => f, NaturalStringComparer.Instance).ToList();
            try
            {
                var result = await ProcessBookAsync(folder, bookDir.Key, files, cancellationToken);
                switch (result)
                {
                    case BookScanOutcome.Added: added++; break;
                    case BookScanOutcome.Updated: updated++; break;
                    case BookScanOutcome.Unchanged: unchanged++; break;
                }
            }
            catch (Exception ex)
            {
                logger.LogError(ex, "Failed to scan book in {Dir}", bookDir.Key);
            }
        }

        var existingPaths = new HashSet<string>(audioFiles, StringComparer.OrdinalIgnoreCase);
        var removed = await repository.RemoveBooksWithMissingFilesAsync(folder.Id, existingPaths, cancellationToken);

        await repository.MarkFolderScannedAsync(folder.Id, DateTime.UtcNow, cancellationToken);

        logger.LogInformation(
            "Scan finished for '{Name}': {Added} added, {Updated} updated, {Unchanged} unchanged, {Removed} removed ({Files} files)",
            folder.Name, added, updated, unchanged, removed, audioFiles.Count);

        return new ScanResult(added, updated, removed, audioFiles.Count);
    }

    private async Task<BookScanOutcome> ProcessBookAsync(
        LibraryFolder folder,
        string bookDir,
        List<string> files,
        CancellationToken cancellationToken)
    {
        // Single stat per file (one SMB round-trip each). Used both for the
        // "is this book unchanged?" fast path and for AudioFile.SizeBytes
        // below, so we never stat the same file twice.
        var stats = files.Select(f => new FileInfo(f)).ToList();

        var existing = await repository.FindBookByFolderAsync(folder.Id, bookDir, cancellationToken);

        // Fast skip: the existing DB row matches what's on disk (same paths,
        // same sizes). Nothing to re-read or re-parse.
        if (existing is not null && BookContentMatches(existing, stats))
            return BookScanOutcome.Unchanged;

        // Tag parsing dominates the per-book cost on network shares. Run it
        // in parallel across the files of this book.
        var allMetadata = await ReadMetadataParallelAsync(files, cancellationToken);
        var firstMeta = allMetadata[0].Meta;
        var totalDuration = TimeSpan.FromTicks(allMetadata.Sum(m => m.Meta.Duration.Ticks));

        var title = firstMeta.Album ?? firstMeta.Title ?? Path.GetFileName(bookDir);

        string? coverPath = null;
        var folderCover = FindFolderCover(bookDir);
        if (folderCover is not null)
        {
            var bytes = await File.ReadAllBytesAsync(folderCover, cancellationToken);
            coverPath = await coverStore.SaveCoverAsync(bookDir, bytes, GetImageMimeType(folderCover), cancellationToken);
        }
        else if (firstMeta.CoverImage is { Length: > 0 })
        {
            coverPath = await coverStore.SaveCoverAsync(bookDir, firstMeta.CoverImage, firstMeta.CoverMimeType, cancellationToken);
        }

        var book = existing ?? new Book
        {
            LibraryFolderId = folder.Id,
            AddedAt = DateTime.UtcNow
        };

        book.Title = title;
        book.Subtitle = firstMeta.Subtitle;
        book.Description = firstMeta.Description;
        book.Language = firstMeta.Language;
        book.Publisher = firstMeta.Publisher;
        book.PublishYear = firstMeta.Year;
        book.Isbn = firstMeta.Isbn;
        book.Asin = firstMeta.Asin;
        book.Duration = totalDuration;
        book.CoverPath = coverPath;
        book.UpdatedAt = DateTime.UtcNow;

        var audioFiles = BuildAudioFiles(allMetadata, stats);
        var chapters = BuildChapters(allMetadata, audioFiles);

        await repository.UpsertBookAsync(
            book,
            bookDir,
            firstMeta.Authors,
            firstMeta.Narrators,
            ResolveSeriesName(folder.Path, bookDir, firstMeta.SeriesName),
            firstMeta.SeriesPosition,
            audioFiles,
            chapters,
            cancellationToken);

        return existing is null ? BookScanOutcome.Added : BookScanOutcome.Updated;
    }

    private static bool BookContentMatches(Book book, List<FileInfo> stats)
    {
        if (book.Files.Count != stats.Count)
            return false;

        var byPath = new Dictionary<string, AudioFile>(book.Files.Count, StringComparer.OrdinalIgnoreCase);
        foreach (var f in book.Files)
            byPath[f.Path] = f;

        foreach (var info in stats)
        {
            if (!byPath.TryGetValue(info.FullName, out var dbFile))
                return false;
            if (dbFile.SizeBytes != info.Length)
                return false;
        }
        return true;
    }

    private async Task<List<(string Path, AudioMetadata Meta)>> ReadMetadataParallelAsync(
        List<string> files,
        CancellationToken cancellationToken)
    {
        var results = new (string Path, AudioMetadata Meta)[files.Count];
        var options = new ParallelOptions
        {
            MaxDegreeOfParallelism = Math.Min(8, Environment.ProcessorCount * 2),
            CancellationToken = cancellationToken
        };
        await Parallel.ForEachAsync(
            Enumerable.Range(0, files.Count),
            options,
            (i, _) =>
            {
                results[i] = (files[i], metadataReader.Read(files[i]));
                return ValueTask.CompletedTask;
            });
        return [.. results];
    }

    /// <summary>
    /// Picks the series name for a book. Embedded metadata wins; if it is
    /// missing, we treat the first sub-folder under the library root as the
    /// series. Files that live directly under the root, or in a single
    /// folder under it, are considered standalone (no series).
    /// </summary>
    internal static string? ResolveSeriesName(string libraryRoot, string bookDir, string? metadataSeriesName)
    {
        if (!string.IsNullOrWhiteSpace(metadataSeriesName))
            return metadataSeriesName.Trim();

        var rootFull = Path.GetFullPath(libraryRoot)
            .TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
        var bookFull = Path.GetFullPath(bookDir)
            .TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);

        if (!bookFull.StartsWith(rootFull, StringComparison.OrdinalIgnoreCase))
            return null;

        var relative = bookFull[rootFull.Length..]
            .TrimStart(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
        if (string.IsNullOrEmpty(relative))
            return null;

        var segments = relative.Split(
            [Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar],
            StringSplitOptions.RemoveEmptyEntries);

        // <root>/<book> -- one segment, standalone.
        if (segments.Length < 2)
            return null;

        var name = segments[0].Trim();
        return string.IsNullOrEmpty(name) ? null : name;
    }

    private static List<AudioFile> BuildAudioFiles(List<(string Path, AudioMetadata Meta)> metadata, List<FileInfo> stats)
    {
        var result = new List<AudioFile>(metadata.Count);
        var offset = TimeSpan.Zero;
        for (int i = 0; i < metadata.Count; i++)
        {
            var (path, meta) = metadata[i];
            result.Add(new AudioFile
            {
                TrackIndex = i,
                Path = path,
                SizeBytes = stats[i].Length,
                Duration = meta.Duration,
                Codec = meta.Codec,
                BitrateKbps = meta.BitrateKbps,
                SampleRate = meta.SampleRate,
                OffsetInBook = offset
            });
            offset += meta.Duration;
        }
        return result;
    }

    private static List<ChapterInfo> BuildChapters(
        List<(string Path, AudioMetadata Meta)> metadata,
        List<AudioFile> files)
    {
        var hasEmbedded = metadata.Any(m => m.Meta.Chapters.Count > 0);

        if (hasEmbedded)
        {
            var result = new List<ChapterInfo>();
            for (int i = 0; i < metadata.Count; i++)
            {
                var offset = files[i].OffsetInBook;
                foreach (var c in metadata[i].Meta.Chapters)
                {
                    result.Add(new ChapterInfo(c.Title, offset + c.Start, offset + c.End));
                }
            }
            return result;
        }

        if (metadata.Count > 1)
        {
            // Audiobook rips often tag every file with the same title (the book name).
            // Only use per-track titles if they are actually distinct.
            var titles = metadata
                .Select(m => m.Meta.Title?.Trim() ?? string.Empty)
                .ToList();
            var allTitlesDistinct = titles.All(t => !string.IsNullOrWhiteSpace(t)) &&
                                    titles.Distinct(StringComparer.OrdinalIgnoreCase).Count() == metadata.Count;

            var result = new List<ChapterInfo>(metadata.Count);
            for (int i = 0; i < metadata.Count; i++)
            {
                var title = allTitlesDistinct ? titles[i] : $"Kapitel {i + 1}";
                var offset = files[i].OffsetInBook;
                result.Add(new ChapterInfo(title, offset, offset + metadata[i].Meta.Duration));
            }
            return result;
        }

        return [];
    }

    private static readonly string[] ImageExtensions = [".jpg", ".jpeg", ".png", ".webp", ".gif"];
    private static readonly string[] PreferredCoverBaseNames = ["cover", "folder", "front", "poster", "albumart", "album"];

    private static string? FindFolderCover(string bookDir)
    {
        if (!Directory.Exists(bookDir)) return null;

        var images = Directory
            .EnumerateFiles(bookDir, "*", SearchOption.TopDirectoryOnly)
            .Where(f => ImageExtensions.Contains(Path.GetExtension(f), StringComparer.OrdinalIgnoreCase))
            .ToList();

        if (images.Count == 0) return null;

        foreach (var preferred in PreferredCoverBaseNames)
        {
            var match = images.FirstOrDefault(f =>
                string.Equals(Path.GetFileNameWithoutExtension(f), preferred, StringComparison.OrdinalIgnoreCase));
            if (match is not null) return match;
        }

        return images.OrderBy(f => f, StringComparer.OrdinalIgnoreCase).First();
    }

    private static string GetImageMimeType(string path) => Path.GetExtension(path).ToLowerInvariant() switch
    {
        ".png" => "image/png",
        ".webp" => "image/webp",
        ".gif" => "image/gif",
        _ => "image/jpeg"
    };

    private enum BookScanOutcome { Added, Updated, Unchanged }
}
