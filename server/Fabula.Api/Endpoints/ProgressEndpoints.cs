using Fabula.Core.Domain;
using Fabula.Data;
using Microsoft.EntityFrameworkCore;

namespace Fabula.Api.Endpoints;

public static class ProgressEndpoints
{
    // Auth is not wired up yet; until then progress is tracked per book without user binding.
    // This placeholder uses a default user id of 1 and will be replaced once JWT auth lands.
    private const int TemporaryUserId = 1;

    public static IEndpointRouteBuilder MapProgressEndpoints(this IEndpointRouteBuilder app)
    {
        var group = app.MapGroup("/api/progress").WithTags("Progress");

        group.MapGet("/{bookId:int}", async (int bookId, FabulaDbContext db, CancellationToken ct) =>
        {
            await EnsureUserAsync(db, ct);
            var p = await db.PlaybackProgress
                .AsNoTracking()
                .FirstOrDefaultAsync(x => x.UserId == TemporaryUserId && x.BookId == bookId, ct);
            if (p is null) return Results.Ok(new ProgressDto(bookId, TimeSpan.Zero, false, null, null));
            return Results.Ok(new ProgressDto(p.BookId, p.Position, p.Finished, p.UpdatedAt, p.LastDevice));
        });

        group.MapPut("/{bookId:int}", async (int bookId, UpdateProgressRequest req, FabulaDbContext db, CancellationToken ct) =>
        {
            var bookExists = await db.Books.AnyAsync(b => b.Id == bookId, ct);
            if (!bookExists) return Results.NotFound();

            await EnsureUserAsync(db, ct);

            var p = await db.PlaybackProgress
                .FirstOrDefaultAsync(x => x.UserId == TemporaryUserId && x.BookId == bookId, ct);

            if (p is null)
            {
                p = new PlaybackProgress
                {
                    UserId = TemporaryUserId,
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

public record ProgressDto(int BookId, TimeSpan Position, bool Finished, DateTime? UpdatedAt, string? Device);
public record UpdateProgressRequest(TimeSpan Position, bool Finished, string? Device);
