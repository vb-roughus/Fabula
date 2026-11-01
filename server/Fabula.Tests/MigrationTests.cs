using Fabula.Data;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Xunit;

namespace Fabula.Tests;

/// <summary>
/// The migrations against the model.
///
/// Migrations here are written by hand, without the EF tooling, and the
/// server migrates on start. If the model snapshot and the model disagree,
/// EF refuses to migrate -- and the service does not come up. These tests are
/// where that shows instead of on the operator's machine.
/// </summary>
public class MigrationTests : IDisposable
{
    private readonly SqliteConnection _connection;
    private readonly FabulaDbContext _db;

    public MigrationTests()
    {
        _connection = new SqliteConnection("Filename=:memory:");
        _connection.Open();
        _db = new FabulaDbContext(new DbContextOptionsBuilder<FabulaDbContext>()
            .UseSqlite(_connection)
            .Options);
    }

    public void Dispose()
    {
        _db.Dispose();
        _connection.Dispose();
        GC.SuppressFinalize(this);
    }

    [Fact]
    public void The_model_has_no_changes_the_migrations_lack()
    {
        Assert.False(_db.Database.HasPendingModelChanges());
    }

    [Fact]
    public void A_fresh_database_migrates_to_the_current_model()
    {
        _db.Database.Migrate();

        Assert.Empty(_db.Database.GetPendingMigrations());
        // Reading the new column proves it exists in the migrated schema.
        Assert.Empty(_db.Books.Select(b => b.MetadataLocks).ToList());
    }

    [Fact]
    public void The_lock_column_comes_with_its_own_migration()
    {
        Assert.Contains("20261101130000_AddMetadataLocks", _db.Database.GetMigrations());
    }
}
