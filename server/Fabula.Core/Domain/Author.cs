namespace Fabula.Core.Domain;

public class Author
{
    public int Id { get; set; }
    public string Name { get; set; } = string.Empty;
    public string? SortName { get; set; }
    public string? Description { get; set; }

    public List<Book> Books { get; set; } = [];
}
