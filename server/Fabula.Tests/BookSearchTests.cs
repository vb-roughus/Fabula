using Fabula.Api.Infrastructure;
using Fabula.Core.Domain;
using Fabula.Data;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Xunit;

namespace Fabula.Tests;

/// <summary>
/// Searching the library by title, author, narrator and series.
///
/// The interesting part is case: SQLite's LIKE folds ASCII only, so the
/// database-level tests run against a real SQLite and the matching itself is
/// checked with umlauts, which is exactly where a SQL-side search failed.
/// </summary>
public class BookSearchTests : IDisposable
{
    private readonly SqliteConnection _connection;
    private readonly FabulaDbContext _db;
    private readonly LibraryFolder _folder;

    public BookSearchTests()
    {
        _connection = new SqliteConnection("Filename=:memory:");
        _connection.Open();
        _db = new FabulaDbContext(new DbContextOptionsBuilder<FabulaDbContext>()
            .UseSqlite(_connection)
            .Options);
        _db.Database.EnsureCreated();

        _folder = new LibraryFolder { Name = "Hörbücher", Path = "/media/audio" };
        _db.LibraryFolders.Add(_folder);
        _db.SaveChanges();
    }

    public void Dispose()
    {
        _db.Dispose();
        _connection.Dispose();
        GC.SuppressFinalize(this);
    }

    private Book AddBook(
        string title,
        string? author = null,
        string? narrator = null,
        string? series = null,
        string? subtitle = null)
    {
        var book = new Book
        {
            Title = title,
            Subtitle = subtitle,
            LibraryFolderId = _folder.Id
        };
        if (author != null) book.Authors.Add(new Author { Name = author });
        if (narrator != null) book.Narrators.Add(new Narrator { Name = narrator });
        if (series != null) book.Series = new Series { Name = series };
        _db.Books.Add(book);
        _db.SaveChanges();
        return book;
    }

    private async Task<List<int>> Search(string text) =>
        await BookSearch.MatchingIdsAsync(_db, text, CancellationToken.None);

    // --- the words of a search ---------------------------------------------

    [Fact]
    public void Splits_a_search_into_its_words()
    {
        Assert.Equal(new[] { "harry", "potter" }, BookSearch.Tokens("  harry   potter "));
    }

    [Fact]
    public void Repeated_words_count_once()
    {
        Assert.Equal(new[] { "harry" }, BookSearch.Tokens("harry HARRY"));
    }

    [Fact]
    public void Nothing_to_search_for_yields_no_words()
    {
        Assert.Empty(BookSearch.Tokens(null));
        Assert.Empty(BookSearch.Tokens("   "));
    }

    // --- matching -----------------------------------------------------------

    /// <summary>
    /// The defect that moved the search out of SQL: LIKE does not fold "Ü".
    /// </summary>
    [Fact]
    public void Matches_umlauts_whatever_their_case()
    {
        var fields = new string?[] { "Über den Wolken" };

        Assert.True(BookSearch.Matches(new[] { "über" }, fields));
        Assert.True(BookSearch.Matches(new[] { "ÜBER" }, fields));
        Assert.True(BookSearch.Matches(new[] { "wolken" }, fields));
    }

    [Fact]
    public void Matches_a_word_in_the_middle_of_a_field()
    {
        Assert.True(BookSearch.Matches(new[] { "potter" }, new string?[] { "Harry Potter und der Stein der Weisen" }));
    }

    /// <summary>"Rowling Stein": one word from the author, one from the title.</summary>
    [Fact]
    public void Every_word_may_match_in_a_different_field()
    {
        var fields = new string?[] { "Harry Potter und der Stein der Weisen", "J. K. Rowling" };

        Assert.True(BookSearch.Matches(new[] { "rowling", "stein" }, fields));
    }

    [Fact]
    public void A_word_that_matches_nothing_rules_the_book_out()
    {
        var fields = new string?[] { "Harry Potter", "J. K. Rowling" };

        Assert.False(BookSearch.Matches(new[] { "rowling", "tolkien" }, fields));
    }

    [Fact]
    public void Missing_fields_are_ignored()
    {
        Assert.True(BookSearch.Matches(new[] { "potter" }, new string?[] { null, "", "Potter" }));
        Assert.False(BookSearch.Matches(new[] { "potter" }, new string?[] { null, "" }));
    }

    // --- against the database -----------------------------------------------

    [Fact]
    public async Task Finds_a_book_by_a_word_of_its_title_in_either_case()
    {
        var book = AddBook("Über den Wolken");
        AddBook("Der Hobbit");

        Assert.Equal(new[] { book.Id }, await Search("über"));
        Assert.Equal(new[] { book.Id }, await Search("WOLKEN"));
    }

    [Fact]
    public async Task Finds_a_book_by_its_author()
    {
        var book = AddBook("Der Hobbit", author: "J. R. R. Tolkien");
        AddBook("Momo", author: "Michael Ende");

        Assert.Equal(new[] { book.Id }, await Search("tolkien"));
    }

    [Fact]
    public async Task Finds_a_book_by_its_narrator()
    {
        var book = AddBook("Der Hobbit", narrator: "Rufus Beck");
        AddBook("Momo", narrator: "Gert Westphal");

        Assert.Equal(new[] { book.Id }, await Search("rufus"));
    }

    /// <summary>
    /// The reason series books can leave the library list at all: they stay
    /// reachable by the name of their series.
    /// </summary>
    [Fact]
    public async Task Finds_a_book_by_the_name_of_its_series()
    {
        var book = AddBook("Der Stein der Weisen", series: "Harry Potter");
        AddBook("Momo");

        Assert.Equal(new[] { book.Id }, await Search("harry potter"));
    }

    [Fact]
    public async Task Finds_a_book_by_its_subtitle()
    {
        var book = AddBook("Das Parfum", subtitle: "Die Geschichte eines Mörders");

        Assert.Equal(new[] { book.Id }, await Search("mörders"));
    }

    [Fact]
    public async Task Combines_words_across_title_and_author()
    {
        var wanted = AddBook("Der Hobbit", author: "J. R. R. Tolkien");
        AddBook("Der Herr der Ringe", author: "J. R. R. Tolkien");
        AddBook("Der Hobbit", author: "Jemand Anderes");

        Assert.Equal(new[] { wanted.Id }, await Search("hobbit tolkien"));
    }

    [Fact]
    public async Task Finds_nothing_when_nothing_matches()
    {
        AddBook("Momo");

        Assert.Empty(await Search("zzz"));
    }
}
