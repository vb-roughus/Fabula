using System.Text.RegularExpressions;
using Fabula.Api.Infrastructure.Metadata;
using Fabula.Core.Services;
using Fabula.Data;
using Microsoft.EntityFrameworkCore;

namespace Fabula.Api.Endpoints;

/// <summary>
/// Looking a book up in Audible's catalogue and taking over what the admin
/// chooses. Admin-only: the result changes the book for every user.
///
/// Three steps, each its own request: search, then the comparison for one
/// result, then taking the ticked fields. Nothing is written before the last.
/// </summary>
public static class MetadataEndpoints
{
    private static readonly Regex AsinPattern = new("^[A-Za-z0-9]{10}$", RegexOptions.Compiled);

    public static IEndpointRouteBuilder MapMetadataEndpoints(this IEndpointRouteBuilder app)
    {
        var group = app.MapGroup("/api/books/{id:int}/metadata")
            .WithTags("Metadata")
            .RequireAuthorization("Admin");

        // Candidates for the book. An ASIN looks up exactly that record;
        // otherwise title and author are searched.
        group.MapGet("/search", async (
            int id, string? title, string? author, string? asin,
            FabulaDbContext db, AudibleCatalogClient catalog, CancellationToken ct) =>
        {
            var book = await db.Books.AsNoTracking()
                .Where(b => b.Id == id)
                .Select(b => new { b.Duration })
                .FirstOrDefaultAsync(ct);
            if (book is null) return Results.NotFound();

            var wantedAsin = asin?.Trim();
            if (string.IsNullOrEmpty(wantedAsin) && string.IsNullOrWhiteSpace(title) && string.IsNullOrWhiteSpace(author))
                return Results.BadRequest(new { error = "Titel, Autor oder ASIN angeben." });
            if (!string.IsNullOrEmpty(wantedAsin) && !AsinPattern.IsMatch(wantedAsin))
                return Results.BadRequest(new { error = "Eine ASIN hat zehn Buchstaben und Ziffern." });

            IReadOnlyList<CatalogBook> found;
            try
            {
                if (!string.IsNullOrEmpty(wantedAsin))
                {
                    var single = await catalog.GetBookAsync(wantedAsin.ToUpperInvariant(), ct);
                    found = single is null ? [] : [single];
                }
                else
                {
                    found = await catalog.SearchAsync(title, author, ct);
                }
            }
            catch (CatalogUnavailableException ex)
            {
                return Unavailable(ex);
            }

            var ranked = MetadataPreview.Rank(found, book.Duration);
            return Results.Ok(ranked.Select(c => new MetadataCandidateDto(
                c.Asin,
                c.Title,
                c.Subtitle,
                c.Authors.ToList(),
                c.Narrators.ToList(),
                MetadataPreview.SeriesLabel(c.SeriesName, c.SeriesPosition),
                c.RuntimeMinutes,
                c.Year,
                c.CoverUrl,
                MetadataPreview.RuntimeMatches(c.RuntimeMinutes, book.Duration))).ToList());
        });

        // What taking one record over would change, field by field.
        group.MapGet("/{asin}", async (
            int id, string asin,
            FabulaDbContext db, AudibleCatalogClient catalog, CancellationToken ct) =>
        {
            if (!AsinPattern.IsMatch(asin))
                return Results.BadRequest(new { error = "Eine ASIN hat zehn Buchstaben und Ziffern." });

            var book = await db.Books
                .AsNoTracking()
                .AsSplitQuery()
                .Include(b => b.Authors)
                .Include(b => b.Narrators)
                .Include(b => b.Series)
                .FirstOrDefaultAsync(b => b.Id == id, ct);
            if (book is null) return Results.NotFound();

            CatalogBook? record;
            try
            {
                record = await catalog.GetBookAsync(asin.ToUpperInvariant(), ct);
            }
            catch (CatalogUnavailableException ex)
            {
                return Unavailable(ex);
            }
            if (record is null)
                return Results.NotFound(new { error = "Zu dieser ASIN gibt es keinen Eintrag." });

            return Results.Ok(new MetadataPreviewDto(
                record.Asin,
                record.Title,
                record.CoverUrl,
                record.RuntimeMinutes,
                MetadataPreview.RuntimeMatches(record.RuntimeMinutes, book.Duration),
                MetadataPreview.Fields(book, record)
                    .Select(f => new MetadataFieldDto(f.Key, f.Label, f.Current, f.Proposed, f.Preselected))
                    .ToList()));
        });

        // Takes the chosen fields. The record is fetched again rather than
        // accepted from the client: what is written is what the catalogue says,
        // not whatever arrived in the request.
        group.MapPost("/{asin}", async (
            int id, string asin, ApplyMetadataRequest request,
            FabulaDbContext db, ILibraryRepository repository, AudibleCatalogClient catalog,
            ICoverStore covers, CancellationToken ct) =>
        {
            if (!AsinPattern.IsMatch(asin))
                return Results.BadRequest(new { error = "Eine ASIN hat zehn Buchstaben und Ziffern." });

            var fields = (request.Fields ?? [])
                .Select(f => f.Trim().ToLowerInvariant())
                .Where(f => MetadataLocks.All.Contains(f))
                .ToHashSet();
            if (fields.Count == 0)
                return Results.BadRequest(new { error = "Keine Felder ausgewählt." });

            if (!await db.Books.AnyAsync(b => b.Id == id, ct)) return Results.NotFound();

            CatalogBook? record;
            try
            {
                record = await catalog.GetBookAsync(asin.ToUpperInvariant(), ct);
            }
            catch (CatalogUnavailableException ex)
            {
                return Unavailable(ex);
            }
            if (record is null)
                return Results.NotFound(new { error = "Zu dieser ASIN gibt es keinen Eintrag." });

            // The cover is stored under its own key, so it gets its own file
            // name -- and with it a new URL that no client has cached.
            string? coverPath = null;
            if (fields.Contains(MetadataLocks.Cover) && record.CoverUrl is not null)
            {
                var image = await catalog.DownloadImageAsync(record.CoverUrl, ct);
                if (image is { } picture)
                    coverPath = await covers.SaveCoverAsync($"catalog:{id}:{record.Asin}", picture.Data, picture.MimeType, ct);
            }

            var update = new BookMetadataUpdate
            {
                Fields = fields,
                Title = record.Title,
                Subtitle = record.Subtitle,
                Description = record.Description,
                Authors = record.Authors,
                Narrators = record.Narrators,
                SeriesName = record.SeriesName,
                SeriesPosition = record.SeriesPosition,
                Publisher = record.Publisher,
                Year = record.Year,
                Language = record.Language,
                Isbn = record.Isbn,
                Asin = record.Asin,
                CoverPath = coverPath
            };

            var taken = await repository.ApplyMetadataAsync(id, update, ct);
            if (taken is null) return Results.NotFound();

            // A ticked field the record had nothing for, or a cover that could
            // not be downloaded, is reported rather than silently dropped.
            var skipped = fields.Except(taken).OrderBy(f => MetadataLocks.All.ToList().IndexOf(f)).ToList();
            return Results.Ok(new ApplyMetadataResultDto(taken.ToList(), skipped));
        });

        return app;
    }

    private static IResult Unavailable(CatalogUnavailableException ex) =>
        Results.Json(new { error = ex.Message }, statusCode: StatusCodes.Status502BadGateway);
}

public record MetadataCandidateDto(
    string Asin,
    string? Title,
    string? Subtitle,
    List<string> Authors,
    List<string> Narrators,
    string? Series,
    int? RuntimeMinutes,
    int? Year,
    string? CoverUrl,
    bool RuntimeMatches);

public record MetadataPreviewDto(
    string Asin,
    string? Title,
    string? CoverUrl,
    int? RuntimeMinutes,
    bool RuntimeMatches,
    List<MetadataFieldDto> Fields);

public record MetadataFieldDto(string Key, string Label, string? Current, string? Proposed, bool Preselected);

public record ApplyMetadataRequest(List<string>? Fields);

public record ApplyMetadataResultDto(List<string> Taken, List<string> Skipped);
