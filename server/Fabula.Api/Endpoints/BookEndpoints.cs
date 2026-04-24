using Fabula.Api.Infrastructure;
using Fabula.Core.Services;
using Fabula.Data;
using Microsoft.EntityFrameworkCore;

namespace Fabula.Api.Endpoints;

public static class BookEndpoints
{
    // Temporary single-user id until JWT auth lands -- mirrors ProgressEndpoints.
    private const int TemporaryUserId = 1;

    public static IEndpointRouteBuilder MapBookEndpoints(this IEndpointRouteBuilder app)
    {
        var group = app.MapGroup("/api/books").WithTags("Books");

        group.MapGet("/", async (FabulaDbContext db, string? search, int page, int pageSize, CancellationToken ct) =>
        {
            page = page <= 0 ? 1 : page;
            pageSize = pageSize is <= 0 or > 200 ? 50 : pageSize;

            var query = db.Books
                .AsSplitQuery()
                .Include(b => b.Authors)
                .Include(b => b.Narrators)
                .Include(b => b.Series)
                .AsNoTracking();

            if (!string.IsNullOrWhiteSpace(search))
            {
                var s = search.Trim();
                query = query.Where(b =>
                    EF.Functions.Like(b.Title, $"%{s}%") ||
                    b.Authors.Any(a => EF.Functions.Like(a.Name, $"%{s}%")));
            }

            var total = await query.CountAsync(ct);

            var books = await query
                .OrderBy(b => b.SortTitle ?? b.Title)
                .Skip((page - 1) * pageSize)
                .Take(pageSize)
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

            return Results.Ok(new PagedResult<BookSummaryDto>(books, total, page, pageSize));
        });

        group.MapGet("/{id:int}", async (int id, FabulaDbContext db, CancellationToken ct) =>
        {
            var book = await db.Books
                .AsSplitQuery()
                .Include(b => b.Authors)
                .Include(b => b.Narrators)
                .Include(b => b.Series)
                .Include(b => b.Chapters.OrderBy(c => c.Index))
                .Include(b => b.Files.OrderBy(f => f.TrackIndex))
                .AsNoTracking()
                .FirstOrDefaultAsync(b => b.Id == id, ct);

            if (book is null) return Results.NotFound();

            var progress = await db.PlaybackProgress
                .AsNoTracking()
                .Where(p => p.UserId == TemporaryUserId && p.BookId == id)
                .Select(p => new ProgressSummaryDto(p.Position, p.Finished))
                .FirstOrDefaultAsync(ct);

            return Results.Ok(new BookDetailDto(
                book.Id,
                book.Title,
                book.Subtitle,
                book.Description,
                book.Authors.Select(a => a.Name).ToList(),
                book.Narrators.Select(n => n.Name).ToList(),
                book.SeriesId,
                book.Series?.Name,
                book.SeriesPosition,
                book.Language,
                book.Publisher,
                book.PublishYear,
                book.Isbn,
                book.Asin,
                book.Duration,
                book.CoverPath != null ? $"/api/books/{book.Id}/cover" : null,
                progress,
                book.Chapters.Select(c => new ChapterDto(c.Index, c.Title, c.Start, c.End)).ToList(),
                book.Files.Select(f => new AudioFileDto(f.Id, f.TrackIndex, f.Duration, f.OffsetInBook)).ToList()));
        });

        group.MapGet("/{id:int}/cover", async (int id, FabulaDbContext db, ICoverStore store, CancellationToken ct) =>
        {
            var book = await db.Books.AsNoTracking().FirstOrDefaultAsync(b => b.Id == id, ct);
            if (book?.CoverPath is null) return Results.NotFound();
            var path = store.GetCoverFilePath(book.CoverPath);
            if (!File.Exists(path)) return Results.NotFound();
            var ext = Path.GetExtension(path).ToLowerInvariant();
            var mime = ext switch
            {
                ".png" => "image/png",
                ".webp" => "image/webp",
                ".gif" => "image/gif",
                _ => "image/jpeg"
            };
            return Results.File(path, mime);
        });

        return app;
    }
}

public record BookSummaryDto(
    int Id,
    string Title,
    string? Subtitle,
    List<string> Authors,
    List<string> Narrators,
    int? SeriesId,
    string? Series,
    decimal? SeriesPosition,
    TimeSpan Duration,
    string? CoverUrl,
    ProgressSummaryDto? Progress);

public record ProgressSummaryDto(TimeSpan Position, bool Finished);

public record BookDetailDto(
    int Id,
    string Title,
    string? Subtitle,
    string? Description,
    List<string> Authors,
    List<string> Narrators,
    int? SeriesId,
    string? Series,
    decimal? SeriesPosition,
    string? Language,
    string? Publisher,
    int? PublishYear,
    string? Isbn,
    string? Asin,
    TimeSpan Duration,
    string? CoverUrl,
    ProgressSummaryDto? Progress,
    List<ChapterDto> Chapters,
    List<AudioFileDto> Files);

public record ChapterDto(int Index, string Title, TimeSpan Start, TimeSpan End);
public record AudioFileDto(int Id, int TrackIndex, TimeSpan Duration, TimeSpan OffsetInBook);
public record PagedResult<T>(IReadOnlyList<T> Items, int Total, int Page, int PageSize);
