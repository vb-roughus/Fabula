using System.Text.Json;
using Fabula.Api.Infrastructure.Metadata;
using Xunit;

namespace Fabula.Tests;

/// <summary>
/// Reading Audible's catalogue and Audnexus.
///
/// The JSON below follows the shape these services answer with, as Audnexus
/// documents it and as other audiobook servers consume it. It was not captured
/// from a live call -- the build environment cannot reach either service --
/// so the parser is written to lose a missing field, never the whole record.
/// </summary>
public class CatalogParsingTests
{
    private static JsonElement Json(string text) => JsonDocument.Parse(text).RootElement;

    private const string AudnexusBook = """
        {
          "asin": "B004V0YKT2",
          "authors": [ { "asin": "B000APZOQA", "name": "Michael Ende" } ],
          "description": "<p>Momo lebt in einem alten Amphitheater.</p><p>Doch dann kommen die <b>grauen Herren</b> &amp; stehlen die Zeit.</p>",
          "formatType": "unabridged",
          "genres": [ { "asin": "1", "name": "Kinder", "type": "genre" } ],
          "image": "https://m.media-amazon.com/images/I/81abc.jpg",
          "isbn": "9783867423456",
          "language": "german",
          "narrators": [ { "name": "Gert Westphal" } ],
          "publisherName": "Der Hörverlag",
          "region": "de",
          "releaseDate": "2005-03-01T00:00:00.000Z",
          "runtimeLengthMin": 412,
          "seriesPrimary": { "asin": "B0S1", "name": "Momo", "position": "1" },
          "subtitle": "oder Die seltsame Geschichte von den Zeit-Dieben",
          "summary": "<p>Kurzfassung</p>",
          "title": "Momo"
        }
        """;

    // --- Audnexus -------------------------------------------------------------

    [Fact]
    public void Reads_an_Audnexus_record()
    {
        var book = CatalogParsing.ParseAudnexusBook(Json(AudnexusBook))!;

        Assert.Equal("B004V0YKT2", book.Asin);
        Assert.Equal("Momo", book.Title);
        Assert.Equal("oder Die seltsame Geschichte von den Zeit-Dieben", book.Subtitle);
        Assert.Equal(new[] { "Michael Ende" }, book.Authors);
        Assert.Equal(new[] { "Gert Westphal" }, book.Narrators);
        Assert.Equal("Momo", book.SeriesName);
        Assert.Equal(1m, book.SeriesPosition);
        Assert.Equal("Der Hörverlag", book.Publisher);
        Assert.Equal(2005, book.Year);
        Assert.Equal("de", book.Language);
        Assert.Equal("9783867423456", book.Isbn);
        Assert.Equal("https://m.media-amazon.com/images/I/81abc.jpg", book.CoverUrl);
        Assert.Equal(412, book.RuntimeMinutes);
    }

    [Fact]
    public void Turns_the_HTML_description_into_paragraphs()
    {
        var book = CatalogParsing.ParseAudnexusBook(Json(AudnexusBook))!;

        Assert.Equal(
            "Momo lebt in einem alten Amphitheater.\n\nDoch dann kommen die grauen Herren & stehlen die Zeit.",
            book.Description);
    }

    [Fact]
    public void Falls_back_to_the_summary_without_a_description()
    {
        var book = CatalogParsing.ParseAudnexusBook(Json("""{ "asin": "B000000001", "summary": "<p>Kurz</p>" }"""))!;

        Assert.Equal("Kurz", book.Description);
    }

    /// <summary>A record with gaps costs the gaps, not the record.</summary>
    [Fact]
    public void A_sparse_record_still_reads()
    {
        var book = CatalogParsing.ParseAudnexusBook(Json("""{ "asin": "B000000001", "title": "Nur ein Titel" }"""))!;

        Assert.Equal("Nur ein Titel", book.Title);
        Assert.Empty(book.Authors);
        Assert.Null(book.SeriesName);
        Assert.Null(book.RuntimeMinutes);
    }

    [Fact]
    public void A_record_without_an_ASIN_is_no_record()
    {
        Assert.Null(CatalogParsing.ParseAudnexusBook(Json("""{ "title": "x" }""")));
        Assert.Null(CatalogParsing.ParseAudnexusBook(Json("""[]""")));
    }

    // --- Audible catalogue search ----------------------------------------------

