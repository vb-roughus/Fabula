using System.Diagnostics;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Microsoft.Extensions.Options;
using Microsoft.Extensions.Hosting.WindowsServices;

namespace Fabula.Api.Infrastructure;

/// <summary>What the settings UI shows before anything has been started.</summary>
public record ServerUpdateInfo(
    bool Supported,
    string? UnsupportedReason,
    string? CurrentVersion,
    string? LatestVersion,
    bool Available,
    ServerUpdateStatus Status);

/// <summary>Result of a manual "check now", mirroring AppUpdateCheckResult.</summary>
public record ServerUpdateCheckResult(
    bool Configured,
    bool Ok,
    string Message,
    string? LatestVersion);

/// <summary>
/// Outcome of asking for an update. A refusal ("already current", "not
/// supported here") is not a failed update -- it leaves the recorded state
/// untouched and comes back as a plain error for the client to show.
/// </summary>
public record ServerUpdateStartResult(bool Started, string? Error, ServerUpdateStatus Status);

/// <summary>
/// Updates the server itself from the `win-v*` installer releases.
///
/// A service cannot replace its own binaries, so this class only ever gets the
/// ball rolling: it downloads the installer, checks it against the published
/// checksum, backs up the database and hands over to a small wrapper script.
/// The installer then stops this very process, swaps the files and starts the
/// service again -- which is why the attempt's state lives in a file rather
/// than in memory, and why the wrapper, not this class, records the outcome.
/// </summary>
public class ServerUpdateService
{
    private readonly FabulaOptions _options;
    private readonly AppUpdateService _appUpdates;
    private readonly ILogger<ServerUpdateService> _logger;
    private readonly SemaphoreSlim _gate = new(1, 1);
    private readonly HttpClient _http;

    private ServerUpdateStatus _status;
    private ServerRelease? _latest;
    private DateTime _lastCheckUtc = DateTime.MinValue;

    public ServerUpdateService(
        IOptions<FabulaOptions> options,
        AppUpdateService appUpdates,
        ILogger<ServerUpdateService> logger)
    {
        _options = options.Value;
        _appUpdates = appUpdates;
        _logger = logger;

        _http = new HttpClient { Timeout = TimeSpan.FromMinutes(10) };
        _http.DefaultRequestHeaders.UserAgent.ParseAdd("Fabula-Server");

        UpdateRoot = ResolveUpdateRoot(_options);
        _status = ServerUpdateLogic.Evaluate(
            ReadStatusFile(), RunningVersion(), DateTime.UtcNow, ReadInstallerExitCode());

        // The interesting case to see in the log is a restart that followed an
        // update attempt -- that is the only report the admin gets if the web UI
        // was closed while the service was down.
        if (_status.State is not ServerUpdateState.Idle)
            _logger.LogInformation(
                "Server update state after start: {State} ({Message})", _status.State, _status.Message ?? "-");
    }

    /// <summary>
    /// Where the downloaded installer and the state files live.
    ///
    /// Deliberately beside the operator settings file (%ProgramData%\Fabula) and
    /// NOT under the data directory: the data directory stays writable for
    /// ordinary users, and an installer sitting there could be swapped between
    /// the checksum check and the moment it is executed as SYSTEM.
    /// </summary>
    private string UpdateRoot { get; }

    private static string ResolveUpdateRoot(FabulaOptions options)
    {
        var settings = options.SettingsFilePath;
        var root = string.IsNullOrWhiteSpace(settings) ? null : Path.GetDirectoryName(settings);
        return Path.Combine(
            string.IsNullOrWhiteSpace(root) ? options.DataDirectory : root,
            "server-updates");
    }

    private string StateFile => Path.Combine(UpdateRoot, "server-update.json");
    private string ResultFile => Path.Combine(UpdateRoot, "server-update-result.json");
    private string WrapperFile => Path.Combine(UpdateRoot, "server-update-run.cmd");

