using System.Text.Json;
using Microsoft.Extensions.Options;

namespace Fabula.Api.Infrastructure;

public record AppUpdateInfo(int VersionCode, string VersionName, string ApkPath);

/// <summary>
/// Mirrors the newest Android APK from the configured GitHub repository's
/// releases into {DataDirectory}/app-updates/, so the app's in-app updater can
/// fetch version info and the APK from this server. GitHub is only contacted
/// every <see cref="FabulaOptions.UpdateCheckMinutes"/> minutes; between
/// checks (and when GitHub is unreachable) the disk cache is served.
/// </summary>
public class AppUpdateService(IOptions<FabulaOptions> options, ILogger<AppUpdateService> logger)
{
    private readonly FabulaOptions _options = options.Value;
    private readonly SemaphoreSlim _gate = new(1, 1);
    private readonly HttpClient _http = CreateClient(options.Value.UpdateGithubToken);
    private DateTime _lastCheckUtc = DateTime.MinValue;
    private AppUpdateInfo? _cached;

    private string UpdatesDirectory => Path.Combine(_options.DataDirectory, "app-updates");
    private string VersionFile => Path.Combine(UpdatesDirectory, "version.json");
    private string ApkFile => Path.Combine(UpdatesDirectory, "fabula.apk");

    private static HttpClient CreateClient(string? token)
    {
        var http = new HttpClient { Timeout = TimeSpan.FromMinutes(5) };
        http.DefaultRequestHeaders.UserAgent.ParseAdd("Fabula-Server");
        http.DefaultRequestHeaders.Accept.ParseAdd("application/vnd.github+json");
        if (!string.IsNullOrWhiteSpace(token))
            http.DefaultRequestHeaders.Authorization = new("Bearer", token);
        return http;
    }

    public async Task<AppUpdateInfo?> GetLatestAsync(CancellationToken ct)
    {
        await _gate.WaitAsync(ct);
        try
        {
            _cached ??= ReadDiskCache();

            var repo = _options.UpdateRepo;
            if (string.IsNullOrWhiteSpace(repo))
                return _cached;

            if (DateTime.UtcNow - _lastCheckUtc < TimeSpan.FromMinutes(Math.Max(1, _options.UpdateCheckMinutes)))
                return _cached;

            try
            {
                await RefreshFromGitHubAsync(repo, ct);
                _lastCheckUtc = DateTime.UtcNow;
            }
            catch (Exception ex) when (ex is not OperationCanceledException)
            {
                // Serve the cache; retry after the regular interval so a
                // GitHub hiccup doesn't hammer the API.
                logger.LogWarning(ex, "App update check against {Repo} failed; serving cached version.", repo);
                _lastCheckUtc = DateTime.UtcNow;
            }

            return _cached;
        }
        finally
        {
            _gate.Release();
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
            logger.LogWarning(ex, "Could not read cached app update metadata.");
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
            logger.LogWarning("Latest release of {Repo} lacks version.json or an .apk asset; skipping.", repo);
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
        logger.LogInformation("Mirrored app update {VersionName} (versionCode {Code}) from {Repo}.", name2, code, repo);
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
