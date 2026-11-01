using Fabula.Core.Domain;

namespace Fabula.Core.Services;

/// <summary>
/// Metadata taken over from an online catalogue for one book, together with
/// the fields the admin chose to take.
///
/// Only the chosen fields are written. Everything else on the book -- whatever
/// the tags said, or an earlier correction -- stays as it is.
/// </summary>
public sealed record BookMetadataUpdate
{
    /// <summary>Which fields to write, as <see cref="MetadataLocks"/> keys.</summary>
    public required IReadOnlySet<string> Fields { get; init; }

    public string? Title { get; init; }
    public string? Subtitle { get; init; }
    public string? Description { get; init; }
    public IReadOnlyList<string> Authors { get; init; } = [];
    public IReadOnlyList<string> Narrators { get; init; } = [];
    public string? SeriesName { get; init; }
    public decimal? SeriesPosition { get; init; }
    public string? Publisher { get; init; }
    public int? Year { get; init; }
    public string? Language { get; init; }
    public string? Isbn { get; init; }
    public string? Asin { get; init; }

    /// <summary>Relative cover path in the cover store, once the image is saved.</summary>
    public string? CoverPath { get; init; }

    public bool Takes(string field) => Fields.Contains(field);

    /// <summary>
    /// Writes the chosen plain fields to <paramref name="book"/> and locks them.
    /// Authors, narrators and series are relations, so the repository resolves
    /// those -- but they are locked here too, so the lock list is complete.
    ///
    /// A chosen field whose new value is empty is skipped rather than written:
    /// taking over "nothing" would erase what the tags had, which is never what
    /// ticking the box meant. Skipped fields are not locked either.
    /// </summary>
    /// <returns>The fields that were actually taken.</returns>
    public IReadOnlyList<string> ApplyTo(Book book)
    {
        var taken = new List<string>();

        void Set(string field, string? value, Action<string> write)
        {
            if (!Takes(field) || string.IsNullOrWhiteSpace(value)) return;
            write(value.Trim());
            taken.Add(field);
        }

        Set(MetadataLocks.Title, Title, v => book.Title = Truncate(v, 512));
        Set(MetadataLocks.Subtitle, Subtitle, v => book.Subtitle = Truncate(v, 512));
        Set(MetadataLocks.Description, Description, v => book.Description = v);
        Set(MetadataLocks.Publisher, Publisher, v => book.Publisher = Truncate(v, 256));
        Set(MetadataLocks.Language, Language, v => book.Language = Truncate(v, 16));
        Set(MetadataLocks.Isbn, Isbn, v => book.Isbn = Truncate(v, 32));
        Set(MetadataLocks.Asin, Asin, v => book.Asin = Truncate(v, 32));
        Set(MetadataLocks.Cover, CoverPath, v => book.CoverPath = v);

        if (Takes(MetadataLocks.Year) && Year is > 0)
        {
            book.PublishYear = Year;
            taken.Add(MetadataLocks.Year);
        }

        // Relations: written by the repository, locked here.
        if (Takes(MetadataLocks.Authors) && Authors.Count > 0) taken.Add(MetadataLocks.Authors);
        if (Takes(MetadataLocks.Narrators) && Narrators.Count > 0) taken.Add(MetadataLocks.Narrators);
        if (Takes(MetadataLocks.Series) && !string.IsNullOrWhiteSpace(SeriesName)) taken.Add(MetadataLocks.Series);

        if (taken.Count > 0)
        {
            book.MetadataLocks = MetadataLocks.Add(book.MetadataLocks, taken);
            book.UpdatedAt = DateTime.UtcNow;
        }
        return taken;
    }

    // Column limits from the model; a catalogue title longer than the column
    // would otherwise fail the whole save.
    private static string Truncate(string value, int max) =>
        value.Length <= max ? value : value[..max];
}
