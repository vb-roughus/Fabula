using Fabula.Api.Infrastructure.Metadata;
using Xunit;

namespace Fabula.Tests;

/// <summary>
/// Placing a catalogue's chapter marks on a book's audio -- or declining to.
///
/// The catalogue below is shaped like Audible's: a three-second jingle at the
/// start inside the first chapter, a five-second one at the end inside the
/// last, one hour of book in between.
/// </summary>
public class ChapterFitTests
{
    private static TimeSpan S(double seconds) => TimeSpan.FromSeconds(seconds);

    private static CatalogChapters Audible() => new(
        [
            new CatalogChapter("Kapitel 1", S(0), S(1203)),
            new CatalogChapter("Kapitel 2", S(1203), S(1200)),
            new CatalogChapter("Kapitel 3", S(2403), S(1205))
        ],
        Runtime: S(3608),
        BrandIntro: S(3),
        BrandOutro: S(5));

    private static List<(string Title, double Start, double End)> Marks(ChapterFitResult fit) =>
        fit.Chapters!.Select(c => (c.Title, c.Start.TotalSeconds, c.End.TotalSeconds)).ToList();

    /// <summary>A file taken from Audible as it is: the marks fit unmoved.</summary>
    [Fact]
    public void Marks_fit_a_file_with_the_jingles_as_they_are()
    {
        var fit = ChapterFit.Fit(Audible(), S(3608))!;

        Assert.Equal(TimeSpan.Zero, fit.Shift);
        Assert.Equal(
            new[] { ("Kapitel 1", 0.0, 1203.0), ("Kapitel 2", 1203.0, 2403.0), ("Kapitel 3", 2403.0, 3608.0) },
            Marks(fit));
    }

    /// <summary>
    /// The reason the jingles matter: without the intro, every mark would be
    /// three seconds late in this file. It is moved forward instead.
    /// </summary>
    [Fact]
    public void Marks_move_forward_for_a_file_without_the_jingles()
    {
        var fit = ChapterFit.Fit(Audible(), S(3600))!;

        Assert.Equal(S(-3), fit.Shift);
        Assert.Equal(
            new[] { ("Kapitel 1", 0.0, 1200.0), ("Kapitel 2", 1200.0, 2400.0), ("Kapitel 3", 2400.0, 3600.0) },
            Marks(fit));
    }

    [Fact]
    public void A_file_with_only_the_intro_removed_fits_as_well()
    {
        var fit = ChapterFit.Fit(Audible(), S(3605))!;

        Assert.Equal(S(-3), fit.Shift);
        Assert.Equal(TimeSpan.Zero, fit.Deviation);
    }

    [Fact]
    public void A_few_seconds_either_way_still_fit_and_end_with_the_audio()
    {
        var fit = ChapterFit.Fit(Audible(), S(3614))!;

        Assert.NotNull(fit.Chapters);
        Assert.Equal(S(6), fit.Deviation);
        Assert.Equal(3614.0, fit.Chapters![^1].End.TotalSeconds);
    }

    /// <summary>
    /// Another recording entirely -- a different reading, a CD rip with
    /// pauses. Its chapters would point at the wrong sentences.
    /// </summary>
    [Fact]
    public void A_recording_of_another_length_gets_no_chapters()
    {
        var fit = ChapterFit.Fit(Audible(), S(3500))!;

        Assert.Null(fit.Chapters);
        Assert.Equal(S(100), fit.Deviation);
    }

    /// <summary>
    /// The reported case: a few seconds beyond the tolerance. Still offered,
    /// but as approximate, with the direction recorded.
    /// </summary>
    [Fact]
    public void A_near_miss_is_offered_as_approximate()
    {
        var fit = ChapterFit.Fit(Audible(), S(3620))!;

        Assert.NotNull(fit.Chapters);
        Assert.False(fit.Exact);
        Assert.Equal(S(12), fit.Deviation);
        Assert.Equal(S(12), fit.Difference);
        Assert.Equal(JingleComparison.WithJingles, fit.Comparison);
        Assert.Equal(3620.0, fit.Chapters![^1].End.TotalSeconds);
    }

    /// <summary>
    /// Where the extra seconds sit is unknown, so they are spread: a mark a
    /// third of the way in moves by a third of the difference.
    /// </summary>
    [Fact]
    public void A_near_miss_is_spread_over_the_whole_book()
    {
        var source = new CatalogChapters(
            [
                new CatalogChapter("A", S(0), S(1200)),
                new CatalogChapter("B", S(1200), S(1200)),
                new CatalogChapter("C", S(2400), S(1200))
            ],
            Runtime: S(3600), BrandIntro: TimeSpan.Zero, BrandOutro: TimeSpan.Zero);

        var chapters = ChapterFit.Fit(source, S(3630))!.Chapters!;

        Assert.Equal(1210.0, chapters[1].Start.TotalSeconds, precision: 3);
        Assert.Equal(2420.0, chapters[2].Start.TotalSeconds, precision: 3);
        Assert.Equal(3630.0, chapters[2].End.TotalSeconds, precision: 3);
    }

