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

            var uid = http.UserId();
            var p = await db.PlaybackProgress
                .FirstOrDefaultAsync(x => x.UserId == uid && x.BookId == bookId, ct);

            if (p is null)
            {
                p = new PlaybackProgress
                {
                    UserId = uid,
                    BookId = bookId
                };
                db.PlaybackProgress.Add(p);
            }

            p.Position = req.Position;
            p.Finished = req.Finished;
            p.LastDevice = req.Device;
            p.UpdatedAt = DateTime.UtcNow;

            await db.SaveChangesAsync(ct);
            return Results.Ok(new ProgressDto(p.BookId, p.Position, p.Finished, p.UpdatedAt, p.LastDevice));
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

            var uid = http.UserId();
            var p = await db.PlaybackProgress
                .FirstOrDefaultAsync(x => x.UserId == uid && x.BookId == bookId, ct);
            if (p is null)
            {
                p = new PlaybackProgress { UserId = uid, BookId = bookId };
                db.PlaybackProgress.Add(p);
            }

            p.Finished = req.Finished;
            p.Position = req.Finished ? book.Duration : TimeSpan.Zero;
            p.LastDevice = req.Device;
            p.UpdatedAt = DateTime.UtcNow;

            await db.SaveChangesAsync(ct);
            return Results.Ok(new ProgressDto(p.BookId, p.Position, p.Finished, p.UpdatedAt, p.LastDevice));
        });

        return app;
    }
}

public record ProgressDto(int BookId, TimeSpan Position, bool Finished, DateTime? UpdatedAt, string? Device);
public record UpdateProgressRequest(TimeSpan Position, bool Finished, string? Device);
public record SetFinishedRequest(bool Finished, string? Device);
