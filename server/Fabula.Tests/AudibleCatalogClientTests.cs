using System.Net;
using System.Text;
using Fabula.Api.Infrastructure;
using Fabula.Api.Infrastructure.Metadata;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;
using Xunit;

namespace Fabula.Tests;

/// <summary>
/// What the client asks the catalogues, and how it takes their failures.
/// The answers come from a scripted handler; no request leaves the machine.
/// </summary>
public class AudibleCatalogClientTests
{
    private sealed class ScriptedHandler(Func<HttpRequestMessage, HttpResponseMessage> answer) : HttpMessageHandler
    {
        public List<Uri> Requests { get; } = [];

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            Requests.Add(request.RequestUri!);
            return Task.FromResult(answer(request));
        }
    }

    private static HttpResponseMessage Json(string body, HttpStatusCode status = HttpStatusCode.OK) =>
        new(status) { Content = new StringContent(body, Encoding.UTF8, "application/json") };

    private static (AudibleCatalogClient Client, ScriptedHandler Handler) Client(
        Func<HttpRequestMessage, HttpResponseMessage> answer, string region = "de")
    {
        var handler = new ScriptedHandler(answer);
        var client = new AudibleCatalogClient(
            new HttpClient(handler),
            Options.Create(new FabulaOptions { MetadataRegion = region }),
            NullLogger<AudibleCatalogClient>.Instance);
        return (client, handler);
    }

    private const string OneProduct = """{ "products": [ { "asin": "B004V0YKT2", "title": "Momo" } ] }""";
    private const string NoProducts = """{ "products": [] }""";

    // --- search ---------------------------------------------------------------

    [Fact]
    public async Task Searches_the_German_catalogue_by_title_and_author()
    {
        var (client, handler) = Client(_ => Json(OneProduct));

        var results = await client.SearchAsync("Momo", "Michael Ende", CancellationToken.None);

        Assert.Single(results);
        var request = Assert.Single(handler.Requests);
        Assert.Equal("api.audible.de", request.Host);
        Assert.Equal("/1.0/catalog/products", request.AbsolutePath);
        Assert.Contains("title=Momo", request.Query);
        Assert.Contains("author=Michael%20Ende", request.Query);
    }

    [Fact]
    public async Task Another_region_searches_another_catalogue()
    {
        var (client, handler) = Client(_ => Json(OneProduct), region: "uk");

        await client.SearchAsync("Momo", null, CancellationToken.None);

        Assert.Equal("api.audible.co.uk", handler.Requests.Single().Host);
    }

    [Fact]
    public async Task An_unknown_region_falls_back_to_Germany()
    {
        var (client, handler) = Client(_ => Json(OneProduct), region: "atlantis");

        await client.SearchAsync("Momo", null, CancellationToken.None);

        Assert.Equal("api.audible.de", handler.Requests.Single().Host);
    }

    /// <summary>
    /// An author spelled differently from the catalogue's turns an exact title
    /// into nothing. The title alone is tried before giving up.
    /// </summary>
    [Fact]
    public async Task Without_results_the_title_is_tried_alone()
    {
        var (client, handler) = Client(r =>
            Json(r.RequestUri!.Query.Contains("author=") ? NoProducts : OneProduct));

        var results = await client.SearchAsync("Momo", "Ende, M.", CancellationToken.None);

        Assert.Single(results);
        Assert.Equal(2, handler.Requests.Count);
        Assert.DoesNotContain("author=", handler.Requests[1].Query);
    }

    [Fact]
    public async Task Nothing_to_search_for_asks_nobody()
    {
        var (client, handler) = Client(_ => Json(OneProduct));

        Assert.Empty(await client.SearchAsync(" ", null, CancellationToken.None));
        Assert.Empty(handler.Requests);
    }

    [Fact]
    public async Task A_failing_catalogue_is_reported_as_unavailable()
    {
        var (client, _) = Client(_ => Json("{}", HttpStatusCode.ServiceUnavailable));

        await Assert.ThrowsAsync<CatalogUnavailableException>(
            () => client.SearchAsync("Momo", null, CancellationToken.None));
    }

    [Fact]
    public async Task An_unreachable_catalogue_is_reported_as_unavailable()
    {
        var (client, _) = Client(_ => throw new HttpRequestException("no route"));

        await Assert.ThrowsAsync<CatalogUnavailableException>(
            () => client.SearchAsync("Momo", null, CancellationToken.None));
    }

    [Fact]
    public async Task An_unreadable_answer_is_reported_as_unavailable()
    {
        var (client, _) = Client(_ => Json("<html>Wartung</html>"));

        await Assert.ThrowsAsync<CatalogUnavailableException>(
            () => client.SearchAsync("Momo", null, CancellationToken.None));
    }

    // --- one record -----------------------------------------------------------

    [Fact]
    public async Task Asks_Audnexus_for_a_record_in_the_region()
    {
        var (client, handler) = Client(_ => Json("""{ "asin": "B004V0YKT2", "title": "Momo" }"""));

        var book = await client.GetBookAsync("B004V0YKT2", CancellationToken.None);

        Assert.Equal("Momo", book!.Title);
        var request = handler.Requests.Single();
        Assert.Equal("api.audnex.us", request.Host);
        Assert.Equal("/books/B004V0YKT2", request.AbsolutePath);
        Assert.Equal("?region=de", request.Query);
    }

    [Theory]
    [InlineData(HttpStatusCode.NotFound)]
    [InlineData(HttpStatusCode.BadRequest)]
    public async Task An_unknown_ASIN_is_no_record(HttpStatusCode status)
    {
        var (client, _) = Client(_ => Json("""{ "error": "Not Found" }""", status));

        Assert.Null(await client.GetBookAsync("B000000000", CancellationToken.None));
    }

    [Fact]
    public async Task A_failing_Audnexus_is_reported_as_unavailable()
    {
        var (client, _) = Client(_ => Json("{}", HttpStatusCode.InternalServerError));

        await Assert.ThrowsAsync<CatalogUnavailableException>(
            () => client.GetBookAsync("B004V0YKT2", CancellationToken.None));
    }

    // --- covers ---------------------------------------------------------------

    private static HttpResponseMessage Image(string mime, int size = 64) =>
        new(HttpStatusCode.OK) { Content = new ByteArrayContent(new byte[size]) { Headers = { { "Content-Type", mime } } } };

    [Fact]
    public async Task Downloads_a_cover()
    {
        var (client, _) = Client(_ => Image("image/jpeg"));

        var image = await client.DownloadImageAsync("https://m.media-amazon.com/images/I/81abc.jpg", CancellationToken.None);

        Assert.Equal("image/jpeg", image!.Value.MimeType);
        Assert.Equal(64, image.Value.Data.Length);
    }

    [Fact]
    public async Task Something_that_is_not_an_image_is_no_cover()
    {
        var (client, _) = Client(_ => Json("{}"));

        Assert.Null(await client.DownloadImageAsync("https://m.media-amazon.com/x.jpg", CancellationToken.None));
    }

    [Fact]
    public async Task Only_https_covers_are_fetched()
    {
        var (client, handler) = Client(_ => Image("image/jpeg"));

        Assert.Null(await client.DownloadImageAsync("http://example.org/x.jpg", CancellationToken.None));
        Assert.Null(await client.DownloadImageAsync("file:///etc/passwd", CancellationToken.None));
        Assert.Empty(handler.Requests);
    }

    [Fact]
    public async Task A_failed_cover_download_is_no_cover_rather_than_an_error()
    {
        var (client, _) = Client(_ => throw new HttpRequestException("reset"));

        Assert.Null(await client.DownloadImageAsync("https://m.media-amazon.com/x.jpg", CancellationToken.None));
    }
}
