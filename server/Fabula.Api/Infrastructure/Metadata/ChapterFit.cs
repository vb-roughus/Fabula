using Fabula.Core.Services;

namespace Fabula.Api.Infrastructure.Metadata;

/// <summary>One chapter as a catalogue lists it, relative to the catalogue's own audio.</summary>
public sealed record CatalogChapter(string? Title, TimeSpan Start, TimeSpan Length);

/// <summary>
/// A catalogue's chapter list. <see cref="BrandIntro"/> and
/// <see cref="BrandOutro"/> are the "Audible präsentiert" jingles at either
/// end, which Audible's own audio contains and a file from elsewhere may not.
/// </summary>
public sealed record CatalogChapters(
    IReadOnlyList<CatalogChapter> Chapters,
    TimeSpan? Runtime,
    TimeSpan BrandIntro,
    TimeSpan BrandOutro);

/// <summary>How the book's length was compared with Audible's, as to the jingles.</summary>
public enum JingleComparison
{
    WithJingles,
    WithoutJingles,
    WithoutIntro,
    WithoutOutro
}

/// <summary>
/// How a catalogue's chapters sit on this book's audio.
///
/// <see cref="Chapters"/> is null when they do not fit at all.
/// <see cref="Exact"/> is false for a near miss: offered, but only roughly
/// right. <see cref="Difference"/> is the book's length minus the catalogue's
/// -- positive when the file is longer -- and <see cref="Deviation"/> its size.
/// <see cref="JinglesKnown"/> says whether the catalogue gave the jingles'
/// lengths at all; without them, a file lacking the jingles cannot be told
/// from one that is simply shorter.
/// </summary>
public sealed record ChapterFitResult(
    IReadOnlyList<ChapterInfo>? Chapters,
    TimeSpan Deviation,
    TimeSpan Shift,
    TimeSpan Difference = default,
    bool Exact = true,
    JingleComparison Comparison = JingleComparison.WithJingles,
    bool JinglesKnown = true);

/// <summary>
/// Placing a catalogue's chapter marks on a book's audio.
///
/// Chapter marks are positions. They only mean something on a recording of
/// the same length, so they are offered only when the lengths agree to within
/// <see cref="MetadataLocks.ChapterTolerance"/>. The one known, explainable
/// difference is Audible's jingles: a file without the intro has every mark a
/// few seconds early. So the lengths are compared as-is and with the jingles
/// removed, and whichever fits best decides the shift.
/// </summary>
public static class ChapterFit
{
    /// <summary>
    /// Beyond the tolerance and up to this, the marks are still offered -- as
    /// approximate, never preselected. Spoken publisher announcements, silence
    /// at either end or a few seconds per track add up to this much on the
    /// same recording; more than a minute is a different one.
    /// </summary>
    public static readonly TimeSpan NearMissLimit = TimeSpan.FromSeconds(60);

    /// <summary>Shorter than this after shifting, a chapter is a remnant of the jingle and is dropped.</summary>
    private static readonly TimeSpan MinimumChapter = TimeSpan.FromSeconds(1);

    public static ChapterFitResult? Fit(CatalogChapters source, TimeSpan bookDuration)
    {
        if (source.Chapters.Count == 0 || bookDuration <= TimeSpan.Zero) return null;

        var last = source.Chapters[^1];
        var total = source.Runtime is { } runtime && runtime > TimeSpan.Zero
            ? runtime
            : last.Start + last.Length;

        // (shift applied to every mark, length the book would then have, how)
        var variants = new List<(TimeSpan Shift, TimeSpan Expected, JingleComparison How)>
        {
            (TimeSpan.Zero, total, JingleComparison.WithJingles)
        };
        if (source.BrandIntro > TimeSpan.Zero || source.BrandOutro > TimeSpan.Zero)
            variants.Add((-source.BrandIntro, total - source.BrandIntro - source.BrandOutro, JingleComparison.WithoutJingles));
        if (source.BrandIntro > TimeSpan.Zero)
            variants.Add((-source.BrandIntro, total - source.BrandIntro, JingleComparison.WithoutIntro));
        if (source.BrandOutro > TimeSpan.Zero)
            variants.Add((TimeSpan.Zero, total - source.BrandOutro, JingleComparison.WithoutOutro));

        var best = variants
            .Select(v => (v.Shift, v.Expected, v.How, Difference: bookDuration - v.Expected))
            .OrderBy(v => v.Difference.Duration())
            .First();

        var deviation = best.Difference.Duration();
        var jinglesKnown = source.BrandIntro > TimeSpan.Zero || source.BrandOutro > TimeSpan.Zero;

        if (deviation > NearMissLimit)
            return new ChapterFitResult(null, deviation, best.Shift, best.Difference, false, best.How, jinglesKnown);

        // Within the tolerance the marks are taken as they are, and the few
        // seconds land at the very end. A near miss has a difference whose
        // place is unknown -- an announcement at the start, silence at the
        // end, a little per track -- so it is spread over the whole book:
        // every mark is then off by at most the difference, and on average by
        // half of it, wherever it really sits.
        var exact = deviation <= MetadataLocks.ChapterTolerance;
        var scale = exact || best.Expected <= TimeSpan.Zero
            ? 1.0
            : bookDuration.TotalMilliseconds / best.Expected.TotalMilliseconds;

        return new ChapterFitResult(
            Place(source.Chapters, best.Shift, bookDuration, scale),
            deviation, best.Shift, best.Difference, exact, best.How, jinglesKnown);
    }

    /// <summary>
    /// The marks moved by <paramref name="shift"/> and laid end to end over the
    /// whole book: each chapter ends where the next begins, the first starts at
    /// zero and the last ends with the audio. The few seconds of tolerance thus
    /// land at the very end, not as a gap or an overlap somewhere in the middle.
    /// </summary>
    private static IReadOnlyList<ChapterInfo> Place(
        IReadOnlyList<CatalogChapter> chapters,
        TimeSpan shift,
        TimeSpan bookDuration,
        double scale)
    {
        var starts = new List<(string Title, TimeSpan Start, TimeSpan End)>();
        foreach (var chapter in chapters.OrderBy(c => c.Start))
        {
            var start = Clamp(Scale(chapter.Start + shift, scale), bookDuration);
            var end = Clamp(Scale(chapter.Start + chapter.Length + shift, scale), bookDuration);
            if (end - start < MinimumChapter) continue;
            var title = string.IsNullOrWhiteSpace(chapter.Title) ? $"Kapitel {starts.Count + 1}" : chapter.Title.Trim();
            starts.Add((title, start, end));
        }

        var placed = new List<ChapterInfo>(starts.Count);
        for (var i = 0; i < starts.Count; i++)
        {
            var start = i == 0 ? TimeSpan.Zero : starts[i].Start;
            var end = i + 1 < starts.Count ? starts[i + 1].Start : bookDuration;
            if (end <= start) continue;
            placed.Add(new ChapterInfo(starts[i].Title, start, end));
        }
        return placed;
    }

    private static TimeSpan Scale(TimeSpan value, double factor) =>
        factor == 1.0 ? value : TimeSpan.FromTicks((long)Math.Round(value.Ticks * factor));

    private static TimeSpan Clamp(TimeSpan value, TimeSpan max) =>
        value < TimeSpan.Zero ? TimeSpan.Zero : value > max ? max : value;
}
