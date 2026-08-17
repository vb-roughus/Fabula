using System.Globalization;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace Fabula.Api.Infrastructure;

/// <summary>How far along a server self-update is.</summary>
public enum ServerUpdateState
{
    Idle,
    Downloading,
    Verifying,
    Installing,
    Succeeded,
    Failed
}

/// <summary>
/// Durable record of an update attempt. Written to disk before the installer is
/// handed control, because the process that started the update does not live to
/// see it finish -- the installer stops this very service.
/// </summary>
public record ServerUpdateStatus(
    ServerUpdateState State,
    string? FromVersion = null,
    string? ToVersion = null,
    DateTime? StartedAtUtc = null,
    // HandoffAtUtc: when the installer was launched. The timeout is measured
    // from here, not from StartedAtUtc, so a slow download doesn't eat into it.
    DateTime? HandoffAtUtc = null,
    string? Message = null)
{
    public static readonly ServerUpdateStatus Idle = new(ServerUpdateState.Idle);
}

/// <summary>A `win-v*` release that carries a Windows installer.</summary>
public record ServerRelease(
    Version Version,
    string Tag,
    string SetupAssetName,
    string SetupAssetUrl,
    string? Sha256AssetUrl);

/// <summary>
/// The decisions behind a server self-update that need no filesystem, no
/// network and no Windows: which release to take, whether it is newer, whether
/// the download matches its checksum, and what an interrupted attempt means.
/// Separated from <see cref="ServerUpdateService"/> precisely so they can be
/// tested -- the rest can only be exercised on a real Windows box.
/// </summary>
public static class ServerUpdateLogic
{
    /// <summary>
    /// Installer releases are tagged `win-v0.3.12`. They deliberately do not
    /// take the "latest" pointer -- that belongs to the Android APK release --
    /// so the newest one has to be found by walking the tags.
    /// </summary>
    private static readonly Regex WinTag =
        new(@"^win-v(\d+)\.(\d+)\.(\d+)$", RegexOptions.Compiled | RegexOptions.CultureInvariant);

    /// <summary>
    /// `owner/name`. The owner must start alphanumeric, which is what stops a
    /// value like `../..` from turning the GitHub URL into something else, and
    /// an all-dots repository name is rejected outright. This matters more than
    /// it looks: the value decides where an executable is downloaded from.
    /// </summary>
    private static readonly Regex RepoPattern =
        new(@"^[A-Za-z0-9][A-Za-z0-9._-]{0,99}/[A-Za-z0-9._-]{1,100}$",
            RegexOptions.Compiled | RegexOptions.CultureInvariant);

    /// <summary>How long an installer may take before we call the attempt dead.</summary>
    public static readonly TimeSpan InstallTimeout = TimeSpan.FromMinutes(15);

    public static bool IsValidRepo(string? repo)
    {
        if (string.IsNullOrWhiteSpace(repo)) return false;
        var trimmed = repo.Trim();
        if (!RepoPattern.IsMatch(trimmed)) return false;
        var name = trimmed.Split('/')[1];
        return !name.All(c => c == '.');
    }

    public static Version? ParseWinTag(string? tag)
    {
        if (string.IsNullOrWhiteSpace(tag)) return null;
        var m = WinTag.Match(tag.Trim());
        if (!m.Success) return null;
        return new Version(
            int.Parse(m.Groups[1].Value, CultureInfo.InvariantCulture),
            int.Parse(m.Groups[2].Value, CultureInfo.InvariantCulture),
            int.Parse(m.Groups[3].Value, CultureInfo.InvariantCulture));
    }

    /// <summary>
    /// Normalises a version for comparison. The assembly version carries a
    /// fourth component (`0.3.12.0`) that the tags never have, and an
    /// informational version can carry `+metadata`.
    /// </summary>
    public static Version? ParseVersion(string? raw)
    {
        if (string.IsNullOrWhiteSpace(raw)) return null;
        var text = raw.Trim();
        var plus = text.IndexOf('+');
        if (plus >= 0) text = text[..plus];
        var dash = text.IndexOf('-');
        if (dash >= 0) text = text[..dash];
        if (!Version.TryParse(text, out var v)) return null;
        return new Version(v.Major, Math.Max(0, v.Minor), Math.Max(0, v.Build));
    }

    /// <summary>
    /// Picks the highest `win-v*` release that actually carries an installer.
    /// Drafts and pre-releases are skipped, and so is any release whose assets
    /// are missing -- a tag alone is not something we can install.
    /// </summary>
    public static ServerRelease? SelectNewestSetupRelease(string releasesJson)
    {
        using var doc = JsonDocument.Parse(releasesJson);
        if (doc.RootElement.ValueKind != JsonValueKind.Array) return null;

        ServerRelease? best = null;
        foreach (var release in doc.RootElement.EnumerateArray())
        {
            if (Flag(release, "draft") || Flag(release, "prerelease")) continue;

            var tag = Str(release, "tag_name");
            var version = ParseWinTag(tag);
            if (version is null) continue;
            if (best is not null && version <= best.Version) continue;

            var candidate = ReadSetupAssets(release, version, tag!);
            if (candidate is not null) best = candidate;
        }
        return best;
    }

