using Fabula.Data;
using Microsoft.EntityFrameworkCore;

namespace Fabula.Api.Infrastructure;

/// <summary>
/// Finding books by what the listener remembers of them: part of the title, an
/// author, the narrator, the series.
///
/// Matching happens here rather than in SQL, and that is deliberate. SQLite's
/// LIKE folds the case of ASCII letters only, so a search for "über" never
/// found "Über den Wolken" -- a defect that matters in a German library and
/// that no collation shipped with SQLite fixes. Comparing in memory with
/// <see cref="StringComparison.OrdinalIgnoreCase"/> folds umlauts correctly.
/// Only a light projection (names, no covers, no files) is read for that, which
/// stays cheap for a personal library of thousands of titles.
/// </summary>
public static class BookSearch
{
    /// <summary>
    /// The words of a search. Each has to match somewhere, so "Rowling Stein"
    /// finds a title by that author without the words having to sit next to
    /// each other -- or in the same field.
    /// </summary>
    public static string[] Tokens(string? search) =>
        (search ?? string.Empty)
            .Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToArray();

    /// <summary>Whether every token is found in at least one of the fields.</summary>
    public static bool Matches(IReadOnlyList<string> tokens, IEnumerable<string?> fields)
    {
        if (tokens.Count == 0) return true;
        var haystack = fields.Where(f => !string.IsNullOrEmpty(f)).Select(f => f!).ToList();
        return tokens.All(token =>
            haystack.Any(field => field.Contains(token, StringComparison.OrdinalIgnoreCase)));
    }

    internal sealed record Row(
        int Id,
        string Title,
        string? Subtitle,
        List<string> Authors,
        List<string> Narrators,
        string? Series);

    /// <summary>Ids of the books matching <paramref name="search"/>, in no particular order.</summary>
    public static async Task<List<int>> MatchingIdsAsync(
        FabulaDbContext db, string search, CancellationToken ct)
    {
        var tokens = Tokens(search);

        var rows = await db.Books
            .AsNoTracking()
            .Select(b => new Row(
                b.Id,
                b.Title,
                b.Subtitle,
                b.Authors.Select(a => a.Name).ToList(),
                b.Narrators.Select(n => n.Name).ToList(),
                b.Series != null ? b.Series.Name : null))
            .ToListAsync(ct);

        return rows
            .Where(r => Matches(tokens, Fields(r)))
            .Select(r => r.Id)
            .ToList();
    }

    private static IEnumerable<string?> Fields(Row r) =>
        new[] { r.Title, r.Subtitle, r.Series }
            .Concat(r.Authors)
            .Concat(r.Narrators);
}
