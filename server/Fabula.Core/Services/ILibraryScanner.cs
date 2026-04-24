namespace Fabula.Core.Services;

public interface ILibraryScanner
{
    Task<ScanResult> ScanAsync(int libraryFolderId, CancellationToken cancellationToken = default);
}

public record ScanResult(int BooksAdded, int BooksUpdated, int BooksRemoved, int FilesScanned);
