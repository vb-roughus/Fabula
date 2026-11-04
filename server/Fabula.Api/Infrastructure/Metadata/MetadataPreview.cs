using System.Globalization;
using Fabula.Core.Domain;
using Fabula.Core.Services;

namespace Fabula.Api.Infrastructure.Metadata;

/// <summary>One field as it is and as the catalogue would have it.</summary>
public sealed record MetadataField(string Key, string Label, string? Current, string? Proposed, bool Preselected);

/// <summary>
/// Comparing a book with a catalogue record: which edition fits, and what
/// taking it over would change.
/// </summary>
public static class MetadataPreview
{
    /// <summary>
    /// Whether a catalogue runtime matches the book's own length.
    ///
    /// The strongest sign of the right edition: a title search returns the
    /// abridged and the unabridged reading, older and newer recordings, and
    /// their runtimes differ by hours while the same recording differs by
    /// seconds of intro. Tolerance is 2 %, at least three minutes.
    /// </summary>
    public static bool RuntimeMatches(int? runtimeMinutes, TimeSpan duration)
    {
        if (runtimeMinutes is not > 0 || duration <= TimeSpan.Zero) return false;
        var ours = duration.TotalMinutes;
        var tolerance = Math.Max(3.0, ours * 0.02);
        return Math.Abs(runtimeMinutes.Value - ours) <= tolerance;
    }

    /// <summary>
    /// Candidates whose runtime matches first, closest first; the rest after
    /// them in the catalogue's own order.
    /// </summary>
    public static IReadOnlyList<CatalogBook> Rank(IReadOnlyList<CatalogBook> candidates, TimeSpan duration) =>
        candidates
            .Select((book, index) => (book, index, matches: RuntimeMatches(book.RuntimeMinutes, duration)))
            .OrderBy(x => x.matches ? 0 : 1)
            .ThenBy(x => x.matches ? Math.Abs(x.book.RuntimeMinutes!.Value - duration.TotalMinutes) : 0)
            .ThenBy(x => x.index)
            .Select(x => x.book)
            .ToList();

    /// <summary>
    /// The fields a catalogue record would change, in display order.
    ///
    /// A field the record has nothing for is left out -- there is nothing to
    /// take -- and so is one that already says the same. What remains is
    /// preselected only where the book has nothing yet: filling gaps is safe,
    /// replacing what is there is the admin's call.
    /// </summary>
    public static IReadOnlyList<MetadataField> Fields(
        Book book,
        CatalogBook proposal,
        ChapterFitResult? chapters = null)
    {
        var fields = new List<MetadataField>();

        void Add(string key, string label, string? current, string? proposed, bool mayPreselect = true)
        {
            if (string.IsNullOrWhiteSpace(proposed)) return;
            if (key != MetadataLocks.Cover &&
                string.Equals(current?.Trim(), proposed.Trim(), StringComparison.Ordinal))
                return;
            fields.Add(new MetadataField(
                key, label, current, proposed, mayPreselect && string.IsNullOrWhiteSpace(current)));
        }

        foreach (var key in MetadataLocks.All)
        {
            switch (key)
            {
                case MetadataLocks.Title:
                    Add(key, "Titel", book.Title, proposal.Title);
                    break;
                case MetadataLocks.Subtitle:
                    Add(key, "Untertitel", book.Subtitle, proposal.Subtitle);
                    break;
                case MetadataLocks.Authors:
                    Add(key, "Autor", Join(book.Authors.Select(a => a.Name)), Join(proposal.Authors));
                    break;
                case MetadataLocks.Narrators:
                    Add(key, "Sprecher", Join(book.Narrators.Select(n => n.Name)), Join(proposal.Narrators));
                    break;
                case MetadataLocks.Series:
                    // The name alone: the number is its own field, so it can be
                    // taken while the book keeps a series named differently here.
                    Add(key, "Serie", book.Series?.Name, proposal.SeriesName);
                    break;
                case MetadataLocks.Volume:
                    // A position only means something inside a series; a book in
                    // none has no number to show, whatever the column holds.
                    Add(key, "Band",
                        book.Series is null && book.SeriesId is null ? null : FormatVolume(book.SeriesPosition),
                        FormatVolume(proposal.SeriesPosition));
                    break;
                case MetadataLocks.Description:
                    Add(key, "Beschreibung", book.Description, proposal.Description);
                    break;
                case MetadataLocks.Chapters:
                    // Only marks that fit this book's audio are offered at all;
                    // why others are not is said in a note instead.
                    // A near miss is offered as approximate and never ticked for
                    // the admin: marks that may sit seconds off are a choice.
                    if (chapters?.Chapters is { Count: > 0 } fitted)
                        Add(key, chapters.Exact ? "Kapitel" : "Kapitel (ungefähr)",
                            ChapterSummary(book.Chapters.OrderBy(c => c.Index).Select(c => c.Title).ToList()),
                            ChapterSummary(fitted.Select(c => c.Title).ToList()),
                            mayPreselect: chapters.Exact);
                    break;
                case MetadataLocks.Publisher:
                    Add(key, "Verlag", book.Publisher, proposal.Publisher);
                    break;
                case MetadataLocks.Year:
                    Add(key, "Jahr", book.PublishYear?.ToString(CultureInfo.InvariantCulture),
                        proposal.Year?.ToString(CultureInfo.InvariantCulture));
                    break;
                case MetadataLocks.Language:
                    Add(key, "Sprache", book.Language, proposal.Language);
                    break;
                case MetadataLocks.Isbn:
                    Add(key, "ISBN", book.Isbn, proposal.Isbn);
                    break;
                case MetadataLocks.Asin:
                    Add(key, "ASIN", book.Asin, proposal.Asin);
                    break;
                case MetadataLocks.Cover:
                    // Current and proposed are image URLs here; the clients show them as pictures.
                    Add(key, "Cover", CoverUrls.Book(book.Id, book.CoverPath), proposal.CoverUrl);
                    break;
            }
        }
        return fields;
    }

