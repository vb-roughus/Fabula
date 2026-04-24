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

            var files = bookDir.OrderBy(f => f, StringComparer.OrdinalIgnoreCase).ToList();
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
        if (firstMeta.CoverImage is { Length: > 0 })
            coverPath = await coverStore.SaveCoverAsync(bookDir, firstMeta.CoverImage, firstMeta.CoverMimeType, cancellationToken);

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
        book.CoverPath = coverPath ?? book.CoverPath;
        book.UpdatedAt = DateTime.UtcNow;

        await repository.UpsertBookAsync(
            book,
            bookDir,
            firstMeta.Authors,
            firstMeta.Narrators,
            firstMeta.SeriesName,
            firstMeta.SeriesPosition,
            BuildAudioFiles(allMetadata),
            firstMeta.Chapters,
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

    private enum BookScanOutcome { Added, Updated }
}
