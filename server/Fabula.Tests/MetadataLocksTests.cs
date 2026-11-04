using Fabula.Core.Domain;
using Fabula.Core.Services;
using Xunit;

namespace Fabula.Tests;

/// <summary>
/// The record of which fields were taken over from a catalogue, and taking
/// them over.
/// </summary>
public class MetadataLocksTests
{
    // --- the lock list --------------------------------------------------------

    [Fact]
    public void Nothing_stored_locks_nothing()
    {
        Assert.Empty(MetadataLocks.Parse(null));
        Assert.Empty(MetadataLocks.Parse("  "));
    }

    [Fact]
    public void Reads_a_stored_list()
    {
        var locks = MetadataLocks.Parse("title, narrators,cover");

        Assert.Equal(new[] { "cover", "narrators", "title" }, locks.OrderBy(k => k));
    }

    /// <summary>A key from a later version, or a typo, must not lock anything.</summary>
    [Fact]
    public void Ignores_unknown_keys()
    {
        Assert.Equal(new[] { "title" }, MetadataLocks.Parse("title,genre,???"));
    }

    [Fact]
    public void Stores_known_keys_once_and_in_a_fixed_order()
    {
        Assert.Equal("title,narrators,cover", MetadataLocks.Format(new[] { "cover", "TITLE", "narrators", "title" }));
    }

    [Fact]
    public void Stores_nothing_for_no_keys()
    {
        Assert.Null(MetadataLocks.Format(Array.Empty<string>()));
        Assert.Null(MetadataLocks.Format(new[] { "genre" }));
    }

    [Fact]
    public void Removing_drops_only_that_key()
    {
        Assert.Equal("title,cover", MetadataLocks.Remove("title,chapters,cover", MetadataLocks.Chapters));
        Assert.Null(MetadataLocks.Remove("chapters", MetadataLocks.Chapters));
        Assert.Null(MetadataLocks.Remove(null, MetadataLocks.Chapters));
    }

    [Fact]
    public void Chapters_are_locked_only_when_there_are_some()
    {
        var withChapters = Update(MetadataLocks.Chapters) with
        {
            Chapters = [new ChapterInfo("Kapitel 1", TimeSpan.Zero, TimeSpan.FromMinutes(10))]
        };

        Assert.Equal(new[] { "chapters" }, withChapters.ApplyTo(Book()));
        Assert.Empty(Update(MetadataLocks.Chapters).ApplyTo(Book()));
    }

    [Fact]
    public void Adding_keeps_what_was_locked_before()
    {
        Assert.Equal("title,narrators,description", MetadataLocks.Add("title,description", new[] { "narrators" }));
    }

    // --- taking fields over ---------------------------------------------------

    private static Book Book() => new()
    {
        Title = "Momo",
        Description = "Aus den Tags",
        Publisher = "Tag-Verlag"
    };

    private static BookMetadataUpdate Update(params string[] fields) => new()
    {
        Fields = fields.ToHashSet(),
        Title = "Momo oder Die seltsame Geschichte",
        Description = "Aus dem Katalog",
        Publisher = "Katalog-Verlag",
        Year = 2005,
        Asin = "B004V0YKT2"
    };

    [Fact]
    public void Writes_only_the_chosen_fields()
    {
        var book = Book();

        Update(MetadataLocks.Description).ApplyTo(book);

        Assert.Equal("Aus dem Katalog", book.Description);
        Assert.Equal("Momo", book.Title);
        Assert.Equal("Tag-Verlag", book.Publisher);
        Assert.Null(book.PublishYear);
    }

    [Fact]
    public void Locks_what_it_wrote()
    {
        var book = Book();

        var taken = Update(MetadataLocks.Title, MetadataLocks.Year).ApplyTo(book);

        Assert.Equal(new[] { "title", "year" }, taken);
        Assert.Equal("title,year", book.MetadataLocks);
    }

    /// <summary>
    /// Ticking a field the catalogue has nothing for must not erase what the
    /// tags had -- nor lock the gap against the next scan.
    /// </summary>
    [Fact]
    public void A_chosen_field_without_a_value_is_skipped_and_not_locked()
    {
        var book = Book();
        var update = Update(MetadataLocks.Subtitle, MetadataLocks.Title) with { Subtitle = null };
        book.Subtitle = "Aus den Tags";

        var taken = update.ApplyTo(book);

        Assert.Equal("Aus den Tags", book.Subtitle);
        Assert.Equal(new[] { "title" }, taken);
        Assert.Equal("title", book.MetadataLocks);
    }

    [Fact]
    public void Earlier_locks_are_kept()
    {
        var book = Book();
        book.MetadataLocks = "cover";

        Update(MetadataLocks.Title).ApplyTo(book);

        Assert.Equal("title,cover", book.MetadataLocks);
    }

    [Fact]
    public void Relations_are_locked_when_there_is_something_to_take()
    {
        var book = Book();
        var update = Update(MetadataLocks.Authors, MetadataLocks.Narrators, MetadataLocks.Series) with
        {
            Authors = ["Michael Ende"],
            Narrators = [],
            SeriesName = "  "
        };

        var taken = update.ApplyTo(book);

        Assert.Equal(new[] { "authors" }, taken);
    }

    [Fact]
    public void A_volume_is_taken_for_a_book_in_a_series()
    {
        var book = Book();
        book.SeriesId = 4;
        var update = Update(MetadataLocks.Volume) with { SeriesPosition = 3m };

        Assert.Equal(new[] { "volume" }, update.ApplyTo(book));
        Assert.Equal("volume", book.MetadataLocks);
    }

    [Fact]
    public void A_volume_is_taken_along_with_the_series_it_numbers()
    {
        var book = Book();
        var update = Update(MetadataLocks.Series, MetadataLocks.Volume) with
        {
            SeriesName = "Michael-Ende-Bibliothek",
            SeriesPosition = 3m
        };

        Assert.Equal(new[] { "series", "volume" }, update.ApplyTo(book));
    }

    /// <summary>A number with nothing to number is not stored, nor locked.</summary>
    [Fact]
    public void A_volume_without_any_series_is_skipped()
    {
        var book = Book();
        var update = Update(MetadataLocks.Volume) with { SeriesPosition = 3m };

        Assert.Empty(update.ApplyTo(book));
        Assert.Null(book.MetadataLocks);
    }

    /// <summary>A catalogue title longer than the column would fail the whole save.</summary>
    [Fact]
    public void A_value_longer_than_its_column_is_cut()
    {
        var book = Book();
        var update = Update(MetadataLocks.Title) with { Title = new string('x', 600) };

        update.ApplyTo(book);

        Assert.Equal(512, book.Title.Length);
    }

    [Fact]
    public void Nothing_taken_leaves_the_book_untouched()
    {
        var book = Book();
        var before = book.UpdatedAt;

        var taken = Update().ApplyTo(book);

        Assert.Empty(taken);
        Assert.Null(book.MetadataLocks);
        Assert.Equal(before, book.UpdatedAt);
    }
}
