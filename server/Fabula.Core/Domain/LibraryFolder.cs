namespace Fabula.Core.Domain;

public class LibraryFolder
{
    public int Id { get; set; }
    public string Name { get; set; } = string.Empty;
    public string Path { get; set; } = string.Empty;
    public LibraryType Type { get; set; } = LibraryType.Audiobook;
    public DateTime? LastScanAt { get; set; }

    public List<Book> Books { get; set; } = [];
}

public enum LibraryType
{
    Audiobook = 0,
    RadioPlay = 1
}
