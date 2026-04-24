namespace Fabula.Core.Services;

public interface ICoverStore
{
    Task<string> SaveCoverAsync(string bookKey, byte[] data, string? mimeType, CancellationToken cancellationToken);
    string GetCoverFilePath(string relativeCoverPath);
}
