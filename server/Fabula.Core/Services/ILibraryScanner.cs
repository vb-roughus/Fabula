namespace Fabula.Core.Services;

public interface ILibraryScanner
{
    Task<ScanResult> ScanAsync(int libraryFolderId, CancellationToken cancellationToken = default);

    /// <summary>
    /// Releases locked fields and gives them back the values the book's files
    /// hold -- only those fields, nothing else on the book. Null when there is
    /// no such book.
    /// </summary>
    Task<RestoreResult?> RestoreFromFilesAsync(
        int bookId,
        IReadOnlySet<string> fields,
        CancellationToken cancellationToken = default);
}

public record ScanResult(int BooksAdded, int BooksUpdated, int BooksRemoved, int FilesScanned);
