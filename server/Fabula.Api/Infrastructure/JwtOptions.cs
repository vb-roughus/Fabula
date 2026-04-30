namespace Fabula.Api.Infrastructure;

public class JwtOptions
{
    public const string SectionName = "Jwt";

    public string Issuer { get; set; } = "fabula";
    public string Audience { get; set; } = "fabula";
    public int LifetimeDays { get; set; } = 30;

    /// <summary>Base64-encoded signing key (resolved by JwtKeyProvider).</summary>
    public string SigningKey { get; set; } = string.Empty;
}
