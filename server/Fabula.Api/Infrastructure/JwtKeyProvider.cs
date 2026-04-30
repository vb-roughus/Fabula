using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Fabula.Api.Infrastructure;

/// <summary>
/// Loads (or lazily creates) the symmetric signing key for the JWT auth.
/// Persisted in <c>secrets.json</c> next to the data directory so the same
/// key survives upgrades and re-installs without invalidating issued tokens.
/// </summary>
public static class JwtKeyProvider
{
    public static byte[] LoadOrCreate(string dataDirectory)
    {
        var dir = Path.GetDirectoryName(dataDirectory.TrimEnd(Path.DirectorySeparatorChar)) ?? dataDirectory;
        Directory.CreateDirectory(dir);
        var path = Path.Combine(dir, "secrets.json");

        if (File.Exists(path))
        {
            try
            {
                var existing = JsonSerializer.Deserialize<SecretsFile>(File.ReadAllText(path));
                var encoded = existing?.Jwt?.SigningKey;
                if (!string.IsNullOrWhiteSpace(encoded))
                    return Convert.FromBase64String(encoded);
            }
            catch
            {
                // Corrupt secrets file -- fall through to regenerate. The
                // existing file is overwritten below.
            }
        }

        var bytes = RandomNumberGenerator.GetBytes(64);
        var content = new SecretsFile { Jwt = new JwtSecretSection { SigningKey = Convert.ToBase64String(bytes) } };
        File.WriteAllText(path, JsonSerializer.Serialize(content, new JsonSerializerOptions { WriteIndented = true }));

        if (!OperatingSystem.IsWindows())
        {
            try
            {
                File.SetUnixFileMode(path,
                    UnixFileMode.UserRead | UnixFileMode.UserWrite);
            }
            catch
            {
                // Best-effort; some filesystems don't support it.
            }
        }

        return bytes;
    }

    private sealed class SecretsFile
    {
        [JsonPropertyName("Jwt")]
        public JwtSecretSection? Jwt { get; set; }
    }

    private sealed class JwtSecretSection
    {
        [JsonPropertyName("SigningKey")]
        public string SigningKey { get; set; } = string.Empty;
    }
}
