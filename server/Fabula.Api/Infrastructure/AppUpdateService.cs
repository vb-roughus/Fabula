using System.Net.Http.Headers;
using System.Text.Json;
using System.Text.Json.Nodes;
using Microsoft.Extensions.Options;

namespace Fabula.Api.Infrastructure;

public record AppUpdateInfo(int VersionCode, string VersionName, string ApkPath);

/// <summary>Current update configuration for the settings UI (token never exposed).</summary>
public record AppUpdateSettings(
    string? Repo,
    bool HasToken,
    int CheckMinutes,
    int? CurrentVersionCode,
    string? CurrentVersionName);

/// <summary>Result of a manual "check now" so the app can show what went wrong.</summary>
public record AppUpdateCheckResult(
    bool Configured,
    bool Ok,
    string Message,
    int? VersionCode,
    string? VersionName);

/// <summary>
/// Mirrors the newest Android APK from the configured GitHub repository's
/// releases into {DataDirectory}/app-updates/, so the app's in-app updater can
/// fetch version info and the APK from this server. The repo/token can be
/// changed at runtime from the app; changes take effect immediately (no
/// restart) and are persisted back to the operator settings file.
/// </summary>
public class AppUpdateService
{
    private readonly FabulaOptions _options;
    private readonly ILogger<AppUpdateService> _logger;
    private readonly SemaphoreSlim _gate = new(1, 1);
    private readonly HttpClient _http;

    private string? _repo;
    private string? _token;
    private int _checkMinutes;
    private DateTime _lastCheckUtc = DateTime.MinValue;
    private AppUpdateInfo? _cached;

    public AppUpdateService(IOptions<FabulaOptions> options, ILogger<AppUpdateService> logger)
    {
        _options = options.Value;
        _logger = logger;
        _repo = Blank(_options.UpdateRepo);
        _token = Blank(_options.UpdateGithubToken);
        _checkMinutes = Math.Max(1, _options.UpdateCheckMinutes);

        _http = new HttpClient { Timeout = TimeSpan.FromMinutes(5) };
        _http.DefaultRequestHeaders.UserAgent.ParseAdd("Fabula-Server");
        _http.DefaultRequestHeaders.Accept.ParseAdd("application/vnd.github+json");
        ApplyTokenHeader();

        _cached = ReadDiskCache();
        _logger.LogInformation(
            "App updates: {State} (repo={Repo}, token={Token}).",
            string.IsNullOrWhiteSpace(_repo) ? "not configured" : "configured",
            _repo ?? "-",
            string.IsNullOrWhiteSpace(_token) ? "no" : "yes");
    }

    private string UpdatesDirectory => Path.Combine(_options.DataDirectory, "app-updates");
    private string VersionFile => Path.Combine(UpdatesDirectory, "version.json");
    private string ApkFile => Path.Combine(UpdatesDirectory, "fabula.apk");

    private static string? Blank(string? s) => string.IsNullOrWhiteSpace(s) ? null : s.Trim();

    private void ApplyTokenHeader() =>
        _http.DefaultRequestHeaders.Authorization =
            string.IsNullOrWhiteSpace(_token) ? null : new AuthenticationHeaderValue("Bearer", _token);

    public AppUpdateSettings GetSettings() => new(
        Repo: _repo,
        HasToken: !string.IsNullOrWhiteSpace(_token),
        CheckMinutes: _checkMinutes,
        CurrentVersionCode: _cached?.VersionCode,
        CurrentVersionName: _cached?.VersionName);

    /// <summary>
    /// Update repo/token at runtime. A blank token is treated as "unchanged"
    /// so editing the repo doesn't wipe the stored token. Forces the next
    /// version check to hit GitHub and persists to the settings file.
    /// </summary>
    public async Task<AppUpdateSettings> UpdateSettingsAsync(string? repo, string? token, CancellationToken ct)
    {
        await _gate.WaitAsync(ct);
        try
        {
            _repo = Blank(repo);
            if (!string.IsNullOrWhiteSpace(token))
            {
                _token = token.Trim();
                ApplyTokenHeader();
            }
            _lastCheckUtc = DateTime.MinValue;
            PersistSettings();
            return GetSettings();
        }
        finally
        {
            _gate.Release();
        }
    }

    /// <summary>Force an immediate GitHub check and report the outcome.</summary>
    public async Task<AppUpdateCheckResult> CheckNowAsync(CancellationToken ct)
    {
        await _gate.WaitAsync(ct);
        try
        {
            if (string.IsNullOrWhiteSpace(_repo))
                return new AppUpdateCheckResult(false, false, "Kein GitHub-Repository konfiguriert.", null, null);

            try
            {
                await RefreshFromGitHubAsync(_repo, ct);
                _lastCheckUtc = DateTime.UtcNow;
                if (_cached is null)
                    return new AppUpdateCheckResult(true, false,
                        "Verbindung ok, aber kein passendes Release gefunden (fabula.apk + version.json).", null, null);
                return new AppUpdateCheckResult(true, true,
                    $"OK – neueste Version: {_cached.VersionName} (Build {_cached.VersionCode}).",
                    _cached.VersionCode, _cached.VersionName);
            }
            catch (Exception ex) when (ex is not OperationCanceledException)
            {
                _logger.LogWarning(ex, "Manual app update check against {Repo} failed.", _repo);
                return new AppUpdateCheckResult(true, false, ex.Message, null, null);
            }
        }
        finally
        {
            _gate.Release();
        }
    }

