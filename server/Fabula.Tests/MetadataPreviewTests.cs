using Fabula.Api.Infrastructure.Metadata;
using Fabula.Core.Domain;
using Fabula.Core.Services;
using Xunit;

namespace Fabula.Tests;

/// <summary>
/// Choosing the edition that fits, and showing what taking it over would change.
/// </summary>
public class MetadataPreviewTests
{
    private static CatalogBook Record(
        string asin = "B004V0YKT2",
        string? title = "Momo",
        int? runtime = 412,
        string? description = "Aus dem Katalog",
        IReadOnlyList<string>? narrators = null,
        string? series = null,
        decimal? position = null,
        string? cover = "https://m.media-amazon.com/images/I/81abc.jpg") =>
        new(asin, title, null, ["Michael Ende"], narrators ?? ["Gert Westphal"], series, position,
            description, "Der Hörverlag", 2005, "de", null, cover, runtime);

    // --- runtime --------------------------------------------------------------

    [Fact]
    public void A_runtime_within_two_percent_matches()
    {
        var sevenHours = TimeSpan.FromMinutes(420);

        Assert.True(MetadataPreview.RuntimeMatches(412, sevenHours));
        Assert.True(MetadataPreview.RuntimeMatches(428, sevenHours));
        Assert.False(MetadataPreview.RuntimeMatches(400, sevenHours));
    }

    /// <summary>Two percent of a short book is seconds; an intro alone differs by more.</summary>
    [Fact]
    public void A_short_book_is_allowed_three_minutes()
    {
        var twentyMinutes = TimeSpan.FromMinutes(20);

        Assert.True(MetadataPreview.RuntimeMatches(23, twentyMinutes));
        Assert.False(MetadataPreview.RuntimeMatches(24, twentyMinutes));
    }

    [Fact]
    public void Without_a_runtime_nothing_matches()
    {
        Assert.False(MetadataPreview.RuntimeMatches(null, TimeSpan.FromMinutes(420)));
        Assert.False(MetadataPreview.RuntimeMatches(412, TimeSpan.Zero));
    }

    /// <summary>
    /// The abridged reading usually ranks first for its title; the edition that
    /// is actually on the disk is the one whose length fits.
    /// </summary>
    [Fact]
    public void The_edition_whose_length_fits_comes_first()
    {
        var candidates = new[]
        {
            Record(asin: "B0GEKUERZT", runtime: 180),
            Record(asin: "B0SONSTWAS", runtime: null),
            Record(asin: "B0PASSTGUT", runtime: 418),
            Record(asin: "B0PASSTBES", runtime: 420)
        };

        var ranked = MetadataPreview.Rank(candidates, TimeSpan.FromMinutes(420));

        Assert.Equal(
            new[] { "B0PASSTBES", "B0PASSTGUT", "B0GEKUERZT", "B0SONSTWAS" },
            ranked.Select(c => c.Asin));
    }

    // --- fields ---------------------------------------------------------------

    private static Book Book() => new()
    {
        Id = 7,
        Title = "Momo",
        Description = null,
        Narrators = [new Narrator { Name = "Tag Sprecher" }],
        Authors = [new Author { Name = "Michael Ende" }],
        CoverPath = "abc.jpg"
    };

    private static MetadataField Field(IEnumerable<MetadataField> fields, string key) =>
        fields.Single(f => f.Key == key);

    [Fact]
    public void An_empty_field_is_preselected()
    {
        var fields = MetadataPreview.Fields(Book(), Record());

        Assert.True(Field(fields, MetadataLocks.Description).Preselected);
        Assert.True(Field(fields, MetadataLocks.Publisher).Preselected);
    }

    /// <summary>Replacing what is there is the admin's call, not the default.</summary>
    [Fact]
    public void A_field_with_a_value_is_offered_but_not_preselected()
    {
        var narrators = Field(MetadataPreview.Fields(Book(), Record()), MetadataLocks.Narrators);

        Assert.False(narrators.Preselected);
        Assert.Equal("Tag Sprecher", narrators.Current);
        Assert.Equal("Gert Westphal", narrators.Proposed);
    }

