namespace Fabula.Api.Infrastructure.Metadata;

/// <summary>
/// One book as an online catalogue describes it, already turned into the
/// shape of Fabula's own fields. Every field is optional: catalogues leave
/// gaps, and a search result carries less than a full record.
/// </summary>
public sealed record CatalogBook(
    string Asin,
    string? Title,
    string? Subtitle,
    IReadOnlyList<string> Authors,
    IReadOnlyList<string> Narrators,
    string? SeriesName,
    decimal? SeriesPosition,
    string? Description,
    string? Publisher,
    int? Year,
    string? Language,
    string? Isbn,
    string? CoverUrl,
    int? RuntimeMinutes);

/// <summary>The catalogue could not be asked: unreachable, timed out, or answered with an error.</summary>
public sealed class CatalogUnavailableException(string message, Exception? inner = null)
    : Exception(message, inner);

/// <summary>
/// The Audible marketplaces. The region decides both which catalogue is
/// searched and which edition Audnexus describes -- a German listener wants
/// the German narrator, not the American one.
/// </summary>
public static class CatalogRegions
{
    public const string Default = "de";

    private static readonly Dictionary<string, string> Hosts = new(StringComparer.OrdinalIgnoreCase)
    {
        ["de"] = "api.audible.de",
        ["us"] = "api.audible.com",
        ["uk"] = "api.audible.co.uk",
        ["fr"] = "api.audible.fr",
        ["it"] = "api.audible.it",
        ["es"] = "api.audible.es",
        ["ca"] = "api.audible.ca",
        ["au"] = "api.audible.com.au",
        ["in"] = "api.audible.in",
        ["jp"] = "api.audible.co.jp"
    };

    /// <summary>A known region in lower case, or <see cref="Default"/>.</summary>
    public static string Normalize(string? region)
    {
        var trimmed = region?.Trim().ToLowerInvariant();
        return trimmed is not null && Hosts.ContainsKey(trimmed) ? trimmed : Default;
    }

    public static string AudibleApiHost(string? region) => Hosts[Normalize(region)];
}