    public async Task<AppUpdateInfo?> GetLatestAsync(CancellationToken ct)
    {
        await _gate.WaitAsync(ct);
        try
        {
            if (string.IsNullOrWhiteSpace(_repo))
                return _cached;

            if (DateTime.UtcNow - _lastCheckUtc < TimeSpan.FromMinutes(Math.Max(1, _checkMinutes)))
                return _cached;

            try
            {
                await RefreshFromGitHubAsync(_repo, ct);
            }
            catch (Exception ex) when (ex is not OperationCanceledException)
            {
                // Serve the cache; retry after the regular interval so a
                // GitHub hiccup doesn't hammer the API.
                _logger.LogWarning(ex, "App update check against {Repo} failed; serving cached version.", _repo);
            }
            _lastCheckUtc = DateTime.UtcNow;
            return _cached;
        }
        finally
        {
            _gate.Release();
        }
    }

    private void PersistSettings()
    {
        var path = _options.SettingsFilePath;
        if (string.IsNullOrWhiteSpace(path)) return;
        try
        {
            var dir = Path.GetDirectoryName(path);
            if (!string.IsNullOrEmpty(dir)) Directory.CreateDirectory(dir);

            // Merge into any existing file so unrelated operator settings survive.
            var root = (File.Exists(path)
                ? JsonNode.Parse(File.ReadAllText(path)) as JsonObject
                : null) ?? new JsonObject();
            var fabula = root["Fabula"] as JsonObject ?? new JsonObject();
            fabula["UpdateRepo"] = _repo;
            fabula["UpdateGithubToken"] = _token;
            root["Fabula"] = fabula;

            File.WriteAllText(path, root.ToJsonString(new JsonSerializerOptions { WriteIndented = true }));
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Could not persist update settings to {Path}.", path);
        }
    }

    private AppUpdateInfo? ReadDiskCache()
    {
        try
        {
            if (!File.Exists(VersionFile) || !File.Exists(ApkFile)) return null;
            using var doc = JsonDocument.Parse(File.ReadAllText(VersionFile));
            var code = doc.RootElement.GetProperty("versionCode").GetInt32();
            var name = doc.RootElement.GetProperty("versionName").GetString() ?? code.ToString();
            return new AppUpdateInfo(code, name, ApkFile);
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Could not read cached app update metadata.");
            return null;
        }
    }

    private async Task RefreshFromGitHubAsync(string repo, CancellationToken ct)
    {
        using var releaseDoc = JsonDocument.Parse(
            await _http.GetStringAsync($"https://api.github.com/repos/{repo}/releases/latest", ct));

        string? versionAssetUrl = null, apkAssetUrl = null;
        foreach (var asset in releaseDoc.RootElement.GetProperty("assets").EnumerateArray())
        {
            var name = asset.GetProperty("name").GetString() ?? "";
            // "url" is the API asset endpoint; with Accept: application/octet-stream
            // it redirects to the binary and works for private repos too
            // (browser_download_url does not).
            var url = asset.GetProperty("url").GetString();
            if (name.Equals("version.json", StringComparison.OrdinalIgnoreCase)) versionAssetUrl = url;
            else if (name.EndsWith(".apk", StringComparison.OrdinalIgnoreCase)) apkAssetUrl = url;
        }
        if (versionAssetUrl is null || apkAssetUrl is null)
        {
            _logger.LogWarning("Latest release of {Repo} lacks version.json or an .apk asset; skipping.", repo);
            return;
        }

        using var versionDoc = JsonDocument.Parse(await DownloadAssetStringAsync(versionAssetUrl, ct));
        var code = versionDoc.RootElement.GetProperty("versionCode").GetInt32();
        var name2 = versionDoc.RootElement.GetProperty("versionName").GetString() ?? code.ToString();

        if (_cached is not null && code <= _cached.VersionCode)
            return; // already have this (or a newer) build on disk

        Directory.CreateDirectory(UpdatesDirectory);

        // Download to a temp file first so a dropped connection can't leave a
        // truncated APK where the endpoint would serve it.
        var tmp = ApkFile + ".tmp";
        await using (var target = File.Create(tmp))
        {
            using var req = new HttpRequestMessage(HttpMethod.Get, apkAssetUrl);
            req.Headers.Accept.ParseAdd("application/octet-stream");
            using var resp = await _http.SendAsync(req, HttpCompletionOption.ResponseHeadersRead, ct);
            resp.EnsureSuccessStatusCode();
            await resp.Content.CopyToAsync(target, ct);
        }
        File.Move(tmp, ApkFile, overwrite: true);
        await File.WriteAllTextAsync(
            VersionFile,
            JsonSerializer.Serialize(new { versionCode = code, versionName = name2 }),
            ct);

        _cached = new AppUpdateInfo(code, name2, ApkFile);
        _logger.LogInformation("Mirrored app update {VersionName} (versionCode {Code}) from {Repo}.", name2, code, repo);
    }

    private async Task<string> DownloadAssetStringAsync(string assetUrl, CancellationToken ct)
    {
        using var req = new HttpRequestMessage(HttpMethod.Get, assetUrl);
        req.Headers.Accept.ParseAdd("application/octet-stream");
        using var resp = await _http.SendAsync(req, ct);
        resp.EnsureSuccessStatusCode();
        return await resp.Content.ReadAsStringAsync(ct);
    }
}