    /// <summary>
    /// "12 Kapitel: Prolog, Erstes Kapitel, Zweites Kapitel, …". A single
    /// chapter counts as none: it is the whole book, and nothing to navigate.
    /// </summary>
    private static string? ChapterSummary(IReadOnlyList<string> titles)
    {
        if (titles.Count <= 1) return null;
        var sample = string.Join(", ", titles.Take(3));
        return titles.Count > 3 ? $"{titles.Count} Kapitel: {sample}, \u2026" : $"{titles.Count} Kapitel: {sample}";
    }

    /// <summary>
    /// What to tell the admin about chapters beside the fields: why none are
    /// offered, or that the marks were moved to fit.
    /// </summary>
    public static IReadOnlyList<string> ChapterNotes(CatalogChapters? source, ChapterFitResult? fit, bool unavailable)
    {
        if (unavailable) return ["Die Kapitel liessen sich nicht laden."];
        if (source is null) return ["Audible führt für diesen Eintrag keine Kapitel."];
        if (fit is null) return [];

        var notes = new List<string>();
        if (fit.Chapters is null)
        {
            notes.Add($"Kapitel nicht angeboten: {Comparison(fit)} \u2013 wohl eine andere Aufnahme, " +
                      "die Kapitelmarken würden nicht passen.");
        }
        else if (!fit.Exact)
        {
            notes.Add($"Kapitel nur ungefähr: {Comparison(fit)}. Der Unterschied ist über das ganze Buch " +
                      $"verteilt, einzelne Marken können um bis zu {Span(fit.Deviation)} danebenliegen. " +
                      "Darum nicht vorausgewählt.");
        }
        if (fit.Chapters is not null && fit.Shift < TimeSpan.Zero)
            notes.Add($"Kapitelmarken um {Span(-fit.Shift)} vorgezogen: Deiner Datei fehlt das Audible-Intro.");
        if (!fit.JinglesKnown && fit.Deviation > MetadataLocks.ChapterTolerance)
            notes.Add("Audible nennt für diesen Eintrag keine Länge für Intro und Jingle. Fehlen die in " +
                      "deiner Datei, erklärt das einige Sekunden davon.");
        return notes;
    }

    /// <summary>
    /// "Deine Datei ist 12 s länger als bei Audible (ohne Audibles Intro und
    /// Jingle verglichen)" -- the direction and the basis of the comparison,
    /// without which a bare number cannot be checked against the file.
    /// </summary>
    private static string Comparison(ChapterFitResult fit)
    {
        var how = fit.Comparison switch
        {
            JingleComparison.WithoutJingles => "ohne Audibles Intro und Jingle verglichen",
            JingleComparison.WithoutIntro => "ohne Audibles Intro verglichen",
            JingleComparison.WithoutOutro => "ohne Audibles Jingle am Ende verglichen",
            _ => fit.JinglesKnown ? "mit Audibles Intro und Jingle verglichen" : "mit Intro und Jingle verglichen"
        };
        var direction = fit.Difference > TimeSpan.Zero ? "länger"
            : fit.Difference < TimeSpan.Zero ? "kürzer"
            : null;
        return direction is null
            ? $"Die Laufzeit weicht um {Span(fit.Deviation)} ab ({how})"
            : $"Deine Datei ist {Span(fit.Deviation)} {direction} als bei Audible ({how})";
    }

    /// <summary>"1 h 2 min", "4 min 12 s", "3 s".</summary>
    public static string Span(TimeSpan span)
    {
        var total = (long)Math.Round(span.Duration().TotalSeconds);
        var hours = total / 3600;
        var minutes = total % 3600 / 60;
        var seconds = total % 60;
        if (hours > 0) return minutes > 0 ? $"{hours} h {minutes} min" : $"{hours} h";
        if (minutes > 0) return seconds > 0 ? $"{minutes} min {seconds} s" : $"{minutes} min";
        return $"{seconds} s";
    }

    private static string? Join(IEnumerable<string> names)
    {
        var joined = string.Join(", ", names.Where(n => !string.IsNullOrWhiteSpace(n)));
        return joined.Length == 0 ? null : joined;
    }

    /// <summary>"Harry Potter · Band 3", "Reihe · Band 2,5", or just the name.</summary>
    public static string? SeriesLabel(string? name, decimal? position)
    {
        if (string.IsNullOrWhiteSpace(name)) return null;
        var volume = FormatVolume(position);
        return volume is null ? name : $"{name} · Band {volume}";
    }

    /// <summary>A volume number as it is written: "3", "2,5".</summary>
    public static string? FormatVolume(decimal? position)
    {
        if (position is not decimal p) return null;
        return p % 1 == 0
            ? decimal.Truncate(p).ToString(CultureInfo.InvariantCulture)
            : p.ToString("0.##", CultureInfo.InvariantCulture).Replace('.', ',');
    }
}
