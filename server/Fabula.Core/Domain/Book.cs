namespace Fabula.Core.Domain;

public class Book
{
    public int Id { get; set; }
    public string Title { get; set; } = string.Empty;
    public string? SortTitle { get; set; }
    public string? Subtitle { get; set; }
    public string? Description { get; set; }
    public string? Language { get; set; }
    public string? Publisher { get; set; }
    public int? PublishYear { get; set; }
    public string? Isbn { get; set; }
    public string? Asin { get; set; }

    public int? SeriesId { get; set; }
    public Series? Series { get; set; }
    public decimal? SeriesPosition { get; set; }

    // True when the user has explicitly set SeriesPosition through the API.
    // The library scanner skips overwriting the position (and only the position)
    // for these books on rescan -- folder/metadata still own series membership.
    public bool SeriesPositionManuallySet { get; set; }

    public TimeSpan Duration { get; set; }
    public string? CoverPath { get; set; }

    public DateTime AddedAt { get; set; } = DateTime.UtcNow;
    public DateTime UpdatedAt { get; set; } = DateTime.UtcNow;

    public int LibraryFolderId { get; set; }
    public LibraryFolder LibraryFolder { get; set; } = null!;

    public List<Author> Authors { get; set; } = [];
    public List<Narrator> Narrators { get; set; } = [];
    public List<AudioFile> Files { get; set; } = [];
    public List<Chapter> Chapters { get; set; } = [];
}
