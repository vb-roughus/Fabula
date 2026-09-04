using Fabula.Api.Infrastructure;
using Fabula.Core.Domain;
using Fabula.Data;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Xunit;

namespace Fabula.Tests;

/// <summary>
/// Looking an account up by name, whatever the keyboard did to the first letter.
///
/// Run against a real SQLite database rather than a substitute, because the
/// behaviour under test *is* SQLite's: which comparison folds case and which
/// does not. A fake would happily agree with whatever the expression said and
/// prove nothing.
/// </summary>
public class UsernameMatchingTests : IDisposable
{
    private readonly SqliteConnection _connection;
    private readonly FabulaDbContext _db;

    public UsernameMatchingTests()
    {
        // The connection has to stay open: an in-memory database lives exactly
        // as long as it.
        _connection = new SqliteConnection("Filename=:memory:");
        _connection.Open();
        _db = new FabulaDbContext(new DbContextOptionsBuilder<FabulaDbContext>()
            .UseSqlite(_connection)
            .Options);
        _db.Database.EnsureCreated();
    }

    public void Dispose()
    {
        _db.Dispose();
        _connection.Dispose();
        GC.SuppressFinalize(this);
    }

    private void AddUser(string username)
    {
        _db.Users.Add(new User
        {
            Username = username,
            PasswordHash = "irrelevant-for-this-test",
            CreatedAt = DateTime.UtcNow
        });
        _db.SaveChanges();
    }

    /// <summary>
    /// The reported failure: the account is "rolf", the keyboard offered
    /// "Rolf", and the login refused correct credentials.
    /// </summary>
    [Fact]
    public void Finds_an_account_whose_name_differs_only_in_capitalisation()
    {
        AddUser("rolf");

        Assert.NotNull(_db.Users.WhereUsernameMatches("Rolf").FirstOrDefault());
        Assert.NotNull(_db.Users.WhereUsernameMatches("ROLF").FirstOrDefault());
        Assert.NotNull(_db.Users.WhereUsernameMatches("rOlF").FirstOrDefault());
    }

    /// <summary>And the other way round, for an account stored capitalised.</summary>
    [Fact]
    public void Finds_a_capitalised_account_typed_in_lower_case()
    {
        AddUser("Rolf");

        Assert.NotNull(_db.Users.WhereUsernameMatches("rolf").FirstOrDefault());
    }

    [Fact]
    public void Still_finds_the_exact_spelling()
    {
        AddUser("rolf");

        var found = _db.Users.WhereUsernameMatches("rolf").FirstOrDefault();

        Assert.NotNull(found);
        Assert.Equal("rolf", found!.Username);
    }

    /// <summary>
    /// Loosening the comparison must not start matching different people.
    /// A prefix, a suffix or a neighbouring name stays a different account.
    /// </summary>
    [Theory]
    [InlineData("rol")]
    [InlineData("rolfx")]
    [InlineData("olf")]
    [InlineData("")]
    [InlineData("anna")]
    public void Does_not_find_a_different_name(string typed)
    {
        AddUser("rolf");

        Assert.Null(_db.Users.WhereUsernameMatches(typed).FirstOrDefault());
    }

    /// <summary>
    /// Wildcards must stay literal. Matching with LIKE would have made "r%"
    /// find "rolf", which is the trap this deliberately avoids.
    /// </summary>
    [Theory]
    [InlineData("r%")]
    [InlineData("%")]
    [InlineData("rol_")]
    public void Treats_wildcard_characters_as_ordinary_text(string typed)
    {
        AddUser("rolf");

        Assert.Null(_db.Users.WhereUsernameMatches(typed).FirstOrDefault());
    }

    /// <summary>
    /// The duplicate check has to use the same comparison as the lookup. If it
    /// were stricter, "Rolf" and "rolf" could both exist -- and then a login
    /// has two answers and silently picks one.
    /// </summary>
    [Fact]
    public void Recognises_a_duplicate_that_differs_only_in_capitalisation()
    {
        AddUser("rolf");

        Assert.True(_db.Users.WhereUsernameMatches("Rolf").Any());
        Assert.False(_db.Users.WhereUsernameMatches("anna").Any());
    }

    /// <summary>
    /// A documented limit, not an oversight: SQLite's NOCASE folds ASCII only.
    /// Pinned here so the boundary is known rather than discovered. Changing it
    /// would mean a custom collation and rebuilding the database.
    /// </summary>
    [Fact]
    public void Does_not_fold_case_beyond_ascii()
    {
        AddUser("ötzi");

        Assert.NotNull(_db.Users.WhereUsernameMatches("ötzi").FirstOrDefault());
        Assert.Null(_db.Users.WhereUsernameMatches("Ötzi").FirstOrDefault());
    }
}
