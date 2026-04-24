namespace Fabula.Core.Domain;

public class PlaybackProgress
{
    public int Id { get; set; }

    public int UserId { get; set; }
    public User User { get; set; } = null!;

    public int BookId { get; set; }
    public Book Book { get; set; } = null!;

    public TimeSpan Position { get; set; }
    public bool Finished { get; set; }
    public DateTime UpdatedAt { get; set; } = DateTime.UtcNow;
    public string? LastDevice { get; set; }
}