    private static Version? RunningVersion()
    {
        var asm = Assembly.GetEntryAssembly();
        var informational = asm?.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion;
        return ServerUpdateLogic.ParseVersion(informational)
            ?? ServerUpdateLogic.ParseVersion(asm?.GetName().Version?.ToString());
    }

    /// <summary>
    /// Self-updating only works where the installer can restart us: a Windows
    /// service. Under `dotnet run` or on Linux the honest answer is "not here",
    /// so the clients can hide the action instead of failing confusingly.
    /// </summary>
    private static (bool Supported, string? Reason) SelfUpdateSupport()
    {
        if (!OperatingSystem.IsWindows())
            return (false, "Selbst-Update ist nur unter Windows möglich.");
        if (!WindowsServiceHelpers.IsWindowsService())
            return (false, "Der Server läuft nicht als Windows-Dienst; bitte den Installer manuell ausführen.");
        return (true, null);
    }

    /// <summary>Current state, re-judged against the clock and the wrapper's result.</summary>
    public ServerUpdateStatus GetStatus()
    {
        if (_status.State != ServerUpdateState.Installing) return _status;

        var judged = ServerUpdateLogic.Evaluate(
            _status, RunningVersion(), DateTime.UtcNow, ReadInstallerExitCode());
        if (judged.State != _status.State)
        {
            _status = judged;
            WriteStatusFile(judged);
        }
        return _status;
    }

    public async Task<ServerUpdateInfo> GetInfoAsync(CancellationToken ct)
    {
        var (supported, reason) = SelfUpdateSupport();
        var running = RunningVersion();
        var latest = await GetLatestAsync(ct);
        return new ServerUpdateInfo(
            Supported: supported,
            UnsupportedReason: reason,
            CurrentVersion: running?.ToString(),
            LatestVersion: latest?.Version.ToString(),
            Available: latest is not null && running is not null && latest.Version > running,
            Status: GetStatus());
    }

    /// <summary>Throttled lookup, same rhythm as the APK mirror.</summary>
    private async Task<ServerRelease?> GetLatestAsync(CancellationToken ct)
    {
        var (repo, _) = _appUpdates.GitHubCredentials();
        if (!ServerUpdateLogic.IsValidRepo(repo)) return null;

        if (DateTime.UtcNow - _lastCheckUtc < TimeSpan.FromMinutes(Math.Max(1, _options.UpdateCheckMinutes)))
            return _latest;

        try
        {
            _latest = await FetchNewestReleaseAsync(repo!, ct);
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            _logger.LogWarning(ex, "Server update check against {Repo} failed; serving cached result.", repo);
        }
        _lastCheckUtc = DateTime.UtcNow;
        return _latest;
    }

    public async Task<ServerUpdateCheckResult> CheckNowAsync(CancellationToken ct)
    {
        var (repo, _) = _appUpdates.GitHubCredentials();
        if (string.IsNullOrWhiteSpace(repo))
            return new ServerUpdateCheckResult(false, false, "Kein GitHub-Repository konfiguriert.", null);
        if (!ServerUpdateLogic.IsValidRepo(repo))
            return new ServerUpdateCheckResult(false, false,
                $"Ungültiges Repository \"{repo}\" – erwartet wird \"owner/name\".", null);

        try
        {
            _latest = await FetchNewestReleaseAsync(repo, ct);
            _lastCheckUtc = DateTime.UtcNow;
            if (_latest is null)
                return new ServerUpdateCheckResult(true, false,
                    "Verbindung ok, aber kein Installer-Release gefunden (Tag win-v… mit Fabula-Setup-….exe).", null);

            var running = RunningVersion();
            var message = running is not null && _latest.Version > running
                ? $"Version {_latest.Version} verfügbar (läuft: {running})."
                : $"OK – aktuellste Version ist {_latest.Version} (läuft: {running?.ToString() ?? "unbekannt"}).";
            return new ServerUpdateCheckResult(true, true, message, _latest.Version.ToString());
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            _logger.LogWarning(ex, "Manual server update check against {Repo} failed.", repo);
            return new ServerUpdateCheckResult(true, false, ex.Message, null);
        }
    }

