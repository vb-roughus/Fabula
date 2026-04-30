using Fabula.Api.Infrastructure;
using Fabula.Core.Domain;
using Fabula.Data;
using Microsoft.EntityFrameworkCore;

namespace Fabula.Api.Endpoints;

public static class BookmarkEndpoints
{
    public static IEndpointRouteBuilder MapBookmarkEndpoints(this IEndpointRouteBuilder app)
    {
        app.MapGet("/api/books/{bookId:int}/bookmarks", async (
            int bookId,
            HttpContext http,
            FabulaDbContext db,
            CancellationToken ct) =>
        {
            var uid = http.UserId();
            // SQLite cannot ORDER BY a TimeSpan column; sort in memory.
            var items = await db.Bookmarks
                .AsNoTracking()
                .Where(b => b.UserId == uid && b.BookId == bookId)
                .ToListAsync(ct);
            var result = items
                .OrderBy(b => b.Position)
                .Select(b => new BookmarkDto(b.Id, b.BookId, b.Position, b.Note, b.CreatedAt))
                .ToList();
            return Results.Ok(result);
        }).RequireAuthorization().WithTags("Bookmarks");

        app.MapPost("/api/books/{bookId:int}/bookmarks", async (
            int bookId,
            CreateBookmarkRequest req,
            HttpContext http,
            FabulaDbContext db,
            CancellationToken ct) =>
        {
            var bookExists = await db.Books.AnyAsync(b => b.Id == bookId, ct);
            if (!bookExists) return Results.NotFound();

            // No explicit note? Stamp the bookmark with the local date and
            // time so the user always has a hint of when it was set.
            var trimmed = req.Note?.Trim();
            var note = string.IsNullOrEmpty(trimmed)
                ? DateTime.Now.ToString("dd.MM.yyyy, HH:mm")
                : trimmed;
            var bookmark = new Bookmark
            {
                UserId = http.UserId(),
                BookId = bookId,
                Position = req.Position,
                Note = note
            };
            db.Bookmarks.Add(bookmark);
            await db.SaveChangesAsync(ct);

            return Results.Created(
                $"/api/bookmarks/{bookmark.Id}",
                new BookmarkDto(bookmark.Id, bookmark.BookId, bookmark.Position, bookmark.Note, bookmark.CreatedAt));
        }).RequireAuthorization().WithTags("Bookmarks");

        app.MapPatch("/api/bookmarks/{id:int}", async (
            int id,
            UpdateBookmarkRequest req,
            HttpContext http,
            FabulaDbContext db,
            CancellationToken ct) =>
        {
            var uid = http.UserId();
            var bookmark = await db.Bookmarks.FirstOrDefaultAsync(
                b => b.Id == id && b.UserId == uid, ct);
            if (bookmark is null) return Results.NotFound();

            bookmark.Note = string.IsNullOrWhiteSpace(req.Note) ? null : req.Note.Trim();
            await db.SaveChangesAsync(ct);

            return Results.Ok(new BookmarkDto(bookmark.Id, bookmark.BookId, bookmark.Position, bookmark.Note, bookmark.CreatedAt));
        }).RequireAuthorization().WithTags("Bookmarks");

        app.MapDelete("/api/bookmarks/{id:int}", async (
            int id,
            HttpContext http,
            FabulaDbContext db,
            CancellationToken ct) =>
        {
            var uid = http.UserId();
            var bookmark = await db.Bookmarks.FirstOrDefaultAsync(
                b => b.Id == id && b.UserId == uid, ct);
            if (bookmark is null) return Results.NotFound();

            db.Bookmarks.Remove(bookmark);
            await db.SaveChangesAsync(ct);
            return Results.NoContent();
        }).RequireAuthorization().WithTags("Bookmarks");

        return app;
    }
}

public record BookmarkDto(int Id, int BookId, TimeSpan Position, string? Note, DateTime CreatedAt);
public record CreateBookmarkRequest(TimeSpan Position, string? Note);
public record UpdateBookmarkRequest(string? Note);