    [Fact]
    public void A_field_that_already_says_the_same_is_left_out()
    {
        var keys = MetadataPreview.Fields(Book(), Record()).Select(f => f.Key).ToList();

        Assert.DoesNotContain(MetadataLocks.Title, keys);
        Assert.DoesNotContain(MetadataLocks.Authors, keys);
    }

    [Fact]
    public void A_field_the_record_has_nothing_for_is_left_out()
    {
        var keys = MetadataPreview.Fields(Book(), Record(description: null)).Select(f => f.Key).ToList();

        Assert.DoesNotContain(MetadataLocks.Description, keys);
        Assert.DoesNotContain(MetadataLocks.Isbn, keys);
    }

    [Fact]
    public void The_cover_is_shown_as_two_pictures()
    {
        var cover = Field(MetadataPreview.Fields(Book(), Record()), MetadataLocks.Cover);

        Assert.Equal("/api/books/7/cover?v=abc.jpg", cover.Current);
        Assert.Equal("https://m.media-amazon.com/images/I/81abc.jpg", cover.Proposed);
        Assert.False(cover.Preselected);
    }

    [Fact]
    public void Fields_come_in_a_fixed_order()
    {
        var keys = MetadataPreview.Fields(Book(), Record(series: "Momo", position: 1m)).Select(f => f.Key).ToList();

        Assert.Equal(keys.OrderBy(k => MetadataLocks.All.ToList().IndexOf(k)), keys);
    }

    private static Book InSeries(string name, decimal position) => new()
    {
        Id = 7,
        Title = "Momo",
        SeriesId = 1,
        Series = new Series { Id = 1, Name = name },
        SeriesPosition = position,
        Authors = [new Author { Name = "Michael Ende" }],
        Narrators = [new Narrator { Name = "Gert Westphal" }]
    };

    /// <summary>
    /// The catalogue names the series differently: the name is offered as a
    /// change, the number -- the same -- is not.
    /// </summary>
    [Fact]
    public void The_series_name_is_offered_on_its_own()
    {
        var fields = MetadataPreview.Fields(InSeries("Die drei Fragezeichen", 5m), Record(series: "Die drei ???", position: 5m));

        var series = Field(fields, MetadataLocks.Series);
        Assert.Equal("Die drei Fragezeichen", series.Current);
        Assert.Equal("Die drei ???", series.Proposed);
        Assert.False(series.Preselected);
        Assert.DoesNotContain(fields, f => f.Key == MetadataLocks.Volume);
    }

    /// <summary>The case the split is for: same series, different number.</summary>
    [Fact]
    public void The_volume_is_offered_on_its_own()
    {
        var fields = MetadataPreview.Fields(InSeries("Momo", 2m), Record(series: "Momo", position: 1m));

        var volume = Field(fields, MetadataLocks.Volume);
        Assert.Equal("2", volume.Current);
        Assert.Equal("1", volume.Proposed);
        Assert.False(volume.Preselected);
        Assert.DoesNotContain(fields, f => f.Key == MetadataLocks.Series);
    }

    [Fact]
    public void A_book_in_no_series_gets_both_ticked()
    {
        var fields = MetadataPreview.Fields(Book(), Record(series: "Momo", position: 1m));

        Assert.True(Field(fields, MetadataLocks.Series).Preselected);
        Assert.True(Field(fields, MetadataLocks.Volume).Preselected);
        Assert.Null(Field(fields, MetadataLocks.Volume).Current);
    }

    [Theory]
    [InlineData(3.0, "3")]
    [InlineData(2.5, "2,5")]
    public void Writes_a_volume_number(double position, string expected)
    {
        Assert.Equal(expected, MetadataPreview.FormatVolume((decimal)position));
    }

    [Theory]
    [InlineData("Harry Potter", 3.0, "Harry Potter · Band 3")]
    [InlineData("Reihe", 2.5, "Reihe · Band 2,5")]
    public void Names_the_volume_of_a_series(string name, double position, string expected)
    {
        Assert.Equal(expected, MetadataPreview.SeriesLabel(name, (decimal)position));
    }

    [Fact]
    public void A_series_without_a_volume_is_just_its_name()
    {
        Assert.Equal("Reihe", MetadataPreview.SeriesLabel("Reihe", null));
        Assert.Null(MetadataPreview.SeriesLabel(null, 3m));
    }
}