    private async Task<ServerRelease?> FetchNewestReleaseAsync(string repo, CancellationToken ct)
    {
        using var req = new HttpRequestMessage(
            HttpMethod.Get, $"https://api.github.com/repos/{repo}/releases?per_page=50");
        req.Headers.Accept.ParseAdd("application/vnd.github+json");
        ApplyToken(req);
        using var resp = await _http.SendAsync(req, ct);
        resp.EnsureSuccessStatusCode();
        return ServerUpdateLogic.SelectNewestSetupRelease(await resp.Content.ReadAsStringAsync(ct));
    }

    private void ApplyToken(HttpRequestMessage req)
    {
        var (_, token) = _appUpdates.GitHubCredentials();
        if (!string.IsNullOrWhiteSpace(token))
            req.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", token);
    }

    /// <summary>
    /// Starts an update. Returns immediately -- the work continues in the
    /// background and ends with this process being stopped by the installer.
    /// A second call while one is in flight returns the running attempt.
    /// </summary>
    public async Task<ServerUpdateStartResult> StartAsync(CancellationToken ct)
    {
        var (supported, reason) = SelfUpdateSupport();
        if (!supported)
            return Refuse(reason ?? "Selbst-Update hier nicht möglich.");

        await _gate.WaitAsync(ct);
        try
        {
            // Already on its way: report the running attempt rather than
            // starting a second installer on top of the first.
            if (_status.State is ServerUpdateState.Downloading
                or ServerUpdateState.Verifying
                or ServerUpdateState.Installing)
                return new ServerUpdateStartResult(false, "Es läuft bereits eine Aktualisierung.", _status);

            var running = RunningVersion();
            var latest = await GetLatestAsync(ct);
            if (latest is null)
                return Refuse("Kein Installer-Release gefunden (Tag win-v… mit Fabula-Setup-….exe).");
            if (running is not null && latest.Version <= running)
                return Refuse($"Version {running} ist bereits aktuell.");
            if (latest.Sha256AssetUrl is null)
                // No checksum means no execution. The release workflow always
                // publishes one; a release without it is not one of ours.
                return Refuse($"Release {latest.Tag} hat keine Prüfsumme ({latest.SetupAssetName}.sha256).");

            SetStatus(new ServerUpdateStatus(
                ServerUpdateState.Downloading,
                FromVersion: running?.ToString(),
                ToVersion: latest.Version.ToString(),
                StartedAtUtc: DateTime.UtcNow,
                Message: $"Lade {latest.SetupAssetName}…"));

            // Fire and forget, like ScanCoordinator: the caller gets its 202 and
            // the work carries on without a request scope.
            _ = Task.Run(() => RunUpdateAsync(latest, running), CancellationToken.None);
            return new ServerUpdateStartResult(true, null, _status);
        }
        finally
        {
            _gate.Release();
        }
    }

    private ServerUpdateStartResult Refuse(string error) => new(false, error, GetStatus());

    private async Task RunUpdateAsync(ServerRelease release, Version? running)
    {
        try
        {
            Directory.CreateDirectory(UpdateRoot);
            var setupPath = Path.Combine(UpdateRoot, release.SetupAssetName);
            await DownloadAssetAsync(release.SetupAssetUrl, setupPath);

            SetStatus(_status with { State = ServerUpdateState.Verifying, Message = "Prüfe Signatur…" });

            var expected = ServerUpdateLogic.ExpectedHash(
                await DownloadAssetStringAsync(release.Sha256AssetUrl!));
            if (expected is null)
            {
                TryDelete(setupPath);
                SetStatus(Failed("Prüfsumme des Releases ist unlesbar; Abbruch."));
                return;
            }

            var actual = await ComputeSha256Async(setupPath);
            if (!string.Equals(actual, expected, StringComparison.OrdinalIgnoreCase))
            {
                TryDelete(setupPath);
                _logger.LogError(
                    "Checksum mismatch for {Asset}: expected {Expected}, got {Actual}. Not executing.",
                    release.SetupAssetName, expected, actual);
                SetStatus(Failed("Prüfsumme stimmt nicht – die Datei wurde verworfen und nicht ausgeführt."));
                return;
            }

            BackupDatabase(running, release.Version);

            var logPath = Path.Combine(
                LogDirectory(), $"server-update-{DateTime.UtcNow:yyyyMMdd-HHmmss}.log");
            WriteWrapper(setupPath, logPath);
            TryDelete(ResultFile);  // a stale result would be read as this run's

            SetStatus(_status with
            {
                State = ServerUpdateState.Installing,
                HandoffAtUtc = DateTime.UtcNow,
                Message = $"Installiere {release.Version} – der Dienst startet dabei neu."
            });

            LaunchWrapper();
            _logger.LogWarning(
                "Handed over to installer {Asset}; this service is about to be stopped.",
                release.SetupAssetName);
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Server update failed before handover.");
            SetStatus(Failed(ex.Message));
        }
    }

