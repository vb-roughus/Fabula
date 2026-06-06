using Fabula.Core.Domain;

namespace Fabula.Core.Services;

public interface ILibraryRepository
{
    Task<LibraryFolder?> GetLibraryFolderAsync(int id, CancellationToken cancellationToken);
    Task<Book?> FindBookByFolderAsync(int libraryFolderId, string bookDirectory, CancellationToken cancellationToken);
    Task UpsertBookAsync(
        Book book,
        string bookDirectory,
        IReadOnlyList<string> authorNames,
        IReadOnlyList<string> narratorNames,
        string? seriesName,
        decimal? seriesPosition,
        IReadOnlyList<AudioFile> files,
        IReadOnlyList<ChapterInfo> chapters,
        CancellationToken cancellationToken);
    Task<int> RemoveBooksWithMissingFilesAsync(int libraryFolderId, ISet<string> existingFiles, CancellationToken cancellationToken);
    Task MarkFolderScannedAsync(int libraryFolderId, DateTime scannedAt, CancellationToken cancellationToken);

    /// <summary>
    /// Updates only a book's cover (and its UpdatedAt). Used on the
    /// unchanged-audio fast path when the user added or replaced a folder
    /// cover image, which the audio-only content check can't detect.
    /// </summary>
    Task SetBookCoverAsync(int bookId, string? coverPath, CancellationToken cancellationToken);
}
