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
    /// <summary>When this arrived at the server.</summary>
    public DateTime UpdatedAt { get; set; } = DateTime.UtcNow;

    /// <summary>
    /// When the listener's device recorded this position, which can be hours
    /// before it arrives: a phone that listened offline hands its progress
    /// over once it is back. Ordering by this, not by arrival, is what stops
    /// that late, older position from overwriting a newer one from another
    /// device. Null for rows from before it was kept, which fall back to
    /// <see cref="UpdatedAt"/>.
    /// </summary>
    public DateTime? RecordedAt { get; set; }
    public string? LastDevice { get; set; }
}