    /// <summary>
    /// Writes the script that outlives us.
    ///
    /// It exists for two reasons. First, nobody would otherwise learn the
    /// installer's exit code -- the process that started it is stopped by that
    /// very installer. Second, the closing `sc start` is the safety net: if the
    /// installer fails *after* stopping the service, the server would stay down
    /// and take the only remedy (the web UI) with it. `sc failure` does not
    /// cover this, as it only reacts to crashes, not to an orderly stop.
    /// </summary>
    private void WriteWrapper(string setupPath, string logPath)
    {
        var script = new StringBuilder();
        script.AppendLine("@echo off");
        script.AppendLine("rem Written by the Fabula server to install its own update.");
        script.AppendLine("rem It must survive the service being stopped, so it is launched detached.");
        script.AppendLine("setlocal");
        // `ping` rather than `timeout`: timeout wants a console input handle and
        // fails in a service context. The pause only gives the HTTP response to
        // the admin's browser a moment to flush before the service goes away.
        script.AppendLine("ping -n 4 127.0.0.1 >nul 2>&1");
        script.AppendLine($"\"{setupPath}\" /VERYSILENT /SUPPRESSMSGBOXES /NORESTART /LOG=\"{logPath}\"");
        script.AppendLine("set FABULA_CODE=%ERRORLEVEL%");
        script.AppendLine($"> \"{ResultFile}\" echo {{\"exitCode\": %FABULA_CODE%}}");
        script.AppendLine("rem Safety net -- see the comment in ServerUpdateService.WriteWrapper.");
        script.AppendLine($"\"{Path.Combine(SystemDirectory(), "sc.exe")}\" start Fabula >nul 2>&1");
        script.AppendLine("endlocal");
        File.WriteAllText(WrapperFile, script.ToString(), Encoding.ASCII);
    }

    private void LaunchWrapper()
    {
        var psi = new ProcessStartInfo
        {
            FileName = Path.Combine(SystemDirectory(), "cmd.exe"),
            Arguments = $"/c \"{WrapperFile}\"",
            WorkingDirectory = UpdateRoot,
            UseShellExecute = false,
            CreateNoWindow = true
        };
        Process.Start(psi);
    }

    private static string SystemDirectory() =>
        string.IsNullOrWhiteSpace(Environment.SystemDirectory)
            ? @"C:\Windows\System32"
            : Environment.SystemDirectory;

    private string LogDirectory()
    {
        // Sits beside the data directory, matching where the file logger writes.
        var root = Path.GetDirectoryName(_options.DataDirectory.TrimEnd(
            Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar));
        var dir = Path.Combine(string.IsNullOrWhiteSpace(root) ? UpdateRoot : root, "logs");
        Directory.CreateDirectory(dir);
        return dir;
    }