    private static ServerRelease? ReadSetupAssets(JsonElement release, Version version, string tag)
    {
        if (!release.TryGetProperty("assets", out var assets) ||
            assets.ValueKind != JsonValueKind.Array) return null;

        string? setupName = null, setupUrl = null;
        var hashUrls = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);

        foreach (var asset in assets.EnumerateArray())
        {
            var name = Str(asset, "name");
            // "url" is the API asset endpoint; with Accept: application/octet-stream
            // it redirects to the binary and works for private repos too, exactly
            // as AppUpdateService does it for the APK.
            var url = Str(asset, "url");
            if (name is null || url is null) continue;

            if (name.EndsWith(".sha256", StringComparison.OrdinalIgnoreCase))
                hashUrls[name] = url;
            else if (name.StartsWith("Fabula-Setup-", StringComparison.OrdinalIgnoreCase) &&
                     name.EndsWith(".exe", StringComparison.OrdinalIgnoreCase))
            {
                setupName = name;
                setupUrl = url;
            }
        }

        if (setupName is null || setupUrl is null) return null;
        hashUrls.TryGetValue(setupName + ".sha256", out var hashUrl);
        return new ServerRelease(version, tag, setupName, setupUrl, hashUrl);
    }

    /// <summary>
    /// Reads the hash out of a `sha256sum`-style file. CI writes
    /// "&lt;hash&gt;  &lt;filename&gt;", but a bare hash is accepted too since that is
    /// the other common shape.
    /// </summary>
    public static string? ExpectedHash(string? sha256FileContent)
    {
        if (string.IsNullOrWhiteSpace(sha256FileContent)) return null;
        foreach (var line in sha256FileContent.Split('\n'))
        {
            var token = line.Trim().Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries)
                .FirstOrDefault();
            if (token is null) continue;
            token = token.TrimStart('*');
            if (token.Length == 64 && token.All(Uri.IsHexDigit))
                return token.ToLowerInvariant();
        }
        return null;
    }

    /// <summary>
    /// What an attempt found on disk actually means right now.
    ///
    /// Called both at startup and on every status read, so the timeout below
    /// takes effect as time passes rather than only once. <paramref name="installerExitCode"/>
    /// comes from the file the handoff wrapper writes -- its presence is the
    /// only proof the installer ran to completion at all.
    /// </summary>
    public static ServerUpdateStatus Evaluate(
        ServerUpdateStatus? persisted,
        Version? runningVersion,
        DateTime nowUtc,
        int? installerExitCode)
    {
        if (persisted is null) return ServerUpdateStatus.Idle;

        // A download that was interrupted by a restart is simply gone: the
        // process that was doing it no longer exists.
        if (persisted.State is ServerUpdateState.Downloading or ServerUpdateState.Verifying)
            return persisted with
            {
                State = ServerUpdateState.Failed,
                Message = "Abgebrochen – der Server wurde neu gestartet, bevor das Update fertig geladen war."
            };

        if (persisted.State != ServerUpdateState.Installing) return persisted;

        var target = ParseVersion(persisted.ToVersion);
        if (target is not null && runningVersion is not null && runningVersion >= target)
            return persisted with
            {
                State = ServerUpdateState.Succeeded,
                Message = $"Version {runningVersion} läuft."
            };

        var running = runningVersion?.ToString() ?? "unbekannt";
        if (installerExitCode is int code)
            return persisted with
            {
                State = ServerUpdateState.Failed,
                Message = code == 0
                    // The wrapper's unconditional `sc start` brought the old
                    // build back, so the service is alive -- just not updated.
                    ? $"Installer meldete Erfolg, es läuft aber weiterhin Version {running}."
                    : $"Installer fehlgeschlagen (Code {code}); es läuft weiterhin Version {running}."
            };

        if (persisted.HandoffAtUtc is DateTime handoff && nowUtc - handoff > InstallTimeout)
            return persisted with
            {
                State = ServerUpdateState.Failed,
                Message = $"Installer wurde nicht abgeschlossen; es läuft weiterhin Version {running}."
            };

        return persisted;  // still running
    }

    private static bool Flag(JsonElement e, string name) =>
        e.TryGetProperty(name, out var p) && p.ValueKind == JsonValueKind.True;

    private static string? Str(JsonElement e, string name) =>
        e.TryGetProperty(name, out var p) && p.ValueKind == JsonValueKind.String ? p.GetString() : null;
}
