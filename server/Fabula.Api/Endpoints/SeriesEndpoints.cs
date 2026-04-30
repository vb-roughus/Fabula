using Fabula.Core.Domain;
using Fabula.Data;
using Microsoft.EntityFrameworkCore;

namespace Fabula.Api.Endpoints;

public static class SeriesEndpoints
{
    // Temporary single-user id until JWT auth lands -- mirrors ProgressEndpoints.
    private const int TemporaryUserId = 1;

    public static IEndpointRouteBuilder MapSeriesEndpoints(this IEndpointRouteBuilder app)
    {
        var group = app.MapGroup("/api/series").WithTags("Series");

        group.MapGet("/", async (FabulaDbContext db, CancellationToken ct) =>
        {
            // SQLite stores decimal as TEXT, so ORDER BY SeriesPosition would
            // sort lexicographically ("10" < "2"). We materialise the candidate
            // covers and pick the lowest position client-side instead.
            var seriesRows = await db.Series
                .AsNoTracking()
                .OrderBy(s => s.Name)
                .Select(s => new
                {
                    s.Id,
                    s.Name,
                    s.Description,
                    BookCount = s.Books.Count,
                    Covers = db.Books
                        .Where(b => b.SeriesId == s.Id && b.CoverPath != null)
                        .Select(b => new { b.Id, b.SeriesPosition })
                        .ToList()
                })
                .ToListAsync(ct);

            return seriesRows
                .Select(s => new SeriesSummaryDto(
                    s.Id,
                    s.Name,
                    s.Description,
                    s.BookCount,
                    s.Covers
                        .OrderBy(c => c.SeriesPosition ?? decimal.MaxValue)
                        .Select(c => (string?)$"/api/books/{c.Id}/cover")
                        .FirstOrDefault()))
                .ToList();
        });

        group.MapGet("/{id:int}", async (int id, FabulaDbContext db, CancellationToken ct) =>
        {
            var series = await db.Series
                .AsNoTracking()
                .Where(s => s.Id == id)
                .Select(s => new { s.Id, s.Name, s.Description })
                .FirstOrDefaultAsync(ct);

            if (series is null) return Results.NotFound();

            // Materialise first, sort in memory: SQLite stores decimal as TEXT,
            // so ORDER BY would sort positions lexicographically ("10" < "2").
            var rows = await db.Books
                .AsNoTracking()
                .Where(b => b.SeriesId == id)
                .Select(b => new
                {
                    b.Id,
                    b.Title,
                    Authors = b.Authors.Select(a => a.Name).ToList(),
                    b.SeriesPosition,
                    HasCover = b.CoverPath != null,
                    b.Duration,
                    Progress = db.PlaybackProgress
                        .Where(p => p.UserId == TemporaryUserId && p.BookId == b.Id)
                        .Select(p => new ProgressSummaryDto(p.Position, p.Finished, p.UpdatedAt))
                        .FirstOrDefault(),
                    SortKey = b.SortTitle ?? b.Title
                })
                .ToListAsync(ct);

            var books = rows
                .OrderBy(b => b.SeriesPosition ?? decimal.MaxValue)
                .ThenBy(b => b.SortKey, StringComparer.OrdinalIgnoreCase)
                .Select(b => new SeriesBookDto(
                    b.Id,
                    b.Title,
                    b.Authors,
                    b.SeriesPosition,
                    b.HasCover ? $"/api/books/{b.Id}/cover" : null,
                    b.Duration,
                    b.Progress))
                .ToList();

            return Results.Ok(new SeriesDetailDto(series.Id, series.Name, series.Description, books));
        });

        group.MapPost("/", async (FabulaDbContext db, SeriesRequest req, CancellationToken ct) =>
        {
            var name = req.Name?.Trim();
            if (string.IsNullOrWhiteSpace(name))
                return Results.BadRequest(new { error = "Name is required." });

            if (await db.Series.AnyAsync(s => s.Name == name, ct))
                return Results.Conflict(new { error = $"A series named \"{name}\" already exists." });

            var series = new Series { Name = name, Description = req.Description?.Trim() };
            db.Series.Add(series);
            await db.SaveChangesAsync(ct);
            return Results.Created($"/api/series/{series.Id}",
                new SeriesSummaryDto(series.Id, series.Name, series.Description, 0, null));
        });

        group.MapPut("/{id:int}", async (int id, FabulaDbContext db, SeriesRequest req, CancellationToken ct) =>
        {
            var series = await db.Series.FindAsync([id], ct);
            if (series is null) return Results.NotFound();

            var name = req.Name?.Trim();
            if (string.IsNullOrWhiteSpace(name))
                return Results.BadRequest(new { error = "Name is required." });

            if (await db.Series.AnyAsync(s => s.Name == name && s.Id != id, ct))
                return Results.Conflict(new { error = $"A series named \"{name}\" already exists." });

            series.Name = name;
            series.Description = req.Description?.Trim();
            await db.SaveChangesAsync(ct);

            var count = await db.Books.CountAsync(b => b.SeriesId == id, ct);
            // Decimal-as-TEXT sort would be lexicographic; pick lowest position client-side.
            var covers = await db.Books
                .Where(b => b.SeriesId == id && b.CoverPath != null)
                .Select(b => new { b.Id, b.SeriesPosition })
                .ToListAsync(ct);
            var coverUrl = covers
                .OrderBy(c => c.SeriesPosition ?? decimal.MaxValue)
                .Select(c => (string?)$"/api/books/{c.Id}/cover")
                .FirstOrDefault();
            return Results.Ok(new SeriesSummaryDto(series.Id, series.Name, series.Description, count, coverUrl));
        });

        group.MapDelete("/{id:int}", async (int id, FabulaDbContext db, CancellationToken ct) =>
        {
            var series = await db.Series.FindAsync([id], ct);
            if (series is null) return Results.NotFound();
            db.Series.Remove(series);
            await db.SaveChangesAsync(ct);
            return Results.NoContent();
        });

        return app;
    }
}

public record SeriesSummaryDto(int Id, string Name, string? Description, int BookCount, string? CoverUrl);

public record SeriesDetailDto(int Id, string Name, string? Description, List<SeriesBookDto> Books);

public record SeriesBookDto(
    int Id,
    string Title,
    List<string> Authors,
    decimal? Position,
    string? CoverUrl,
    TimeSpan Duration,
    ProgressSummaryDto? Progress);

public record SeriesRequest(string? Name, string? Description);
