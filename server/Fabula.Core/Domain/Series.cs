namespace Fabula.Core.Domain;

public class Series
{
    public int Id { get; set; }
    public string Name { get; set; } = string.Empty;
    public string? Description { get; set; }

    public List<Book> Books { get; set; } = [];
}
