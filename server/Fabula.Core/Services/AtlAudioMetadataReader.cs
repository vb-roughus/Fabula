using System.Globalization;
using System.Text.RegularExpressions;
using ATL;

namespace Fabula.Core.Services;

public class AtlAudioMetadataReader : IAudioMetadataReader
{
    public AudioMetadata Read(string filePath)
    {
        var track = new Track(filePath);

        var authors = SplitPeople(FirstNonEmpty(track.AlbumArtist, track.Artist));
        var narratorRaw = FirstNonEmpty(
            GetAdditionalField(track, "narrator", "NARRATOR", "----:com.apple.iTunes:NARRATOR"),
            track.OriginalArtist,
            track.Composer);
        var narrators = SplitPeople(narratorRaw)
            .Where(n => !authors.Contains(n, StringComparer.OrdinalIgnoreCase))
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToList();

        var (seriesName, seriesPosition) = ExtractSeries(track);

        var duration = TimeSpan.FromMilliseconds(track.DurationMs);

        var chapters = track.Chapters?
            .Where(c => c.EndTime > c.StartTime || c.StartTime == 0)
            .Select((c, i) => new ChapterInfo(
                Title: string.IsNullOrWhiteSpace(c.Title) ? $"Chapter {i + 1}" : c.Title,
                Start: TimeSpan.FromMilliseconds(c.StartTime),
                End: c.EndTime > c.StartTime ? TimeSpan.FromMilliseconds(c.EndTime) : duration))
            .ToList() ?? [];

        var cover = track.EmbeddedPictures?.FirstOrDefault();

        return new AudioMetadata(
            Title: track.Title,
            Album: track.Album,
            Subtitle: GetAdditionalField(track, "subtitle", "SUBTITLE"),
            Authors: authors,
            Narrators: narrators,
            SeriesName: seriesName,
            SeriesPosition: seriesPosition,
            Description: string.IsNullOrWhiteSpace(track.Description) ? track.Comment : track.Description,
            Language: GetAdditionalField(track, "language", "LANGUAGE"),
            Publisher: track.Publisher,
            Year: track.Year > 0 ? track.Year : null,
            Isbn: GetAdditionalField(track, "isbn", "ISBN"),
            Asin: GetAdditionalField(track, "asin", "ASIN"),
            Duration: duration,
            Codec: track.AudioFormat?.ShortName,
            BitrateKbps: track.Bitrate > 0 ? track.Bitrate : (int?)null,
            SampleRate: track.SampleRate > 0 ? (int)track.SampleRate : (int?)null,
            CoverImage: cover?.PictureData,
            CoverMimeType: cover?.MimeType,
            Chapters: chapters);
    }

    private static string? FirstNonEmpty(params string?[] values)
    {
        foreach (var v in values)
            if (!string.IsNullOrWhiteSpace(v)) return v;
        return null;
    }

    private static List<string> SplitPeople(string? raw)
    {
        if (string.IsNullOrWhiteSpace(raw)) return [];
        return raw
            .Split(['/', ';', ',', '&'], StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .Where(s => !string.IsNullOrWhiteSpace(s))
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToList();
    }

private static (string? Name, decimal? Position) ExtractSeries(Track track)
    {
        var seriesField = GetAdditionalField(track, "series", "SERIES", "----:com.apple.iTunes:SERIES", "MVNM");
        var positionField = GetAdditionalField(track, "series-part", "SERIES-PART", "----:com.apple.iTunes:SERIES-PART", "MVIN");

        if (!string.IsNullOrWhiteSpace(seriesField))
        {
            decimal? pos = null;
            if (!string.IsNullOrWhiteSpace(positionField) &&
                decimal.TryParse(positionField, NumberStyles.Number, CultureInfo.InvariantCulture, out var p))
                pos = p;
            return (seriesField.Trim(), pos);
        }

        if (!string.IsNullOrWhiteSpace(track.Group))
        {
            var match = Regex.Match(track.Group, @"^(?<name>.*?)(?:\s*#\s*(?<pos>\d+(?:\.\d+)?))?$");
            if (match.Success)
            {
                var name = match.Groups["name"].Value.Trim();
                decimal? pos = decimal.TryParse(match.Groups["pos"].Value,
                    NumberStyles.Number, CultureInfo.InvariantCulture, out var p) ? p : null;
                return (string.IsNullOrEmpty(name) ? null : name, pos);
            }
        }

        return (null, null);
    }

    private static string? GetAdditionalField(Track track, params string[] keys)
    {
        if (track.AdditionalFields is null) return null;
        foreach (var key in keys)
        {
            foreach (var kvp in track.AdditionalFields)
            {
                if (string.Equals(kvp.Key, key, StringComparison.OrdinalIgnoreCase) && !string.IsNullOrWhiteSpace(kvp.Value))
                    return kvp.Value;
            }
        }
        return null;
    }
}
