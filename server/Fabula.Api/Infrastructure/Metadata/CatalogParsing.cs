using System.Globalization;
using System.Net;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace Fabula.Api.Infrastructure.Metadata;

/// <summary>
/// Turning catalogue answers into <see cref="CatalogBook"/>s.
///
/// Written to tolerate gaps rather than to trust a schema: neither Audible's
/// catalogue nor Audnexus publishes a contract, and a field that went missing
/// should cost that field, not the whole lookup.
/// </summary>
public static class CatalogParsing
{
    /// <summary>
    /// A search answer from Audible's catalogue (<c>/1.0/catalog/products</c>).
    /// Products without an ASIN are dropped; without one there is nothing to
    /// look up afterwards.
    /// </summary>
    public static IReadOnlyList<CatalogBook> ParseCatalogSearch(JsonElement root)
    {
        if (root.ValueKind != JsonValueKind.Object ||
            !root.TryGetProperty("products", out var products) ||
            products.ValueKind != JsonValueKind.Array)
            return [];

        var books = new List<CatalogBook>();
        foreach (var product in products.EnumerateArray())
        {
            if (product.ValueKind != JsonValueKind.Object) continue;
            var asin = Str(product, "asin");
            if (asin is null) continue;

            var (seriesName, seriesPosition) = FirstCatalogSeries(product);
            books.Add(new CatalogBook(
                Asin: asin,
                Title: Str(product, "title"),
                Subtitle: Str(product, "subtitle"),
                Authors: Names(product, "authors"),
                Narrators: Names(product, "narrators"),
                SeriesName: seriesName,
                SeriesPosition: seriesPosition,
                Description: HtmlToText(Str(product, "merchandising_summary") ?? Str(product, "publisher_summary")),
                Publisher: Str(product, "publisher_name"),
                Year: YearOf(Str(product, "release_date") ?? Str(product, "issue_date")),
                Language: LanguageCode(Str(product, "language")),
                Isbn: Str(product, "isbn"),
                CoverUrl: CatalogImage(product),
                RuntimeMinutes: Int(product, "runtime_length_min")));
        }
        return books;
    }

    /// <summary>A book record from Audnexus (<c>/books/{asin}</c>); null without an ASIN.</summary>
    public static CatalogBook? ParseAudnexusBook(JsonElement root)
    {
        if (root.ValueKind != JsonValueKind.Object) return null;
        var asin = Str(root, "asin");
        if (asin is null) return null;

        string? seriesName = null;
        decimal? seriesPosition = null;
        if (root.TryGetProperty("seriesPrimary", out var series) && series.ValueKind == JsonValueKind.Object)
        {
            seriesName = Str(series, "name");
            seriesPosition = SeriesPosition(Str(series, "position"));
        }

        return new CatalogBook(
            Asin: asin,
            Title: Str(root, "title"),
            Subtitle: Str(root, "subtitle"),
            Authors: Names(root, "authors"),
            Narrators: Names(root, "narrators"),
            SeriesName: seriesName,
            SeriesPosition: seriesPosition,
            Description: HtmlToText(Str(root, "description") ?? Str(root, "summary")),
            Publisher: Str(root, "publisherName"),
            Year: YearOf(Str(root, "releaseDate")),
            Language: LanguageCode(Str(root, "language")),
            Isbn: Str(root, "isbn"),
            CoverUrl: Str(root, "image"),
            RuntimeMinutes: Int(root, "runtimeLengthMin"));
    }

    /// <summary>
    /// A chapter list from Audnexus (<c>/books/{asin}/chapters</c>); null when
    /// it has none. Times are milliseconds there.
    /// </summary>
    public static CatalogChapters? ParseAudnexusChapters(JsonElement root)
    {
        if (root.ValueKind != JsonValueKind.Object ||
            !root.TryGetProperty("chapters", out var list) ||
            list.ValueKind != JsonValueKind.Array)
            return null;

        var chapters = new List<CatalogChapter>();
        foreach (var item in list.EnumerateArray())
        {
            if (item.ValueKind != JsonValueKind.Object) continue;
            var start = Millis(item, "startOffsetMs") ?? Seconds(item, "startOffsetSec");
            var length = Millis(item, "lengthMs");
            if (start is null || length is null || length <= TimeSpan.Zero) continue;
            chapters.Add(new CatalogChapter(Str(item, "title"), start.Value, length.Value));
        }
        if (chapters.Count == 0) return null;

        return new CatalogChapters(
            chapters,
            Millis(root, "runtimeLengthMs") ?? Seconds(root, "runtimeLengthSec"),
            Millis(root, "brandIntroDurationMs") ?? TimeSpan.Zero,
            Millis(root, "brandOutroDurationMs") ?? TimeSpan.Zero);
    }

