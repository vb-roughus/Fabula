using System.Collections.Concurrent;
using Fabula.Core.Services;

namespace Fabula.Api.Infrastructure;

public enum ScanState { Idle, Running, Completed, Failed, Cancelled }

public record ScanStatus(
    int LibraryFolderId,
    ScanState State,
    DateTime StartedAt,
    DateTime? FinishedAt,
    ScanResult? Result,
    string? Error);

/// <summary>
/// Runs library scans in the background, decoupled from the HTTP request
/// that triggered them. Status per library folder is held in memory and
/// can be polled by the client.
/// </summary>
public class ScanCoordinator
{
    private readonly ConcurrentDictionary<int, ScanStatus> _statuses = new();
    private readonly IServiceScopeFactory _scopeFactory;
    private readonly IHostApplicationLifetime _lifetime;
    private readonly ILogger<ScanCoordinator> _logger;

    public ScanCoordinator(
        IServiceScopeFactory scopeFactory,
        IHostApplicationLifetime lifetime,
        ILogger<ScanCoordinator> logger)
    {
        _scopeFactory = scopeFactory;
        _lifetime = lifetime;
        _logger = logger;
    }

    public ScanStatus StartScan(int libraryFolderId)
    {
        var startedAt = DateTime.UtcNow;
        var initial = new ScanStatus(libraryFolderId, ScanState.Running, startedAt, null, null, null);

        // Reject re-entry: a second scan request for a folder that is
        // already running just returns the existing status.
        var current = _statuses.AddOrUpdate(
            libraryFolderId,
            initial,
            (_, existing) => existing.State == ScanState.Running ? existing : initial);

        if (!ReferenceEquals(current, initial))
            return current;

        _ = Task.Run(() => RunScanAsync(libraryFolderId, startedAt));
        return initial;
    }

    public ScanStatus? Get(int libraryFolderId) =>
        _statuses.TryGetValue(libraryFolderId, out var status) ? status : null;

    private async Task RunScanAsync(int libraryFolderId, DateTime startedAt)
    {
        try
        {
            await using var scope = _scopeFactory.CreateAsyncScope();
            var scanner = scope.ServiceProvider.GetRequiredService<ILibraryScanner>();
            var result = await scanner.ScanAsync(libraryFolderId, _lifetime.ApplicationStopping);

            _statuses[libraryFolderId] = new ScanStatus(
                libraryFolderId, ScanState.Completed, startedAt, DateTime.UtcNow, result, null);
        }
        catch (OperationCanceledException)
        {
            _statuses[libraryFolderId] = new ScanStatus(
                libraryFolderId, ScanState.Cancelled, startedAt, DateTime.UtcNow, null, "Scan cancelled.");
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Scan for library {Id} failed", libraryFolderId);
            _statuses[libraryFolderId] = new ScanStatus(
                libraryFolderId, ScanState.Failed, startedAt, DateTime.UtcNow, null, ex.Message);
        }
    }
}