    [Fact]
    public void Within_the_tolerance_nothing_is_spread()
    {
        var fit = ChapterFit.Fit(Audible(), S(3614))!;

        Assert.True(fit.Exact);
        Assert.Equal(1203.0, fit.Chapters![1].Start.TotalSeconds);
    }

    [Fact]
    public void More_than_a_minute_is_another_recording()
    {
        var fit = ChapterFit.Fit(Audible(), S(3608 + 61))!;

        Assert.Null(fit.Chapters);
        Assert.Equal(S(61), fit.Difference);
    }

    /// <summary>
    /// Without the jingles' lengths, a file that lacks them looks merely
    /// shorter -- the result says so, so the note can.
    /// </summary>
    [Fact]
    public void Unknown_jingles_are_recorded()
    {
        var withoutLengths = Audible() with { BrandIntro = TimeSpan.Zero, BrandOutro = TimeSpan.Zero };

        var fit = ChapterFit.Fit(withoutLengths, S(3596))!;

        Assert.False(fit.JinglesKnown);
        Assert.Equal(S(-12), fit.Difference);
        Assert.False(fit.Exact);
    }

    [Fact]
    public void A_comparison_without_the_jingles_is_recorded()
    {
        var fit = ChapterFit.Fit(Audible(), S(3600))!;

        Assert.Equal(JingleComparison.WithoutJingles, fit.Comparison);
        Assert.True(fit.JinglesKnown);
    }

    [Fact]
    public void A_jingle_that_was_its_own_chapter_disappears_with_the_jingle()
    {
        var source = new CatalogChapters(
            [
                new CatalogChapter("Audible präsentiert", S(0), S(3)),
                new CatalogChapter("Kapitel 1", S(3), S(1800)),
                new CatalogChapter("Kapitel 2", S(1803), S(1800))
            ],
            Runtime: S(3603),
            BrandIntro: S(3),
            BrandOutro: TimeSpan.Zero);

        var fit = ChapterFit.Fit(source, S(3600))!;

        Assert.Equal(new[] { "Kapitel 1", "Kapitel 2" }, fit.Chapters!.Select(c => c.Title));
        Assert.Equal(TimeSpan.Zero, fit.Chapters![0].Start);
    }

    [Fact]
    public void An_untitled_chapter_gets_a_number()
    {
        var source = new CatalogChapters(
            [new CatalogChapter(" ", S(0), S(60)), new CatalogChapter(null, S(60), S(60))],
            Runtime: S(120), BrandIntro: TimeSpan.Zero, BrandOutro: TimeSpan.Zero);

        var fit = ChapterFit.Fit(source, S(120))!;

        Assert.Equal(new[] { "Kapitel 1", "Kapitel 2" }, fit.Chapters!.Select(c => c.Title));
    }

    /// <summary>Gaps or overlaps between catalogue marks must not survive into the player.</summary>
    [Fact]
    public void Chapters_are_laid_end_to_end()
    {
        var source = new CatalogChapters(
            [new CatalogChapter("A", S(0), S(55)), new CatalogChapter("B", S(60), S(70))],
            Runtime: S(130), BrandIntro: TimeSpan.Zero, BrandOutro: TimeSpan.Zero);

        var chapters = ChapterFit.Fit(source, S(130))!.Chapters!;

        Assert.Equal(chapters[1].Start, chapters[0].End);
        Assert.Equal(S(130), chapters[1].End);
    }

    [Fact]
    public void Without_a_runtime_the_end_of_the_last_chapter_is_the_length()
    {
        var source = new CatalogChapters(
            [new CatalogChapter("A", S(0), S(60)), new CatalogChapter("B", S(60), S(60))],
            Runtime: null, BrandIntro: TimeSpan.Zero, BrandOutro: TimeSpan.Zero);

        Assert.NotNull(ChapterFit.Fit(source, S(120))!.Chapters);
    }

    [Fact]
    public void Nothing_to_place_is_no_result()
    {
        var empty = new CatalogChapters([], S(60), TimeSpan.Zero, TimeSpan.Zero);

        Assert.Null(ChapterFit.Fit(empty, S(60)));
        Assert.Null(ChapterFit.Fit(Audible(), TimeSpan.Zero));
    }
}
