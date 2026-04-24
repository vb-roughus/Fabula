using System.Text.RegularExpressions;

namespace Fabula.Core.Services;

public class TagLibAudioMetadataReader : IAudioMetadataReader
{
    public AudioMetadata Read(string filePath)
    {
        using var file = TagLib.File.Create(filePath);
        var tag = file.Tag;
        var props = file.Properties;

        var authors = (tag.AlbumArtists.Length > 0 ? tag.AlbumArtists : tag.Performers)
            .Concat(tag.Composers)
            .Where(s => !string.IsNullOrWhiteSpace(s))
            .Distinct()
            .ToList();

        var narrators = tag.Performers
            .Where(s => !string.IsNullOrWhiteSpace(s) && !authors.Contains(s))
            .Distinct()
            .ToList();

        var (seriesName, seriesPosition) = ExtractSeries(tag);

        var chapters = ReadChapters(file);

        return new AudioMetadata(
            Title: tag.Title,
            Album: tag.Album,
            Subtitle: tag.Subtitle,
            Authors: authors,
            Narrators: narrators,
            SeriesName: seriesName,
            SeriesPosition: seriesPosition,
            Description: tag.Comment,
            Language: null,
            Publisher: tag.Publisher,
            Year: tag.Year == 0 ? null : (int)tag.Year,
            Isbn: tag.ISRC,
            Asin: null,
            Duration: props.Duration,
            Codec: props.Codecs.FirstOrDefault()?.Description,
            BitrateKbps: props.AudioBitrate == 0 ? null : props.AudioBitrate,
            SampleRate: props.AudioSampleRate == 0 ? null : props.AudioSampleRate,
            CoverImage: tag.Pictures.Length > 0 ? tag.Pictures[0].Data.Data : null,
            CoverMimeType: tag.Pictures.Length > 0 ? tag.Pictures[0].MimeType : null,
            Chapters: chapters);
    }

    private static (string? Name, decimal? Position) ExtractSeries(TagLib.Tag tag)
    {
        if (!string.IsNullOrWhiteSpace(tag.Grouping))
        {
            var match = Regex.Match(tag.Grouping, @"^(?<name>.*?)(?:\s*#\s*(?<pos>\d+(?:\.\d+)?))?$");
            if (match.Success)
            {
                var name = match.Groups["name"].Value.Trim();
                var posStr = match.Groups["pos"].Value;
                decimal? pos = decimal.TryParse(posStr,
                    System.Globalization.NumberStyles.Number,
                    System.Globalization.CultureInfo.InvariantCulture,
                    out var p) ? p : null;
                return (string.IsNullOrEmpty(name) ? null : name, pos);
            }
        }
        return (null, null);
    }

    private static IReadOnlyList<ChapterInfo> ReadChapters(TagLib.File file)
    {
        // Placeholder: full chapter frame parsing (CHAP for MP3, Nero/QuickTime chapters for M4B)
        // will be added later. For now, return empty; scanner falls back to per-file tracks.
        _ = file;
        return Array.Empty<ChapterInfo>();
    }
}
