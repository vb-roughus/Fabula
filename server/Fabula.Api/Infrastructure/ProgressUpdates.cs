using Fabula.Core.Domain;
using Fabula.Data;
using Microsoft.EntityFrameworkCore;

namespace Fabula.Api.Infrastructure;

/// <summary>
/// Writing a listener's progress, newest listening first.
///
/// Progress used to be stamped with the time it reached the server, and the
/// last to arrive won. A phone that listened offline and handed its progress
/// over hours later therefore overwrote whatever another device had done in
/// between -- sending the listener back. Each update now carries when the
/// device recorded it, and one older than what is stored is turned away.
/// </summary>
public static class ProgressUpdates
{
    /// <summary>When a stored row was recorded: by its device where known, else when it arrived.</summary>
    public static DateTime RecordedAt(PlaybackProgress row) => row.RecordedAt ?? row.UpdatedAt;

    /// <summary>
    /// The time an update counts as recorded at: the device's own, but never
    /// later than its arrival. A clock running ahead would otherwise make one
    /// device's position unbeatable until the real time caught up.
    /// Without a time from the device -- the web player, older apps -- it is
    /// the arrival, which is what it always was.
    /// </summary>
    public static DateTime Effective(DateTime? claimed, DateTime now)
    {
        if (claimed is not DateTime time) return now;
        var utc = time.Kind == DateTimeKind.Local ? time.ToUniversalTime() : time;
        return utc > now ? now : utc;
    }

    /// <summary>Whether an update recorded at <paramref name="recordedAt"/> replaces <paramref name="stored"/>.</summary>
    public static bool Supersedes(DateTime recordedAt, PlaybackProgress? stored) =>
        stored is null || recordedAt >= RecordedAt(stored);

    /// <summary>
    /// Stores the update unless something recorded later is there already.
    /// Returns the row as it now stands, and whether the update was taken.
    /// </summary>
    public static async Task<(PlaybackProgress Row, bool Accepted)> ApplyAsync(
        FabulaDbContext db,
        int userId,
        int bookId,
        TimeSpan position,
        bool finished,
        string? device,
        DateTime? claimedRecordedAt,
        DateTime now,
        CancellationToken ct)
    {
        var row = await db.PlaybackProgress
            .FirstOrDefaultAsync(x => x.UserId == userId && x.BookId == bookId, ct);
        var recordedAt = Effective(claimedRecordedAt, now);

        if (row is not null && !Supersedes(recordedAt, row)) return (row, false);

        if (row is null)
        {
            row = new PlaybackProgress { UserId = userId, BookId = bookId };
            db.PlaybackProgress.Add(row);
        }

        row.Position = position;
        row.Finished = finished;
        row.LastDevice = device;
        row.UpdatedAt = now;
        row.RecordedAt = recordedAt;

        await db.SaveChangesAsync(ct);
        return (row, true);
    }
}
