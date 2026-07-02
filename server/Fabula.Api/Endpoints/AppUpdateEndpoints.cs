using Fabula.Api.Infrastructure;

namespace Fabula.Api.Endpoints;

public static class AppUpdateEndpoints
{
    public static IEndpointRouteBuilder MapAppUpdateEndpoints(this IEndpointRouteBuilder app)
    {
        var group = app.MapGroup("/api/app").WithTags("AppUpdate").RequireAuthorization();

        // Newest APK the server knows about. 404 until the first release has
        // been mirrored (or when no UpdateRepo is configured).
        group.MapGet("/version", async (AppUpdateService updates, CancellationToken ct) =>
        {
            var latest = await updates.GetLatestAsync(ct);
            return latest is null
                ? Results.NotFound()
                : Results.Ok(new AppVersionDto(latest.VersionCode, latest.VersionName));
        });

        group.MapGet("/apk", async (AppUpdateService updates, CancellationToken ct) =>
        {
            var latest = await updates.GetLatestAsync(ct);
            if (latest is null || !File.Exists(latest.ApkPath)) return Results.NotFound();
            return Results.File(
                latest.ApkPath,
                contentType: "application/vnd.android.package-archive",
                fileDownloadName: "fabula.apk");
        });

        return app;
    }
}

public record AppVersionDto(int VersionCode, string VersionName);