    // --- conversions ----------------------------------------------------------

    private static readonly Regex LineBreak = new(@"<br\s*/?>", RegexOptions.IgnoreCase | RegexOptions.Compiled);
    private static readonly Regex BlockEnd = new(@"</(p|div|h[1-6]|ul|ol)\s*>", RegexOptions.IgnoreCase | RegexOptions.Compiled);
    private static readonly Regex ListItem = new(@"<li[^>]*>", RegexOptions.IgnoreCase | RegexOptions.Compiled);
    private static readonly Regex ListItemEnd = new(@"</li\s*>", RegexOptions.IgnoreCase | RegexOptions.Compiled);
    private static readonly Regex AnyTag = new(@"<[^>]+>", RegexOptions.Compiled);
    private static readonly Regex SpacesBeforeBreak = new(@"[ \t]+\n", RegexOptions.Compiled);
    private static readonly Regex ManyBreaks = new(@"\n{3,}", RegexOptions.Compiled);

    /// <summary>
    /// Catalogue descriptions are HTML. Fabula shows plain text, so paragraphs
    /// become blank lines, list items bullets, and everything else is dropped.
    /// </summary>
    public static string? HtmlToText(string? html)
    {
        if (string.IsNullOrWhiteSpace(html)) return null;
        var text = html.Replace("\r\n", "\n").Replace('\r', '\n');
        text = LineBreak.Replace(text, "\n");
        text = BlockEnd.Replace(text, "\n\n");
        text = ListItemEnd.Replace(text, "\n");
        text = ListItem.Replace(text, "• ");
        text = AnyTag.Replace(text, "");
        text = WebUtility.HtmlDecode(text).Replace(' ', ' ');
        text = SpacesBeforeBreak.Replace(text, "\n");
        text = ManyBreaks.Replace(text, "\n\n").Trim();
        return text.Length == 0 ? null : text;
    }

    private static readonly Regex LeadingYear = new(@"^\s*(\d{4})", RegexOptions.Compiled);

    /// <summary>The year of a catalogue date ("2019-05-28", "2019-05-28T00:00:00.000Z").</summary>
    public static int? YearOf(string? date)
    {
        if (date is null) return null;
        var match = LeadingYear.Match(date);
        return match.Success && int.TryParse(match.Groups[1].Value, out var year) && year > 0 ? year : null;
    }

    private static readonly Dictionary<string, string> LanguageCodes = new(StringComparer.OrdinalIgnoreCase)
    {
        ["german"] = "de", ["deutsch"] = "de",
        ["english"] = "en", ["french"] = "fr", ["spanish"] = "es", ["italian"] = "it",
        ["dutch"] = "nl", ["swedish"] = "sv", ["danish"] = "da", ["norwegian"] = "no",
        ["finnish"] = "fi", ["polish"] = "pl", ["portuguese"] = "pt", ["russian"] = "ru",
        ["japanese"] = "ja", ["chinese"] = "zh", ["turkish"] = "tr"
    };

    /// <summary>
    /// Catalogues name the language ("german"); the tags Fabula reads usually
    /// carry a code ("de"). Known names become codes so the two agree.
    /// </summary>
    public static string? LanguageCode(string? language)
    {
        if (string.IsNullOrWhiteSpace(language)) return null;
        var trimmed = language.Trim();
        if (LanguageCodes.TryGetValue(trimmed, out var code)) return code;
        return trimmed.Length <= 16 ? trimmed : trimmed[..16];
    }

