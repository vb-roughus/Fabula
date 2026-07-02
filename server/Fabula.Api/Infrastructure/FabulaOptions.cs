namespace Fabula.Api.Infrastructure;

public class FabulaOptions
{
    public const string SectionName = "Fabula";

    public string DataDirectory { get; set; } = "data";
    public string? CoversDirectory { get; set; }

    /// <summary>
    /// GitHub repository ("owner/name") whose releases carry the Android APK
    /// built by CI. When set, the server periodically mirrors the newest
    /// release so the app's in-app updater can download it from here --
    /// the phone never needs to reach GitHub itself.
    /// </summary>
    public string? UpdateRepo { get; set; }

    /// <summary>
    /// Personal access token with read access to <see cref="UpdateRepo"/>.
    /// Required when the repository is private; leave empty for public repos.
    /// </summary>
    public string? UpdateGithubToken { get; set; }

    /// <summary>Minimum minutes between two GitHub release checks.</summary>
    public int UpdateCheckMinutes { get; set; } = 15;

    /// <summary>
    /// Absolute path of the operator settings file (fabula.settings.json).
    /// Set at startup so runtime edits from the app can be persisted back to
    /// the same file the config was loaded from. Not read from config itself.
    /// </summary>
    public string? SettingsFilePath { get; set; }
}