    /// <summary>
    /// Copies the database aside before the new build gets to migrate it.
    /// EF migrations run at startup and only go forwards, so this copy is the
    /// only way back to the previous version.
    /// </summary>
    private void BackupDatabase(Version? from, Version to)
    {
        try
        {
            var db = Path.Combine(_options.DataDirectory, "fabula.db");
            if (!File.Exists(db)) return;
            var dir = Path.Combine(_options.DataDirectory, "backups");
            Directory.CreateDirectory(dir);
            var name = $"fabula-{from?.ToString() ?? "unknown"}-before-{to}-{DateTime.UtcNow:yyyyMMdd-HHmmss}.db";
            File.Copy(db, Path.Combine(dir, name), overwrite: false);
            _logger.LogInformation("Database backed up to {Name} before updating to {To}.", name, to);
        }
        catch (Exception ex)
        {
            // Worth a loud log, but not worth refusing the update: the installer
            // itself does not touch the database.
            _logger.LogWarning(ex, "Could not back up the database before updating.");
        }
    }

    private async Task DownloadAssetAsync(string assetUrl, string targetPath)
    {
        var tmp = targetPath + ".tmp";
        await using (var target = File.Create(tmp))
        {
            using var req = new HttpRequestMessage(HttpMethod.Get, assetUrl);
            req.Headers.Accept.ParseAdd("application/octet-stream");
            ApplyToken(req);
            using var resp = await _http.SendAsync(req, HttpCompletionOption.ResponseHeadersRead);
            resp.EnsureSuccessStatusCode();
            await resp.Content.CopyToAsync(target);
        }
        File.Move(tmp, targetPath, overwrite: true);
    }

    private async Task<string> DownloadAssetStringAsync(string assetUrl)
    {
        using var req = new HttpRequestMessage(HttpMethod.Get, assetUrl);
        req.Headers.Accept.ParseAdd("application/octet-stream");
        ApplyToken(req);
        using var resp = await _http.SendAsync(req);
        resp.EnsureSuccessStatusCode();
        return await resp.Content.ReadAsStringAsync();
    }

    private static async Task<string> ComputeSha256Async(string path)
    {
        await using var stream = File.OpenRead(path);
        var hash = await SHA256.HashDataAsync(stream);
        return Convert.ToHexString(hash).ToLowerInvariant();
    }

    private ServerUpdateStatus Failed(string message) =>
        _status with { State = ServerUpdateState.Failed, Message = message };

    private void SetStatus(ServerUpdateStatus status)
    {
        _status = status;
        WriteStatusFile(status);
    }

    /// <summary>
    /// Enum as text, not as a number: after a restart this file is the only
    /// account of what happened, and it gets read by a human on the server box
    /// at least as often as by this code.
    /// </summary>
    private static readonly JsonSerializerOptions StateJson = new()
    {
        WriteIndented = true,
        Converters = { new System.Text.Json.Serialization.JsonStringEnumConverter() }
    };

    private void WriteStatusFile(ServerUpdateStatus status)
    {
        try
        {
            Directory.CreateDirectory(UpdateRoot);
            var tmp = StateFile + ".tmp";
            File.WriteAllText(tmp, JsonSerializer.Serialize(status, StateJson));
            File.Move(tmp, StateFile, overwrite: true);
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Could not persist server update state to {Path}.", StateFile);
        }
    }

    private ServerUpdateStatus? ReadStatusFile()
    {
        try
        {
            if (!File.Exists(StateFile)) return null;
            return JsonSerializer.Deserialize<ServerUpdateStatus>(File.ReadAllText(StateFile), StateJson);
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Could not read server update state from {Path}.", StateFile);
            return null;
        }
    }

    /// <summary>The wrapper's verdict, or null while the installer is still running.</summary>
    private int? ReadInstallerExitCode()
    {
        try
        {
            if (!File.Exists(ResultFile)) return null;
            using var doc = JsonDocument.Parse(File.ReadAllText(ResultFile));
            return doc.RootElement.TryGetProperty("exitCode", out var p) && p.TryGetInt32(out var code)
                ? code
                : null;
        }
        catch
        {
            return null;  // half-written file; treat as "not finished yet"
        }
    }

    private static void TryDelete(string path)
    {
        try { if (File.Exists(path)) File.Delete(path); } catch { /* best effort */ }
    }
}
