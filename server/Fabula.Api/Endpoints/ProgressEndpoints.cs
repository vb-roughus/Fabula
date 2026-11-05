using Fabula.Api.Infrastructure;
using Fabula.Core.Domain;
using Fabula.Data;
using Microsoft.EntityFrameworkCore;

namespace Fabula.Api.Endpoints;

public static class ProgressEndpoints
{
    public static IEndpointRouteBuilder MapProgressEndpoints(this IEndpointRouteBuilder app)
    {
        var group = app.MapGroup("/api/progress").WithTags("Progress").RequireAuthorization();

        group.MapGet("/in-progress", async (HttpContext http, FabulaDbContext db, CancellationToken ct) =>
        {
            var uid = http.UserId();
            var threshold = TimeSpan.FromSeconds(5);

            // Position is a TimeSpan stored as TEXT in SQLite, so a `> threshold`
            // comparison can't be translated to SQL. Filter unfinished rows in
            // the DB (Finished/UpdatedAt translate fine), then apply the
            // position threshold in memory. The number of in-progress rows per
            // user is small, so loading them all is cheap.
            var bookIds = (await db.PlaybackProgress
                    .Where(p => p.UserId == uid && !p.Finished)
                    .OrderByDescending(p => p.UpdatedAt)
                    .Select(p => new { p.BookId, p.Position })
                    .ToListAsync(ct))
                .Where(p => p.Position > threshold)
                .Take(20)
                .Select(p => p.BookId)
                .ToList();

            if (bookIds.Count == 0) return Results.Ok(Array.Empty<BookSummaryDto>());

            var books = await db.Books
                .AsNoTracking()
                .Where(b => bookIds.Contains(b.Id))
                .SelectSummary(db, uid)
                .ToListAsync(ct);

            var order = bookIds.Select((id, i) => (id, i)).ToDictionary(x => x.id, x => x.i);
            return Results.Ok(books.OrderBy(b => order.GetValueOrDefault(b.Id, int.MaxValue)).ToList());
        });

        group.MapGet("/{bookId:int}", async (int bookId, HttpContext http, FabulaDbContext db, CancellationToken ct) =>
        {
            var uid = http.UserId();
            var p = await db.PlaybackProgress
                .AsNoTracking()
                .FirstOrDefaultAsync(x => x.UserId == uid && x.BookId == bookId, ct);
            if (p is null) return Results.Ok(new ProgressDto(bookId, TimeSpan.Zero, false, null, null));
            return Results.Ok(new ProgressDto(p.BookId, p.Position, p.Finished, p.UpdatedAt, p.LastDevice));
        });

        group.MapPut("/{bookId:int}", async (
            int bookId,
            UpdateProgressRequest req,
            HttpContext http,
            FabulaDbContext db,
            CancellationToken ct) =>
        {
            var bookExists = await db.Books.AnyAsync(b => b.Id == bookId, ct);
            if (!bookExists) return Results.NotFound();

            // An update recorded before what is stored -- a phone handing over
            // offline listening late -- is turned away, and the stored, newer
            // progress is what comes back.
            var (p, accepted) = await ProgressUpdates.ApplyAsync(
                db, http.UserId(), bookId, req.Position, req.Finished, req.Device,
                req.RecordedAt, DateTime.UtcNow, ct);
            return Results.Ok(new ProgressDto(p.BookId, p.Position, p.Finished, p.UpdatedAt, p.LastDevice, accepted));
        });

        // Convenience endpoint for "mark as already heard" / "mark as
        // unheard" in the clients. Sets the position to the book's full
        // duration when finishing, or back to zero when un-finishing, so the
        // progress bar in the library reflects the new state.
        group.MapPost("/{bookId:int}/finished", async (
            int bookId,
            SetFinishedRequest req,
            HttpContext http,
            FabulaDbContext db,
            CancellationToken ct) =>
        {
            var book = await db.Books.AsNoTracking().FirstOrDefaultAsync(b => b.Id == bookId, ct);
            if (book is null) return Results.NotFound();

            var (p, accepted) = await ProgressUpdates.ApplyAsync(
                db, http.UserId(), bookId,
                req.Finished ? book.Duration : TimeSpan.Zero, req.Finished, req.Device,
                req.RecordedAt, DateTime.UtcNow, ct);
            return Results.Ok(new ProgressDto(p.BookId, p.Position, p.Finished, p.UpdatedAt, p.LastDevice, accepted));
        });

        return app;
    }
}

/// <summary><c>Accepted</c> is false when the update was older than what is stored; the stored progress is returned then.</summary>
public record ProgressDto(
    int BookId, TimeSpan Position, bool Finished, DateTime? UpdatedAt, string? Device, bool Accepted = true);

/// <summary><c>RecordedAt</c>: when the device recorded the position. Optional; without it, the arrival counts.</summary>
public record UpdateProgressRequest(TimeSpan Position, bool Finished, string? Device, DateTime? RecordedAt = null);

public record SetFinishedRequest(bool Finished, string? Device, DateTime? RecordedAt = null);