    private static readonly Regex FirstNumber = new(@"\d+(?:[.,]\d+)?", RegexOptions.Compiled);

    /// <summary>
    /// A volume number from a catalogue's position text: "3", "3.5", "1-3"
    /// (the first), "Folge 12". Null when there is no number, or one too large
    /// for the column.
    /// </summary>
    public static decimal? SeriesPosition(string? raw)
    {
        if (raw is null) return null;
        var match = FirstNumber.Match(raw);
        if (!match.Success) return null;
        var normalised = match.Value.Replace(',', '.');
        return decimal.TryParse(normalised, NumberStyles.Number, CultureInfo.InvariantCulture, out var value)
               && value < 10000m
            ? value
            : null;
    }

    // --- JSON access ----------------------------------------------------------

    private static string? Str(JsonElement element, string name)
    {
        if (!element.TryGetProperty(name, out var value)) return null;
        var text = value.ValueKind switch
        {
            JsonValueKind.String => value.GetString(),
            JsonValueKind.Number => value.GetRawText(),
            _ => null
        };
        return string.IsNullOrWhiteSpace(text) ? null : text.Trim();
    }

    private static int? Int(JsonElement element, string name)
    {
        if (!element.TryGetProperty(name, out var value)) return null;
        return value.ValueKind switch
        {
            JsonValueKind.Number when value.TryGetInt32(out var number) => number,
            JsonValueKind.Number when value.TryGetDouble(out var real) => (int)Math.Round(real),
            JsonValueKind.String when int.TryParse(value.GetString(), out var parsed) => parsed,
            _ => null
        };
    }

    private static TimeSpan? Millis(JsonElement element, string name)
    {
        if (!element.TryGetProperty(name, out var value) || value.ValueKind != JsonValueKind.Number) return null;
        return value.TryGetInt64(out var ms) ? TimeSpan.FromMilliseconds(ms)
            : value.TryGetDouble(out var real) ? TimeSpan.FromMilliseconds(real)
            : null;
    }

    private static TimeSpan? Seconds(JsonElement element, string name)
    {
        if (!element.TryGetProperty(name, out var value) || value.ValueKind != JsonValueKind.Number) return null;
        return value.TryGetDouble(out var seconds) ? TimeSpan.FromSeconds(seconds) : null;
    }

    /// <summary>People listed as objects with a name, or as plain strings.</summary>
    private static IReadOnlyList<string> Names(JsonElement element, string name)
    {
        if (!element.TryGetProperty(name, out var list) || list.ValueKind != JsonValueKind.Array) return [];
        var names = new List<string>();
        foreach (var item in list.EnumerateArray())
        {
            var value = item.ValueKind switch
            {
                JsonValueKind.Object => Str(item, "name"),
                JsonValueKind.String => item.GetString()?.Trim(),
                _ => null
            };
            if (!string.IsNullOrWhiteSpace(value) && !names.Contains(value, StringComparer.Ordinal))
                names.Add(value);
        }
        return names;
    }

    private static (string? Name, decimal? Position) FirstCatalogSeries(JsonElement product)
    {
        if (!product.TryGetProperty("series", out var list) || list.ValueKind != JsonValueKind.Array)
            return (null, null);
        foreach (var series in list.EnumerateArray())
        {
            if (series.ValueKind != JsonValueKind.Object) continue;
            var name = Str(series, "title") ?? Str(series, "name");
            if (name is null) continue;
            return (name, SeriesPosition(Str(series, "sequence") ?? Str(series, "position")));
        }
        return (null, null);
    }

    /// <summary>The catalogue's cover, preferring the 500 px rendition.</summary>
    private static string? CatalogImage(JsonElement product)
    {
        if (!product.TryGetProperty("product_images", out var images) || images.ValueKind != JsonValueKind.Object)
            return null;
        var preferred = Str(images, "500");
        if (preferred is not null) return preferred;
        foreach (var image in images.EnumerateObject())
        {
            if (image.Value.ValueKind == JsonValueKind.String && !string.IsNullOrWhiteSpace(image.Value.GetString()))
                return image.Value.GetString()!.Trim();
        }
        return null;
    }
}
