namespace Fabula.Core.Domain;

/// <summary>
/// A user-marked passage of a book -- like a highlighter stroke over a
/// paragraph. Spans a time range (<see cref="Start"/>..<see cref="End"/>) and
/// carries an optional short description plus free-form notes.
/// </summary>
public class Highlight
{
    public int Id { get; set; }

    public int UserId { get; set; }
    public User User { get; set; } = null!;

    public int BookId { get; set; }
    public Book Book { get; set; } = null!;

    public TimeSpan Start { get; set; }
    public TimeSpan End { get; set; }

    /// <summary>Short label shown in lists ("Beschreibung").</summary>
    public string? Title { get; set; }

    /// <summary>Longer free-form notes ("Notizen").</summary>
    public string? Note { get; set; }

    public DateTime CreatedAt { get; set; } = DateTime.UtcNow;
}
