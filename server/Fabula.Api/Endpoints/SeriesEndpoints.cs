using Fabula.Data;
using Microsoft.EntityFrameworkCore;

namespace Fabula.Api.Endpoints;

public static class SeriesEndpoints
{
    // Placeholder until JWT auth lands -- duplicated with BookEndpoints on purpose.
    private const int TemporaryUserId = 1;

    public static IEndpointRouteBuilder MapSeriesEndpoints(this IEndpointRouteBuilder app)
    {
        var group = app.MapGroup("/api/series").WithTags("Series");

        group.MapGet("/", async (FabulaDbContext db, CancellationToken ct) =>
        {
            var items = await db.Series
                .AsNoTracking()
                .OrderBy(s => s.Name)
                .Select(s => new SeriesSummaryDto(
                    s.Id,
                    s.Name,
                    db.Books.Count(b => b.SeriesId == s.Id),
                    db.Books
                        .Where(b => b.SeriesId == s.Id && b.CoverPath != null)
                        .OrderBy(b => b.SeriesPosition ?? 0m)
                        .Select(b => (string?)("/api/books/" + b.Id + "/cover"))
                        .FirstOrDefault()))
                .ToListAsync(ct);
            return Results.Ok(items);
        });

        group.MapGet("/{id:int}", async (int id, FabulaDbContext db, CancellationToken ct) =>
        {
            var series = await db.Series
                .AsNoTracking()
                .FirstOrDefaultAsync(s => s.Id == id, ct);
            if (series is null) return Results.NotFound();

            var books = await db.Books
                .AsSplitQuery()
                .Include(b => b.Authors)
                .Include(b => b.Narrators)
                .Include(b => b.Series)
                .AsNoTracking()
                .Where(b => b.SeriesId == id)
                .OrderBy(b => b.SeriesPosition ?? 0m)
                .ThenBy(b => b.SortTitle ?? b.Title)
                .Select(b => new BookSummaryDto(
                    b.Id,
                    b.Title,
                    b.Subtitle,
                    b.Authors.Select(a => a.Name).ToList(),
                    b.Narrators.Select(n => n.Name).ToList(),
                    b.SeriesId,
                    b.Series != null ? b.Series.Name : null,
                    b.SeriesPosition,
                    b.Duration,
                    b.CoverPath != null ? $"/api/books/{b.Id}/cover" : null,
                    db.PlaybackProgress
                        .Where(p => p.UserId == TemporaryUserId && p.BookId == b.Id)
                        .Select(p => new ProgressSummaryDto(p.Position, p.Finished))
                        .FirstOrDefault()))
                .ToListAsync(ct);

            return Results.Ok(new SeriesDetailDto(series.Id, series.Name, series.Description, books));
        });

        return app;
    }
}

public record SeriesSummaryDto(int Id, string Name, int BookCount, string? CoverUrl);
public record SeriesDetailDto(int Id, string Name, string? Description, List<BookSummaryDto> Books);
