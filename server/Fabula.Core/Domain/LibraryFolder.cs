namespace Fabula.Core.Domain;

public class LibraryFolder
{
    public int Id { get; set; }
    public string Name { get; set; } = string.Empty;
    public string Path { get; set; } = string.Empty;
    public DateTime? LastScanAt { get; set; }

    public List<Book> Books { get; set; } = [];
}
