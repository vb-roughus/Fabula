namespace Fabula.Core.Domain;

public class Chapter
{
    public int Id { get; set; }
    public int BookId { get; set; }
    public Book Book { get; set; } = null!;

    public int Index { get; set; }
    public string Title { get; set; } = string.Empty;
    public TimeSpan Start { get; set; }
    public TimeSpan End { get; set; }
}
