using Fabula.Api.Endpoints;
using Fabula.Core.Services;

namespace Fabula.Api.Infrastructure.Metadata;

/// <summary>
/// What each metadata field is called in the clients. One place, so the
/// comparison and the list of locked fields always say the same.
/// </summary>
public static class MetadataLabels
{
    private static readonly Dictionary<string, string> Labels = new()
    {
        [MetadataLocks.Title] = "Titel",
        [MetadataLocks.Subtitle] = "Untertitel",
        [MetadataLocks.Authors] = "Autor",
        [MetadataLocks.Narrators] = "Sprecher",
        [MetadataLocks.Series] = "Serie",
        [MetadataLocks.Volume] = "Band",
        [MetadataLocks.Description] = "Beschreibung",
        [MetadataLocks.Chapters] = "Kapitel",
        [MetadataLocks.Publisher] = "Verlag",
        [MetadataLocks.Year] = "Jahr",
        [MetadataLocks.Language] = "Sprache",
        [MetadataLocks.Isbn] = "ISBN",
        [MetadataLocks.Asin] = "ASIN",
        [MetadataLocks.Cover] = "Cover"
    };

    public static string For(string key) => Labels.TryGetValue(key, out var label) ? label : key;

    /// <summary>A book's locked fields, labelled and in display order.</summary>
    public static List<LockedFieldDto> Locked(string? stored)
    {
        var locks = MetadataLocks.Parse(stored);
        return MetadataLocks.All
            .Where(locks.Contains)
            .Select(key => new LockedFieldDto(key, For(key)))
            .ToList();
    }
}