    private const string CatalogSearch = """
        {
          "products": [
            {
              "asin": "B004V0YKT2",
              "title": "Momo",
              "subtitle": "oder Die seltsame Geschichte",
              "authors": [ { "asin": "B000APZOQA", "name": "Michael Ende" } ],
              "narrators": [ { "name": "Gert Westphal" } ],
              "publisher_name": "Der Hörverlag",
              "release_date": "2005-03-01",
              "runtime_length_min": 412,
              "language": "german",
              "product_images": { "500": "https://m.media-amazon.com/images/I/81abc._SL500_.jpg" },
              "series": [ { "asin": "B0S1", "title": "Momo", "sequence": "1" } ],
              "merchandising_summary": "<p>Die Zeit-Diebe</p>"
            },
            { "title": "Kein ASIN, kein Treffer" },
            {
              "asin": "B0GEKUERZT",
              "title": "Momo (gekürzt)",
              "authors": [ { "name": "Michael Ende" } ],
              "runtime_length_min": 180,
              "product_images": { "252": "https://m.media-amazon.com/images/I/small.jpg" }
            }
          ],
          "total_results": 3
        }
        """;

    [Fact]
    public void Reads_the_products_of_a_search()
    {
        var results = CatalogParsing.ParseCatalogSearch(Json(CatalogSearch));

        Assert.Equal(new[] { "B004V0YKT2", "B0GEKUERZT" }, results.Select(r => r.Asin));

        var first = results[0];
        Assert.Equal("Momo", first.Title);
        Assert.Equal(new[] { "Gert Westphal" }, first.Narrators);
        Assert.Equal("Momo", first.SeriesName);
        Assert.Equal(1m, first.SeriesPosition);
        Assert.Equal(412, first.RuntimeMinutes);
        Assert.Equal(2005, first.Year);
        Assert.Equal("de", first.Language);
        Assert.Equal("https://m.media-amazon.com/images/I/81abc._SL500_.jpg", first.CoverUrl);
        Assert.Equal("Die Zeit-Diebe", first.Description);
    }

    [Fact]
    public void Takes_any_image_when_the_500_pixel_one_is_missing()
    {
        var results = CatalogParsing.ParseCatalogSearch(Json(CatalogSearch));

        Assert.Equal("https://m.media-amazon.com/images/I/small.jpg", results[1].CoverUrl);
    }

    [Fact]
    public void An_answer_without_products_has_no_results()
    {
        Assert.Empty(CatalogParsing.ParseCatalogSearch(Json("""{ "total_results": 0 }""")));
        Assert.Empty(CatalogParsing.ParseCatalogSearch(Json("""{ "products": null }""")));
    }

    // --- conversions ----------------------------------------------------------

    [Theory]
    [InlineData("1", 1)]
    [InlineData("3.5", 3.5)]
    [InlineData("2,5", 2.5)]
    [InlineData("1-3", 1)]
    [InlineData("Folge 12", 12)]
    public void Reads_a_volume_number(string raw, double expected)
    {
        Assert.Equal((decimal)expected, CatalogParsing.SeriesPosition(raw));
    }

    [Theory]
    [InlineData(null)]
    [InlineData("Sonderband")]
    [InlineData("123456")]
    public void No_usable_volume_number_is_none(string? raw)
    {
        Assert.Null(CatalogParsing.SeriesPosition(raw));
    }

    [Theory]
    [InlineData("german", "de")]
    [InlineData("English", "en")]
    [InlineData("de", "de")]
    [InlineData("klingonisch", "klingonisch")]
    public void Names_a_language_by_its_code(string name, string expected)
    {
        Assert.Equal(expected, CatalogParsing.LanguageCode(name));
    }

    [Theory]
    [InlineData("2019-05-28", 2019)]
    [InlineData("2019-05-28T00:00:00.000Z", 2019)]
    public void Reads_the_year_of_a_date(string date, int expected)
    {
        Assert.Equal(expected, CatalogParsing.YearOf(date));
    }

    [Fact]
    public void A_date_without_a_year_has_none()
    {
        Assert.Null(CatalogParsing.YearOf("demnächst"));
        Assert.Null(CatalogParsing.YearOf(null));
    }

    [Fact]
    public void Lists_and_line_breaks_survive_as_text()
    {
        var text = CatalogParsing.HtmlToText("<p>Enthält:</p><ul><li>Teil eins</li><li>Teil zwei</li></ul>Zeile<br/>Umbruch");

        Assert.Equal("Enthält:\n\n• Teil eins\n• Teil zwei\n\nZeile\nUmbruch", text);
    }

    [Fact]
    public void An_empty_description_is_none()
    {
        Assert.Null(CatalogParsing.HtmlToText("<p> </p>"));
        Assert.Null(CatalogParsing.HtmlToText(null));
    }
}
