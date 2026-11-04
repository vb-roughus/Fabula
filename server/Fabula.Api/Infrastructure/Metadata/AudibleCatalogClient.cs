using System.Net;
using System.Text.Json;
using Microsoft.Extensions.Options;

namespace Fabula.Api.Infrastructure.Metadata;

/// <summary>
/// Looks books up in Audible's catalogue.
///
/// Two services, because neither does both jobs. Audnexus describes a book
/// well -- the full description, narrators, series, a large cover -- but only
/// for an ASIN it is given; it has no search. Audible's own catalogue API can
/// search by title and author and yields the ASINs. So a search asks Audible,
/// and a chosen result is described by Audnexus. Neither is an official,
/// documented interface: both can change without notice, which is why every
/// failure here is reported as "unavailable" rather than taken for an answer.
/// </summary>
public sealed class AudibleCatalogClient(
    HttpClient http,
    IOptions<FabulaOptions> options,
    ILogger<AudibleCatalogClient> logger)
{
    private const long MaxImageBytes = 10 * 1024 * 1024;

    private string Region => CatalogRegions.Normalize(options.Value.MetadataRegion);

    /// <summary>
    /// Candidates for a title and author, best match first as Audible ranks them.
    /// </summary>
    public async Task<IReadOnlyList<CatalogBook>> SearchAsync(string? title, string? author, CancellationToken ct)
    {
        if (string.IsNullOrWhiteSpace(title) && string.IsNullOrWhiteSpace(author)) return [];

        var results = await SearchOnceAsync(title, author, ct);

        // An author written differently from the catalogue's ("Tolkien, J.R.R.",
        // initials, a typo in the tag) turns an exact title into no result at
        // all. The title alone usually still finds it.
        if (results.Count == 0 && !string.IsNullOrWhiteSpace(title) && !string.IsNullOrWhiteSpace(author))
            results = await SearchOnceAsync(title, null, ct);

        return results;
    }

    private async Task<IReadOnlyList<CatalogBook>> SearchOnceAsync(string? title, string? author, CancellationToken ct)
    {
        var query = new List<string>
        {
            "num_results=10",
            "products_sort_by=Relevance",
            "response_groups=contributors,media,product_attrs,product_desc,series"
        };
        if (!string.IsNullOrWhiteSpace(title)) query.Add("title=" + Uri.EscapeDataString(title.Trim()));
        if (!string.IsNullOrWhiteSpace(author)) query.Add("author=" + Uri.EscapeDataString(author.Trim()));

        var url = $"https://{CatalogRegions.AudibleApiHost(Region)}/1.0/catalog/products?{string.Join("&", query)}";
        using var document = await GetJsonAsync(url, "Audible", notFoundIsEmpty: false, ct);
        return document is null ? [] : CatalogParsing.ParseCatalogSearch(document.RootElement);
    }

    /// <summary>The full record for an ASIN, or null when Audnexus does not know it.</summary>
    public async Task<CatalogBook?> GetBookAsync(string asin, CancellationToken ct)
    {
        var url = $"https://api.audnex.us/books/{Uri.EscapeDataString(asin)}?region={Region}";
        using var document = await GetJsonAsync(url, "Audnexus", notFoundIsEmpty: true, ct);
        return document is null ? null : CatalogParsing.ParseAudnexusBook(document.RootElement);
    }

    /// <summary>The chapter list for an ASIN, or null when Audnexus has none.</summary>
    public async Task<CatalogChapters?> GetChaptersAsync(string asin, CancellationToken ct)
    {
        var url = $"https://api.audnex.us/books/{Uri.EscapeDataString(asin)}/chapters?region={Region}";
        using var document = await GetJsonAsync(url, "Audnexus", notFoundIsEmpty: true, ct);
        return document is null ? null : CatalogParsing.ParseAudnexusChapters(document.RootElement);
    }

    /// <summary>
    /// The cover image behind a catalogue URL; null when it is not an image or
    /// too large to be a sensible cover.
    /// </summary>
    public async Task<(byte[] Data, string MimeType)?> DownloadImageAsync(string url, CancellationToken ct)
    {
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri) || uri.Scheme != Uri.UriSchemeHttps) return null;
        try
        {
            using var response = await http.GetAsync(uri, HttpCompletionOption.ResponseHeadersRead, ct);
            if (!response.IsSuccessStatusCode) return null;
            var mime = response.Content.Headers.ContentType?.MediaType;
            if (mime is null || !mime.StartsWith("image/", StringComparison.OrdinalIgnoreCase)) return null;
            if (response.Content.Headers.ContentLength > MaxImageBytes) return null;
            var bytes = await response.Content.ReadAsByteArrayAsync(ct);
            return bytes.Length is > 0 and <= (int)MaxImageBytes ? (bytes, mime) : null;
        }
        catch (Exception ex) when (ex is HttpRequestException || (ex is TaskCanceledException && !ct.IsCancellationRequested))
        {
            logger.LogWarning(ex, "Cover von {Url} konnte nicht geladen werden.", uri.Host);
            return null;
        }
    }

    private async Task<JsonDocument?> GetJsonAsync(string url, string service, bool notFoundIsEmpty, CancellationToken ct)
    {
        try
        {
            using var response = await http.GetAsync(url, ct);
            // Audnexus answers an unknown or region-less ASIN with 404 or 400.
            if (notFoundIsEmpty && response.StatusCode is HttpStatusCode.NotFound or HttpStatusCode.BadRequest)
                return null;
            if (!response.IsSuccessStatusCode)
            {
                logger.LogWarning("{Service} antwortete mit {Status}.", service, (int)response.StatusCode);
                throw new CatalogUnavailableException($"{service} antwortet mit Fehler {(int)response.StatusCode}.");
            }
            await using var stream = await response.Content.ReadAsStreamAsync(ct);
            return await JsonDocument.ParseAsync(stream, cancellationToken: ct);
        }
        catch (Exception ex) when (ex is HttpRequestException || (ex is TaskCanceledException && !ct.IsCancellationRequested))
        {
            logger.LogWarning(ex, "{Service} nicht erreichbar.", service);
            throw new CatalogUnavailableException($"{service} ist nicht erreichbar.", ex);
        }
        catch (JsonException ex)
        {
            logger.LogWarning(ex, "{Service} lieferte kein lesbares JSON.", service);
            throw new CatalogUnavailableException($"{service} lieferte eine unlesbare Antwort.", ex);
        }
    }
}
