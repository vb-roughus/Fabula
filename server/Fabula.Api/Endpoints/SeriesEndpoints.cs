using Fabula.Api.Infrastructure;
using Fabula.Core.Domain;
using Fabula.Core.Services;
using Fabula.Data;
using Microsoft.EntityFrameworkCore;

namespace Fabula.Api.Endpoints;

public static class SeriesEndpoints
{
    public static IEndpointRouteBuilder MapSeriesEndpoints(this IEndpointRouteBuilder app)
    {
        var group = app.MapGroup("/api/series").WithTags("Series").RequireAuthorization();

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

        group.MapGet("/{id:int}", async (int id, HttpContext http, FabulaDbContext db, CancellationToken ct) =>
        {
            var uid = http.UserId();
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
                        .Where(p => p.UserId == uid && p.BookId == b.Id)
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

        // Re-derives SeriesPosition for every book in the series from the
        // current folder name. Cheaper than a full library scan because we
        // never touch the audio files -- the folder paths are already in the
        // DB. Manual overrides (SeriesPositionManuallySet) are preserved.
        group.MapPost("/{id:int}/reorder", async (int id, FabulaDbContext db, CancellationToken ct) =>
        {
            if (!await db.Series.AnyAsync(s => s.Id == id, ct))
                return Results.NotFound();

            var books = await db.Books
                .Include(b => b.Files)
                .Where(b => b.SeriesId == id)
                .ToListAsync(ct);

            var derived = new Dictionary<int, decimal?>(books.Count);
            foreach (var b in books)
            {
                if (b.SeriesPositionManuallySet)
                    continue;

                var anyFile = b.Files.OrderBy(f => f.TrackIndex).FirstOrDefault();
                var bookDir = anyFile is null ? null : Path.GetDirectoryName(anyFile.Path);
                derived[b.Id] = LibraryScanner.ExtractPositionFromName(
                    bookDir is null ? null : Path.GetFileName(bookDir));
            }

            // Fill gaps deterministically: books without a derived position
            // (and without a manual override) get the next free integer,
            // ordered alphabetically by sort title for stability.
            var taken = new HashSet<decimal>(
                books
                    .Where(b => b.SeriesPositionManuallySet && b.SeriesPosition is not null)
                    .Select(b => b.SeriesPosition!.Value)
                    .Concat(derived.Values.Where(p => p is not null).Select(p => p!.Value)));

            var next = taken.Count == 0 ? 1m : Math.Floor(taken.Max()) + 1m;
            var fillers = books
                .Where(b => !b.SeriesPositionManuallySet && derived[b.Id] is null)
                .OrderBy(b => b.SortTitle ?? b.Title, StringComparer.OrdinalIgnoreCase);
            foreach (var b in fillers)
            {
                while (taken.Contains(next)) next += 1m;
                derived[b.Id] = next;
                taken.Add(next);
                next += 1m;
            }

            int updated = 0;
            foreach (var b in books)
            {
                if (b.SeriesPositionManuallySet) continue;
                var newPos = derived[b.Id];
                if (b.SeriesPosition != newPos)
                {
                    b.SeriesPosition = newPos;
                    updated++;
                }
            }

            if (updated > 0)
                await db.SaveChangesAsync(ct);

            return Results.Ok(new { updated });
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
