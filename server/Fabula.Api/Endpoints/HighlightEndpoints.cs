using Fabula.Api.Infrastructure;
using Fabula.Core.Domain;
using Fabula.Data;
using Microsoft.EntityFrameworkCore;

namespace Fabula.Api.Endpoints;

public static class HighlightEndpoints
{
    public static IEndpointRouteBuilder MapHighlightEndpoints(this IEndpointRouteBuilder app)
    {
        app.MapGet("/api/books/{bookId:int}/highlights", async (
            int bookId,
            HttpContext http,
            FabulaDbContext db,
            CancellationToken ct) =>
        {
            var uid = http.UserId();
            // SQLite cannot ORDER BY a TimeSpan column; sort in memory.
            var items = await db.Highlights
                .AsNoTracking()
                .Where(h => h.UserId == uid && h.BookId == bookId)
                .ToListAsync(ct);
            var result = items
                .OrderBy(h => h.Start)
                .Select(h => new HighlightDto(h.Id, h.BookId, h.Start, h.End, h.Title, h.Note, h.CreatedAt))
                .ToList();
            return Results.Ok(result);
        }).RequireAuthorization().WithTags("Highlights");

        app.MapPost("/api/books/{bookId:int}/highlights", async (
            int bookId,
            CreateHighlightRequest req,
            HttpContext http,
            FabulaDbContext db,
            CancellationToken ct) =>
        {
            var bookExists = await db.Books.AnyAsync(b => b.Id == bookId, ct);
            if (!bookExists) return Results.NotFound();

            // Tolerate a reversed range (end captured before start) by ordering
            // the two bounds, and clamp negatives to zero.
            var start = req.Start < TimeSpan.Zero ? TimeSpan.Zero : req.Start;
            var end = req.End < TimeSpan.Zero ? TimeSpan.Zero : req.End;
            if (end < start) (start, end) = (end, start);

            var highlight = new Highlight
            {
                UserId = http.UserId(),
                BookId = bookId,
                Start = start,
                End = end,
                Title = Clean(req.Title),
                Note = Clean(req.Note)
            };
            db.Highlights.Add(highlight);
            await db.SaveChangesAsync(ct);

            return Results.Created(
                $"/api/highlights/{highlight.Id}",
                new HighlightDto(highlight.Id, highlight.BookId, highlight.Start, highlight.End, highlight.Title, highlight.Note, highlight.CreatedAt));
        }).RequireAuthorization().WithTags("Highlights");

        app.MapPatch("/api/highlights/{id:int}", async (
            int id,
            UpdateHighlightRequest req,
            HttpContext http,
            FabulaDbContext db,
            CancellationToken ct) =>
        {
            var uid = http.UserId();
            var highlight = await db.Highlights.FirstOrDefaultAsync(
                h => h.Id == id && h.UserId == uid, ct);
            if (highlight is null) return Results.NotFound();

            highlight.Title = Clean(req.Title);
            highlight.Note = Clean(req.Note);
            await db.SaveChangesAsync(ct);

            return Results.Ok(new HighlightDto(highlight.Id, highlight.BookId, highlight.Start, highlight.End, highlight.Title, highlight.Note, highlight.CreatedAt));
        }).RequireAuthorization().WithTags("Highlights");

        app.MapDelete("/api/highlights/{id:int}", async (
            int id,
            HttpContext http,
            FabulaDbContext db,
            CancellationToken ct) =>
        {
            var uid = http.UserId();
            var highlight = await db.Highlights.FirstOrDefaultAsync(
                h => h.Id == id && h.UserId == uid, ct);
            if (highlight is null) return Results.NotFound();

            db.Highlights.Remove(highlight);
            await db.SaveChangesAsync(ct);
            return Results.NoContent();
        }).RequireAuthorization().WithTags("Highlights");

        return app;
    }

    private static string? Clean(string? value)
    {
        var trimmed = value?.Trim();
        return string.IsNullOrEmpty(trimmed) ? null : trimmed;
    }
}

public record HighlightDto(int Id, int BookId, TimeSpan Start, TimeSpan End, string? Title, string? Note, DateTime CreatedAt);
public record CreateHighlightRequest(TimeSpan Start, TimeSpan End, string? Title, string? Note);
public record UpdateHighlightRequest(string? Title, string? Note);
