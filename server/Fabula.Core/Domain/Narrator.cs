namespace Fabula.Core.Domain;

public class Narrator
{
    public int Id { get; set; }
    public string Name { get; set; } = string.Empty;

    public List<Book> Books { get; set; } = [];
}
