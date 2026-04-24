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

            if (!Directory.Exists(req.Path))
                return Results.BadRequest(new { error = $"Path does not exist: {req.Path}" });

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
}

public record LibraryFolderDto(int Id, string Name, string Path, DateTime? LastScanAt);
public record CreateLibraryFolderRequest(string Name, string Path);
