namespace Fabula.Core.Services;

/// <summary>
/// What a book's files say about it, worked out the way a scan does: the
/// album or title tag, the folder's or the embedded cover, the series from
/// the folders or the tags, the chapters from the files.
/// </summary>
public sealed record TagValues(
    string Title,
    string? Subtitle,
    string? Description,
    string? Language,
    string? Publisher,
    int? Year,
    string? Isbn,
    string? Asin,
    IReadOnlyList<string> Authors,
    IReadOnlyList<string> Narrators,
    string? SeriesName,
    decimal? SeriesPosition,
    IReadOnlyList<ChapterInfo> Chapters,
    string? CoverPath);

/// <summary>
/// The fields a restore released, and whether the files could be read for
/// them. Unreadable files still release the locks; the values then come back
/// with the next scan that reads the files.
/// </summary>
public sealed record RestoreResult(IReadOnlyList<string> Released, bool FilesRead);
