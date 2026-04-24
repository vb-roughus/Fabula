using System.Security.Cryptography;
using Fabula.Core.Services;
using Microsoft.Extensions.Options;

namespace Fabula.Api.Infrastructure;

public class FileSystemCoverStore(IOptions<FabulaOptions> options) : ICoverStore
{
    private readonly string _root = ResolveRoot(options.Value);

    public async Task<string> SaveCoverAsync(string bookKey, byte[] data, string? mimeType, CancellationToken cancellationToken)
    {
        Directory.CreateDirectory(_root);
        var hash = Convert.ToHexString(SHA1.HashData(System.Text.Encoding.UTF8.GetBytes(bookKey)))[..16].ToLowerInvariant();
        var ext = MimeToExtension(mimeType);
        var relative = $"{hash}{ext}";
        var absolute = Path.Combine(_root, relative);
        await File.WriteAllBytesAsync(absolute, data, cancellationToken);
        return relative;
    }

    public string GetCoverFilePath(string relativeCoverPath) => Path.Combine(_root, relativeCoverPath);

    private static string ResolveRoot(FabulaOptions options)
    {
        var root = string.IsNullOrWhiteSpace(options.CoversDirectory)
            ? Path.Combine(options.DataDirectory, "covers")
            : options.CoversDirectory;
        Directory.CreateDirectory(root);
        return root;
    }

    private static string MimeToExtension(string? mime) => mime?.ToLowerInvariant() switch
    {
        "image/png" => ".png",
        "image/webp" => ".webp",
        "image/gif" => ".gif",
        _ => ".jpg"
    };
}
