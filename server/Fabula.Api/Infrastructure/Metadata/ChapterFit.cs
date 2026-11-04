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

/// <summary>
/// How a catalogue's chapters sit on this book's audio. <see cref="Chapters"/>
/// is null when they do not fit; <see cref="Deviation"/> says by how much the
/// lengths differ either way.
/// </summary>
public sealed record ChapterFitResult(
    IReadOnlyList<ChapterInfo>? Chapters,
    TimeSpan Deviation,
    TimeSpan Shift);

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
    /// <summary>Shorter than this after shifting, a chapter is a remnant of the jingle and is dropped.</summary>
    private static readonly TimeSpan MinimumChapter = TimeSpan.FromSeconds(1);

    public static ChapterFitResult? Fit(CatalogChapters source, TimeSpan bookDuration)
    {
        if (source.Chapters.Count == 0 || bookDuration <= TimeSpan.Zero) return null;

        var last = source.Chapters[^1];
        var total = source.Runtime is { } runtime && runtime > TimeSpan.Zero
            ? runtime
            : last.Start + last.Length;

        // (shift applied to every mark, length the book would then have)
        var variants = new List<(TimeSpan Shift, TimeSpan Expected)> { (TimeSpan.Zero, total) };
        if (source.BrandIntro > TimeSpan.Zero || source.BrandOutro > TimeSpan.Zero)
            variants.Add((-source.BrandIntro, total - source.BrandIntro - source.BrandOutro));
        if (source.BrandIntro > TimeSpan.Zero)
            variants.Add((-source.BrandIntro, total - source.BrandIntro));
        if (source.BrandOutro > TimeSpan.Zero)
            variants.Add((TimeSpan.Zero, total - source.BrandOutro));

        var best = variants
            .Select(v => (v.Shift, Deviation: (bookDuration - v.Expected).Duration()))
            .OrderBy(v => v.Deviation)
            .First();

        if (best.Deviation > MetadataLocks.ChapterTolerance)
            return new ChapterFitResult(null, best.Deviation, best.Shift);

        return new ChapterFitResult(Place(source.Chapters, best.Shift, bookDuration), best.Deviation, best.Shift);
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
        TimeSpan bookDuration)
    {
        var starts = new List<(string Title, TimeSpan Start, TimeSpan End)>();
        foreach (var chapter in chapters.OrderBy(c => c.Start))
        {
            var start = Clamp(chapter.Start + shift, bookDuration);
            var end = Clamp(chapter.Start + chapter.Length + shift, bookDuration);
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

    private static TimeSpan Clamp(TimeSpan value, TimeSpan max) =>
        value < TimeSpan.Zero ? TimeSpan.Zero : value > max ? max : value;
}
