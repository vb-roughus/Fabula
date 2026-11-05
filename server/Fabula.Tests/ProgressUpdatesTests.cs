using Fabula.Api.Infrastructure;
using Fabula.Core.Domain;
using Fabula.Data;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Xunit;

namespace Fabula.Tests;

/// <summary>
/// Progress from several devices, ordered by when it was listened to rather
/// than by when it reached the server. Run against a real SQLite database.
/// </summary>
public class ProgressUpdatesTests : IDisposable
{
    private readonly SqliteConnection _connection;
    private readonly FabulaDbContext _db;
    private readonly int _userId;
    private readonly int _bookId;

    private static readonly DateTime Ten = new(2026, 11, 5, 10, 0, 0, DateTimeKind.Utc);

    public ProgressUpdatesTests()
    {
        _connection = new SqliteConnection("Filename=:memory:");
        _connection.Open();
        _db = new FabulaDbContext(new DbContextOptionsBuilder<FabulaDbContext>()
            .UseSqlite(_connection)
            .Options);
        _db.Database.EnsureCreated();

        var user = new User { Username = "rolf", PasswordHash = "x", CreatedAt = Ten };
        var folder = new LibraryFolder { Name = "Hörbücher", Path = "/media/books" };
        _db.Users.Add(user);
        _db.LibraryFolders.Add(folder);
        _db.SaveChanges();
        var book = new Book { Title = "Momo", LibraryFolderId = folder.Id, Duration = TimeSpan.FromHours(7) };
        _db.Books.Add(book);
        _db.SaveChanges();
        _userId = user.Id;
        _bookId = book.Id;
    }

    public void Dispose()
    {
        _db.Dispose();
        _connection.Dispose();
        GC.SuppressFinalize(this);
    }

    private Task<(PlaybackProgress Row, bool Accepted)> Save(
        double hours, DateTime? recordedAt, DateTime arrives, string device = "handy") =>
        ProgressUpdates.ApplyAsync(
            _db, _userId, _bookId, TimeSpan.FromHours(hours), finished: false, device,
            recordedAt, arrives, CancellationToken.None);

    private PlaybackProgress Stored()
    {
        _db.ChangeTracker.Clear();
        return _db.PlaybackProgress.Single();
    }

    [Fact]
    public async Task The_first_progress_is_stored_with_its_device_time()
    {
        var (_, accepted) = await Save(1, recordedAt: Ten, arrives: Ten.AddMinutes(1));

        Assert.True(accepted);
        Assert.Equal(TimeSpan.FromHours(1), Stored().Position);
        Assert.Equal(Ten, Stored().RecordedAt);
    }

    [Fact]
    public async Task Later_listening_replaces_earlier()
    {
        await Save(1, recordedAt: Ten, arrives: Ten);

        var (_, accepted) = await Save(2, recordedAt: Ten.AddHours(1), arrives: Ten.AddHours(1));

        Assert.True(accepted);
        Assert.Equal(TimeSpan.FromHours(2), Stored().Position);
    }

    /// <summary>
    /// The case this is for: the phone listened offline at ten, the tablet
    /// listened online until twelve, and the phone hands its ten o'clock
    /// position over at one. Arrival order would send the listener back two
    /// hours; listening order keeps the tablet's.
    /// </summary>
    [Fact]
    public async Task A_late_handover_of_older_listening_is_turned_away()
    {
        await Save(3, recordedAt: Ten.AddHours(2), arrives: Ten.AddHours(2), device: "tablet");

        var (row, accepted) = await Save(1, recordedAt: Ten, arrives: Ten.AddHours(3), device: "handy");

        Assert.False(accepted);
        Assert.Equal(TimeSpan.FromHours(3), row.Position);
        Assert.Equal(TimeSpan.FromHours(3), Stored().Position);
        Assert.Equal("tablet", Stored().LastDevice);
    }

    /// <summary>Without a time from the device -- the web player, an older app -- the arrival counts, as always.</summary>
    [Fact]
    public async Task Without_a_device_time_the_arrival_counts()
    {
        await Save(2, recordedAt: null, arrives: Ten.AddHours(2), device: "web");

        var (_, accepted) = await Save(1, recordedAt: Ten, arrives: Ten.AddHours(3));

        Assert.False(accepted);
        Assert.Equal(Ten.AddHours(2), Stored().RecordedAt);
    }

    /// <summary>A clock running ahead must not make one device's position unbeatable.</summary>
    [Fact]
    public async Task A_time_from_the_future_counts_as_the_arrival()
    {
        await Save(1, recordedAt: Ten.AddHours(5), arrives: Ten);

        Assert.Equal(Ten, Stored().RecordedAt);

        var (_, accepted) = await Save(2, recordedAt: Ten.AddMinutes(30), arrives: Ten.AddMinutes(30));
        Assert.True(accepted);
    }

    /// <summary>Rows from before device times were kept compare by when they arrived.</summary>
    [Fact]
    public async Task An_old_row_without_a_device_time_compares_by_its_arrival()
    {
        _db.PlaybackProgress.Add(new PlaybackProgress
        {
            UserId = _userId, BookId = _bookId,
            Position = TimeSpan.FromHours(2), UpdatedAt = Ten.AddHours(2), RecordedAt = null
        });
        _db.SaveChanges();

        var (_, older) = await Save(1, recordedAt: Ten, arrives: Ten.AddHours(3));
        var (_, newer) = await Save(3, recordedAt: Ten.AddHours(4), arrives: Ten.AddHours(4));

        Assert.False(older);
        Assert.True(newer);
    }

    [Fact]
    public async Task The_same_moment_is_taken()
    {
        await Save(1, recordedAt: Ten, arrives: Ten);

        var (_, accepted) = await Save(1.5, recordedAt: Ten, arrives: Ten.AddMinutes(1));

        Assert.True(accepted);
    }

    [Fact]
    public void A_local_time_is_compared_in_UTC()
    {
        var local = Ten.ToLocalTime();

        Assert.Equal(Ten, ProgressUpdates.Effective(local, Ten.AddHours(1)));
    }
}
