namespace Fabula.Core.Services;

/// <summary>
/// The book fields an admin can take over from an online catalogue, and the
/// record of which ones were.
///
/// A locked field is one the library scanner leaves alone. Without that, the
/// next rescan of a book whose files changed would put the old tag values
/// straight back -- quietly undoing exactly the correction that was made.
///
/// Stored as one comma-separated column rather than a flag per field: the set
/// of fields is small, and a new one then needs no migration.
/// </summary>
public static class MetadataLocks
{
    public const string Title = "title";
    public const string Subtitle = "subtitle";
    public const string Description = "description";
    public const string Authors = "authors";
    public const string Narrators = "narrators";
    /// <summary>Which series the book belongs to -- the name, not the volume.</summary>
    public const string Series = "series";

    /// <summary>
    /// The book's volume number within its series. Separate from
    /// <see cref="Series"/> so a catalogue's number can be taken while the
    /// book stays in a series that the catalogue names differently.
    /// </summary>
    public const string Volume = "volume";

    /// <summary>
    /// Chapter marks from a catalogue. Kept only while the audio stays the
    /// length they were fitted to: see <see cref="ChapterTolerance"/>.
    /// </summary>
    public const string Chapters = "chapters";
    public const string Publisher = "publisher";
    public const string Year = "year";
    public const string Language = "language";
    public const string Isbn = "isbn";
    public const string Asin = "asin";
    public const string Cover = "cover";

    private static readonly string[] Ordered =
    [
        Title, Subtitle, Authors, Narrators, Series, Volume, Description, Chapters,
        Publisher, Year, Language, Isbn, Asin, Cover
    ];

    /// <summary>Every field that can be taken over, in the order they are shown.</summary>
    public static IReadOnlyList<string> All => Ordered;

    private static bool IsKnown(string key) => Array.IndexOf(Ordered, key) >= 0;

    /// <summary>The locked fields of a stored value. Unknown keys are dropped.</summary>
    public static IReadOnlySet<string> Parse(string? stored)
    {
        if (string.IsNullOrWhiteSpace(stored)) return new HashSet<string>();
        return stored
            .Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .Select(k => k.ToLowerInvariant())
            .Where(IsKnown)
            .ToHashSet();
    }

    /// <summary>The value to store for a set of locked fields; null when there are none.</summary>
    public static string? Format(IEnumerable<string> keys)
    {
        var known = keys
            .Select(k => k.ToLowerInvariant())
            .Where(IsKnown)
            .Distinct()
            .OrderBy(k => Array.IndexOf(Ordered, k))
            .ToList();
        return known.Count == 0 ? null : string.Join(",", known);
    }

    /// <summary>Adds <paramref name="keys"/> to what is locked already.</summary>
    public static string? Add(string? stored, IEnumerable<string> keys) =>
        Format(Parse(stored).Concat(keys));

    /// <summary>Drops <paramref name="key"/> from what is locked.</summary>
    public static string? Remove(string? stored, string key) =>
        Format(Parse(stored).Where(k => k != key));

    /// <summary>
    /// How far a book's length may be from a catalogue's for its chapter marks
    /// to count as fitting. Chapters are positions in the audio; on a recording
    /// of a different length they point at the wrong sentences, so the bar is
    /// seconds, not the percentage that is enough to tell editions apart.
    /// </summary>
    public static readonly TimeSpan ChapterTolerance = TimeSpan.FromSeconds(10);
}
