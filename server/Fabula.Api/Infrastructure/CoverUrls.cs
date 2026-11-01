namespace Fabula.Api.Infrastructure;

/// <summary>
/// The URL a client loads a book's cover from.
///
/// Carries the stored cover's file name as a version. Covers are served
/// anonymously from a fixed address and clients cache them by URL, so without
/// it a replaced cover -- one taken over from a catalogue, say -- stays the old
/// picture in the app until its cache happens to expire. A new file name is a
/// new URL, and the cache misses.
///
/// The book list's database projection builds the same string inline, because
/// it has to translate to SQL.
/// </summary>
public static class CoverUrls
{
    public static string? Book(int bookId, string? coverPath) =>
        coverPath is null ? null : $"/api/books/{bookId}/cover?v={Uri.EscapeDataString(coverPath)}";
}
