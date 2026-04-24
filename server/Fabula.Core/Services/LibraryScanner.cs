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

        int added = 0, updated = 0;

        foreach (var bookDir in grouped)
        {
            cancellationToken.ThrowIfCancellationRequested();

            var files = bookDir.OrderBy(f => f, NaturalStringComparer.Instance).ToList();
            try
            {
                var result = await ProcessBookAsync(folder, bookDir.Key, files, cancellationToken);
                if (result == BookScanOutcome.Added) added++;
                else if (result == BookScanOutcome.Updated) updated++;
            }
            catch (Exception ex)
            {
                logger.LogError(ex, "Failed to scan book in {Dir}", bookDir.Key);
            }
        }

        var removed = await repository.RemoveBooksWithMissingFilesAsync(folder.Id, cancellationToken);

        await repository.MarkFolderScannedAsync(folder.Id, DateTime.UtcNow, cancellationToken);

        return new ScanResult(added, updated, removed, audioFiles.Count);
    }

    private async Task<BookScanOutcome> ProcessBookAsync(
        LibraryFolder folder,
        string bookDir,
        List<string> files,
        CancellationToken cancellationToken)
    {
        var firstMeta = metadataReader.Read(files[0]);
        var title = firstMeta.Album ?? firstMeta.Title ?? Path.GetFileName(bookDir);

        var existing = await repository.FindBookByFolderAsync(folder.Id, bookDir, cancellationToken);

        var allMetadata = files.Select(f => (Path: f, Meta: metadataReader.Read(f))).ToList();
        var totalDuration = TimeSpan.FromTicks(allMetadata.Sum(m => m.Meta.Duration.Ticks));

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

        var audioFiles = BuildAudioFiles(allMetadata);
        var chapters = BuildChapters(allMetadata, audioFiles);

        await repository.UpsertBookAsync(
            book,
            bookDir,
            firstMeta.Authors,
            firstMeta.Narrators,
            firstMeta.SeriesName,
            firstMeta.SeriesPosition,
            audioFiles,
            chapters,
            cancellationToken);

        return existing is null ? BookScanOutcome.Added : BookScanOutcome.Updated;
    }

    private static List<AudioFile> BuildAudioFiles(List<(string Path, AudioMetadata Meta)> metadata)
    {
        var result = new List<AudioFile>(metadata.Count);
        var offset = TimeSpan.Zero;
        for (int i = 0; i < metadata.Count; i++)
        {
            var (path, meta) = metadata[i];
            var info = new FileInfo(path);
            result.Add(new AudioFile
            {
                TrackIndex = i,
                Path = path,
                SizeBytes = info.Length,
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

    private enum BookScanOutcome { Added, Updated }
}
