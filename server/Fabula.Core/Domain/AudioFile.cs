namespace Fabula.Core.Domain;

public class AudioFile
{
    public int Id { get; set; }
    public int BookId { get; set; }
    public Book Book { get; set; } = null!;

    public int TrackIndex { get; set; }
    public string Path { get; set; } = string.Empty;
    public long SizeBytes { get; set; }
    public TimeSpan Duration { get; set; }
    public string? Codec { get; set; }
    public int? BitrateKbps { get; set; }
    public int? SampleRate { get; set; }

    public TimeSpan OffsetInBook { get; set; }
}
