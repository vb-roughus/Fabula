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
            await db.Series
                .AsNoTracking()
                .OrderBy(s => s.Name)
                .Select(s => new SeriesSummaryDto(
                    s.Id,
                    s.Name,
                    s.Description,
                    s.Books.Count,
                    db.Books
                        .Where(b => b.SeriesId == s.Id && b.CoverPath != null)
                        .OrderBy(b => b.SeriesPosition ?? 0m)
                        .Select(b => (string?)("/api/books/" + b.Id + "/cover"))
                        .FirstOrDefault()))
                .ToListAsync(ct));

        group.MapGet("/{id:int}", async (int id, FabulaDbContext db, CancellationToken ct) =>
        {
            var series = await db.Series
                .AsNoTracking()
                .Where(s => s.Id == id)
                .Select(s => new { s.Id, s.Name, s.Description })
                .FirstOrDefaultAsync(ct);

            if (series is null) return Results.NotFound();

            var books = await db.Books
                .AsNoTracking()
                .Where(b => b.SeriesId == id)
                .OrderBy(b => b.SeriesPosition ?? decimal.MaxValue)
                .ThenBy(b => b.SortTitle ?? b.Title)
                .Select(b => new SeriesBookDto(
                    b.Id,
                    b.Title,
                    b.Authors.Select(a => a.Name).ToList(),
                    b.SeriesPosition,
                    b.CoverPath != null ? $"/api/books/{b.Id}/cover" : null,
                    b.Duration,
                    db.PlaybackProgress
                        .Where(p => p.UserId == TemporaryUserId && p.BookId == b.Id)
                        .Select(p => new ProgressSummaryDto(p.Position, p.Finished, p.UpdatedAt))
                        .FirstOrDefault()))
                .ToListAsync(ct);

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
            var coverUrl = await db.Books
                .Where(b => b.SeriesId == id && b.CoverPath != null)
                .OrderBy(b => b.SeriesPosition ?? 0m)
                .Select(b => (string?)("/api/books/" + b.Id + "/cover"))
                .FirstOrDefaultAsync(ct);
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
