using Fabula.Core.Domain;
using Fabula.Data;
using Microsoft.EntityFrameworkCore;

namespace Fabula.Api.Endpoints;

public static class BookmarkEndpoints
{
    // Temporary single-user id -- same convention as ProgressEndpoints.
    private const int TemporaryUserId = 1;

    public static IEndpointRouteBuilder MapBookmarkEndpoints(this IEndpointRouteBuilder app)
    {
        app.MapGet("/api/books/{bookId:int}/bookmarks", async (int bookId, FabulaDbContext db, CancellationToken ct) =>
        {
            // SQLite cannot ORDER BY a TimeSpan column; sort in memory.
            var items = await db.Bookmarks
                .AsNoTracking()
                .Where(b => b.UserId == TemporaryUserId && b.BookId == bookId)
                .ToListAsync(ct);
            var result = items
                .OrderBy(b => b.Position)
                .Select(b => new BookmarkDto(b.Id, b.BookId, b.Position, b.Note, b.CreatedAt))
                .ToList();
            return Results.Ok(result);
        }).WithTags("Bookmarks");

        app.MapPost("/api/books/{bookId:int}/bookmarks", async (
            int bookId,
            CreateBookmarkRequest req,
            FabulaDbContext db,
            CancellationToken ct) =>
        {
            var bookExists = await db.Books.AnyAsync(b => b.Id == bookId, ct);
            if (!bookExists) return Results.NotFound();

            await EnsureUserAsync(db, ct);

            // No explicit note? Stamp the bookmark with the local date and
            // time so the user always has a hint of when it was set.
            var trimmed = req.Note?.Trim();
            var note = string.IsNullOrEmpty(trimmed)
                ? DateTime.Now.ToString("dd.MM.yyyy, HH:mm")
                : trimmed;
            var bookmark = new Bookmark
            {
                UserId = TemporaryUserId,
                BookId = bookId,
                Position = req.Position,
                Note = note
            };
            db.Bookmarks.Add(bookmark);
            await db.SaveChangesAsync(ct);

            return Results.Created(
                $"/api/bookmarks/{bookmark.Id}",
                new BookmarkDto(bookmark.Id, bookmark.BookId, bookmark.Position, bookmark.Note, bookmark.CreatedAt));
        }).WithTags("Bookmarks");

        app.MapPatch("/api/bookmarks/{id:int}", async (
            int id,
            UpdateBookmarkRequest req,
            FabulaDbContext db,
            CancellationToken ct) =>
        {
            var bookmark = await db.Bookmarks.FirstOrDefaultAsync(
                b => b.Id == id && b.UserId == TemporaryUserId, ct);
            if (bookmark is null) return Results.NotFound();

            bookmark.Note = string.IsNullOrWhiteSpace(req.Note) ? null : req.Note.Trim();
            await db.SaveChangesAsync(ct);

            return Results.Ok(new BookmarkDto(bookmark.Id, bookmark.BookId, bookmark.Position, bookmark.Note, bookmark.CreatedAt));
        }).WithTags("Bookmarks");

        app.MapDelete("/api/bookmarks/{id:int}", async (int id, FabulaDbContext db, CancellationToken ct) =>
        {
            var bookmark = await db.Bookmarks.FirstOrDefaultAsync(
                b => b.Id == id && b.UserId == TemporaryUserId, ct);
            if (bookmark is null) return Results.NotFound();

            db.Bookmarks.Remove(bookmark);
            await db.SaveChangesAsync(ct);
            return Results.NoContent();
        }).WithTags("Bookmarks");

        return app;
    }

    private static async Task EnsureUserAsync(FabulaDbContext db, CancellationToken ct)
    {
        if (await db.Users.AnyAsync(u => u.Id == TemporaryUserId, ct)) return;
        db.Users.Add(new User
        {
            Id = TemporaryUserId,
            Username = "local",
            PasswordHash = string.Empty,
            IsAdmin = true
        });
        await db.SaveChangesAsync(ct);
    }
}

public record BookmarkDto(int Id, int BookId, TimeSpan Position, string? Note, DateTime CreatedAt);
public record CreateBookmarkRequest(TimeSpan Position, string? Note);
public record UpdateBookmarkRequest(string? Note);
