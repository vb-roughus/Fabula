using Fabula.Api.Infrastructure;
using Fabula.Core.Domain;
using Fabula.Data;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Xunit;

namespace Fabula.Tests;

/// <summary>
/// The filters and the order behind the library listing, run against a real
/// SQLite database. A page boundary is where a slightly wrong filter or order
/// shows up -- as a book that is missing, or one that is listed twice.
/// </summary>
public class BookQueriesTests : IDisposable
{
    private readonly SqliteConnection _connection;
    private readonly FabulaDbContext _db;
    private readonly LibraryFolder _audiobooks;
    private readonly LibraryFolder _radioPlays;

    public BookQueriesTests()
    {
        _connection = new SqliteConnection("Filename=:memory:");
        _connection.Open();
        _db = new FabulaDbContext(new DbContextOptionsBuilder<FabulaDbContext>()
            .UseSqlite(_connection)
            .Options);
        _db.Database.EnsureCreated();

        _audiobooks = new LibraryFolder { Name = "Hörbücher", Path = "/media/books", Type = LibraryType.Audiobook };
        _radioPlays = new LibraryFolder { Name = "Hörspiele", Path = "/media/plays", Type = LibraryType.RadioPlay };
        _db.LibraryFolders.AddRange(_audiobooks, _radioPlays);
        _db.SaveChanges();
    }

    public void Dispose()
    {
        _db.Dispose();
        _connection.Dispose();
        GC.SuppressFinalize(this);
    }

    private Book AddBook(string title, LibraryFolder? folder = null, Series? series = null)
    {
        var book = new Book
        {
            Title = title,
            LibraryFolderId = (folder ?? _audiobooks).Id,
            Series = series
        };
        _db.Books.Add(book);
        _db.SaveChanges();
        return book;
    }

    // --- standalone ---------------------------------------------------------

    [Fact]
    public void Standalone_leaves_out_books_in_a_series()
    {
        var series = new Series { Name = "Harry Potter" };
        var alone = AddBook("Momo");
        AddBook("Der Stein der Weisen", series: series);

        var ids = _db.Books.WhereStandalone(true).Select(b => b.Id).ToList();

        Assert.Equal(new[] { alone.Id }, ids);
    }

    [Fact]
    public void Not_asking_for_standalone_lists_everything()
    {
        var series = new Series { Name = "Harry Potter" };
        AddBook("Momo");
        AddBook("Der Stein der Weisen", series: series);

        Assert.Equal(2, _db.Books.WhereStandalone(false).Count());
    }

    // --- type ---------------------------------------------------------------

    [Fact]
    public void Type_keeps_only_that_kind_of_library()
    {
        var book = AddBook("Momo", _audiobooks);
        var play = AddBook("Die drei ???", _radioPlays);

        Assert.Equal(new[] { book.Id }, _db.Books.WhereType(LibraryType.Audiobook).Select(b => b.Id).ToList());
        Assert.Equal(new[] { play.Id }, _db.Books.WhereType(LibraryType.RadioPlay).Select(b => b.Id).ToList());
    }

    [Fact]
    public void No_type_lists_every_kind()
    {
        AddBook("Momo", _audiobooks);
        AddBook("Die drei ???", _radioPlays);

        Assert.Equal(2, _db.Books.WhereType(null).Count());
    }

    // --- order and paging ---------------------------------------------------

    [Fact]
    public void Lists_by_title()
    {
        AddBook("Cäsar");
        AddBook("Anna");
        AddBook("Berta");

        var titles = _db.Books.OrderForListing().Select(b => b.Title).ToList();

        Assert.Equal(new[] { "Anna", "Berta", "Cäsar" }, titles);
    }

    [Fact]
    public void A_sort_title_takes_the_place_of_the_title()
    {
        var book = AddBook("Der Hobbit");
        book.SortTitle = "Hobbit, Der";
        _db.SaveChanges();
        AddBook("Anna");
        AddBook("Zebra");

        var titles = _db.Books.OrderForListing().Select(b => b.Title).ToList();

        Assert.Equal(new[] { "Anna", "Der Hobbit", "Zebra" }, titles);
    }

    /// <summary>
    /// The reason for the tie-break. Every page is its own query; with equal
    /// titles and no further key the order between them is whatever the
    /// database happens to do, and a book can be repeated or skipped at a page
    /// boundary. Paging through twice-named books must list each exactly once.
    /// </summary>
    [Fact]
    public void Paging_through_equal_titles_lists_every_book_exactly_once()
    {
        var added = Enumerable.Range(0, 7).Select(_ => AddBook("Das Parfum").Id).ToList();

        var seen = new List<int>();
        for (var page = 1; page <= 4; page++)
        {
            seen.AddRange(_db.Books
                .OrderForListing()
                .Skip((page - 1) * 2)
                .Take(2)
                .Select(b => b.Id)
                .ToList());
        }

        Assert.Equal(added.OrderBy(x => x), seen);
    }

    [Fact]
    public void Equal_titles_fall_back_to_the_order_they_were_added_in()
    {
        var first = AddBook("Das Parfum");
        var second = AddBook("Das Parfum");

        var ids = _db.Books.OrderForListing().Select(b => b.Id).ToList();

        Assert.Equal(new[] { first.Id, second.Id }, ids);
    }
}
