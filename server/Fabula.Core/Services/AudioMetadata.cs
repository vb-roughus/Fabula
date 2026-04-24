namespace Fabula.Core.Services;

public record AudioMetadata(
    string? Title,
    string? Album,
    string? Subtitle,
    IReadOnlyList<string> Authors,
    IReadOnlyList<string> Narrators,
    string? SeriesName,
    decimal? SeriesPosition,
    string? Description,
    string? Language,
    string? Publisher,
    int? Year,
    string? Isbn,
    string? Asin,
    TimeSpan Duration,
    string? Codec,
    int? BitrateKbps,
    int? SampleRate,
    byte[]? CoverImage,
    string? CoverMimeType,
    IReadOnlyList<ChapterInfo> Chapters);

public record ChapterInfo(string Title, TimeSpan Start, TimeSpan End);
