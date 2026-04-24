namespace Fabula.Api.Infrastructure;

public class FabulaOptions
{
    public const string SectionName = "Fabula";

    public string DataDirectory { get; set; } = "data";
    public string? CoversDirectory { get; set; }
}
