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

    /// <summary>
    /// Writes metadata an admin took over from an online catalogue, and locks
    /// the fields that were written so a rescan leaves them alone.
    /// </summary>
    /// <returns>The fields actually taken; null when there is no such book.</returns>
    Task<IReadOnlyList<string>?> ApplyMetadataAsync(
        int bookId,
        BookMetadataUpdate update,
        CancellationToken cancellationToken);

    /// <summary>A book with its files and its library folder, for reading its tags again.</summary>
    Task<Book?> GetBookWithFilesAsync(int bookId, CancellationToken cancellationToken);

    /// <summary>
    /// Unlocks <paramref name="fields"/> and, when <paramref name="tags"/> is
    /// given, writes their values from it -- empty ones included, since empty
    /// is what the files say.
    /// </summary>
    Task RestoreFieldsAsync(
        int bookId,
        IReadOnlySet<string> fields,
        TagValues? tags,
        CancellationToken cancellationToken);
}
