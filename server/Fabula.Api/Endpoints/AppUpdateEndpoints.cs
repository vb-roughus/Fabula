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

        // --- Admin: view / edit / test the update configuration ------------

        group.MapGet("/config", (AppUpdateService updates) =>
            Results.Ok(updates.GetSettings()))
            .RequireAuthorization("Admin");

        group.MapPut("/config", async (UpdateAppConfigRequest req, AppUpdateService updates, CancellationToken ct) =>
        {
            var settings = await updates.UpdateSettingsAsync(req.Repo, req.Token, ct);
            return Results.Ok(settings);
        }).RequireAuthorization("Admin");

        // Force an immediate GitHub check and report exactly what happened, so
        // the operator can diagnose repo/token issues from the app.
        group.MapPost("/check", async (AppUpdateService updates, CancellationToken ct) =>
            Results.Ok(await updates.CheckNowAsync(ct)))
            .RequireAuthorization("Admin");

        return app;
    }
}

public record AppVersionDto(int VersionCode, string VersionName);
public record UpdateAppConfigRequest(string? Repo, string? Token);
