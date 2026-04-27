using Fabula.Core.Domain;
using Fabula.Core.Services;
using Fabula.Data;
using Microsoft.EntityFrameworkCore;

namespace Fabula.Api.Endpoints;

public static class LibraryEndpoints
{
    public static IEndpointRouteBuilder MapLibraryEndpoints(this IEndpointRouteBuilder app)
    {
        var group = app.MapGroup("/api/libraries").WithTags("Libraries");

        group.MapGet("/", async (FabulaDbContext db, CancellationToken ct) =>
            await db.LibraryFolders
                .Select(f => new LibraryFolderDto(f.Id, f.Name, f.Path, f.LastScanAt))
                .ToListAsync(ct));

        group.MapPost("/", async (FabulaDbContext db, CreateLibraryFolderRequest req, CancellationToken ct) =>
        {
            if (string.IsNullOrWhiteSpace(req.Name) || string.IsNullOrWhiteSpace(req.Path))
                return Results.BadRequest(new { error = "Name and Path are required." });

            var (ok, error) = ValidateLibraryPath(req.Path);
            if (!ok)
                return Results.BadRequest(new { error });

            var folder = new LibraryFolder { Name = req.Name, Path = req.Path };
            db.LibraryFolders.Add(folder);
            await db.SaveChangesAsync(ct);
            return Results.Created($"/api/libraries/{folder.Id}",
                new LibraryFolderDto(folder.Id, folder.Name, folder.Path, folder.LastScanAt));
        });

        group.MapPost("/{id:int}/scan", async (int id, ILibraryScanner scanner, CancellationToken ct) =>
        {
            var result = await scanner.ScanAsync(id, ct);
            return Results.Ok(result);
        });

        group.MapDelete("/{id:int}", async (int id, FabulaDbContext db, CancellationToken ct) =>
        {
            var folder = await db.LibraryFolders.FindAsync([id], ct);
            if (folder is null) return Results.NotFound();
            db.LibraryFolders.Remove(folder);
            await db.SaveChangesAsync(ct);
            return Results.NoContent();
        });

        return app;
    }

    private static (bool Ok, string? Error) ValidateLibraryPath(string path)
    {
        try
        {
            if (Directory.Exists(path))
                return (true, null);
        }
        catch (UnauthorizedAccessException ex)
        {
            return (false, IsUncPath(path)
                ? $"Access denied to {path}. The Fabula service account needs read access to that share."
                : $"Access denied to {path}: {ex.Message}");
        }
        catch (IOException ex)
        {
            return (false, $"I/O error accessing {path}: {ex.Message}");
        }

        if (IsMappedDriveLetterNotVisible(path))
        {
            return (false,
                $"Path \"{path}\" is not reachable. Mapped drive letters (e.g. Z:) belong to the interactive Windows session and are not available to a Windows service. " +
                "Use the UNC path of the share instead, for example \\\\server\\share\\Audiobooks. The service account must also have read access to that share.");
        }

        if (IsUncPath(path))
        {
            return (false,
                $"Path \"{path}\" is not reachable. Either the share name is wrong or the Fabula service account does not have read access to it. " +
                "Configure the service to log on as a user that can access the share (services.msc -> Fabula -> Properties -> Log On).");
        }

        return (false, $"Path does not exist: {path}");
    }

    private static bool IsUncPath(string path) =>
        path.StartsWith(@"\\", StringComparison.Ordinal) ||
        path.StartsWith("//", StringComparison.Ordinal);

    private static bool IsMappedDriveLetterNotVisible(string path)
    {
        if (path.Length < 2 || !char.IsLetter(path[0]) || path[1] != ':')
            return false;
        if (!OperatingSystem.IsWindows())
            return false;

        var letter = char.ToUpperInvariant(path[0]);
        try
        {
            var visible = DriveInfo.GetDrives()
                .Select(d => char.ToUpperInvariant(d.Name[0]));
            return !visible.Contains(letter);
        }
        catch
        {
            return false;
        }
    }
}

public record LibraryFolderDto(int Id, string Name, string Path, DateTime? LastScanAt);
public record CreateLibraryFolderRequest(string Name, string Path);
