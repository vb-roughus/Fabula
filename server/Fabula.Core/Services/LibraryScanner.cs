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
        // in parallel across the files of this book. Broken files (ATL throws
        // on a malformed tag etc.) are logged and skipped so a single bad
        // file doesn't make the whole book disappear.
        var rawMetadata = await ReadMetadataParallelAsync(files, bookDir, cancellationToken);
        var goodEntries = rawMetadata
            .Select((entry, idx) => (entry, idx))
            .Where(x => x.entry.Meta is not null)
            .ToList();

        if (goodEntries.Count == 0)
        {
            logger.LogWarning("All files in {Dir} failed to read; skipping book.", bookDir);
            return BookScanOutcome.Unchanged;
        }

        if (goodEntries.Count < rawMetadata.Count)
        {
            logger.LogWarning(
                "{Skipped} of {Total} files in {Dir} could not be read and were skipped.",
                rawMetadata.Count - goodEntries.Count, rawMetadata.Count, bookDir);
        }

        var allMetadata = goodEntries
            .Select(x => (Path: x.entry.Path, Meta: x.entry.Meta!))
            .ToList();
        var usableStats = goodEntries.Select(x => stats[x.idx]).ToList();
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

        var audioFiles = BuildAudioFiles(allMetadata, usableStats);
        var chapters = BuildChapters(allMetadata, audioFiles);

        var seriesAssignment = ResolveSeries(
            folder.Path,
            bookDir,
            firstMeta.SeriesName,
            firstMeta.SeriesPosition);

        await repository.UpsertBookAsync(
            book,
            bookDir,
            firstMeta.Authors,
            firstMeta.Narrators,
            seriesAssignment.Name,
            seriesAssignment.Position,
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

    private async Task<List<(string Path, AudioMetadata? Meta)>> ReadMetadataParallelAsync(
        List<string> files,
        string bookDir,
        CancellationToken cancellationToken)
    {
        var results = new (string Path, AudioMetadata? Meta)[files.Count];
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
                try
                {
                    results[i] = (files[i], metadataReader.Read(files[i]));
                }
                catch (Exception ex)
                {
                    logger.LogWarning(ex,
                        "Failed to read metadata for {File} in {Dir}; the file will be skipped.",
                        files[i], bookDir);
                    results[i] = (files[i], null);
                }
                return ValueTask.CompletedTask;
            });
        return [.. results];
    }

    /// <summary>
    /// Picks the series name + position for a book.
    ///
    /// The first sub-folder under the library root wins as the series name
    /// -- that matches a Plex/Jellyfin-style "one folder per series" layout
    /// and is what users intuitively expect when they organise their library
    /// by series. Embedded SeriesName tags only kick in when the folder
    /// structure has nothing to say (book sits directly under the root).
    ///
    /// Position: when the folder structure provides the series, we look for
    /// the first numeric token in the book folder name -- e.g. "Perry Rhodan
    /// Silber Edition 028 - Lemuria" -> 28, "01 - Foo" -> 1, "Vol. 12.5" ->
    /// 12.5. Falls back to the metadata SeriesPosition when the folder name
    /// has no number; uses metadata position directly when metadata also
    /// owns the series name.
    /// </summary>
    internal static (string? Name, decimal? Position) ResolveSeries(
        string libraryRoot,
        string bookDir,
        string? metadataSeriesName,
        decimal? metadataSeriesPosition)
    {
        var folderSeries = DeriveSeriesFromFolders(libraryRoot, bookDir);
        if (folderSeries is not null)
        {
            var fromFolder = ExtractPositionFromName(Path.GetFileName(bookDir));
            return (folderSeries, fromFolder ?? metadataSeriesPosition);
        }

        if (!string.IsNullOrWhiteSpace(metadataSeriesName))
            return (metadataSeriesName.Trim(), metadataSeriesPosition);

        return (null, null);
    }

    private static readonly System.Text.RegularExpressions.Regex PositionInName =
        new(@"\d+(?:[.,]\d+)?", System.Text.RegularExpressions.RegexOptions.Compiled);

    /// <summary>
    /// Extracts a series position from a book folder / file name. Picks the
    /// first numeric token, but skips an obvious year prefix (1900-2099) so
    /// "2024 Edition 028 - Title" still resolves to 28 instead of 2024.
    /// Accepts "1", "01", "012", "12.5", "12,5".
    /// </summary>
    internal static decimal? ExtractPositionFromName(string? name)
    {
        if (string.IsNullOrWhiteSpace(name)) return null;
        var matches = PositionInName.Matches(name);
        foreach (System.Text.RegularExpressions.Match m in matches)
        {
            var raw = m.Value.Replace(',', '.');
            if (!decimal.TryParse(
                    raw,
                    System.Globalization.NumberStyles.Number,
                    System.Globalization.CultureInfo.InvariantCulture,
                    out var value))
                continue;

            // Skip year-like prefixes when there is another number behind
            // them (e.g. "2024 Edition 028" -> 28). A single year-only token
            // still resolves to itself so that books like "1984" don't lose
            // a position they really do have.
            if (m.Value.Length == 4 && value >= 1900m && value <= 2099m && matches.Count > 1)
                continue;

            return value;
        }
        return null;
    }

    private static string? DeriveSeriesFromFolders(string libraryRoot, string bookDir)
    {
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
