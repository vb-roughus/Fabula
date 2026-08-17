using Fabula.Api.Infrastructure;

namespace Fabula.Api.Endpoints;

public static class ServerUpdateEndpoints
{
    public static IEndpointRouteBuilder MapServerUpdateEndpoints(this IEndpointRouteBuilder app)
    {
        // Every endpoint here is admin-only: they read the deployment's version
        // and one of them replaces the running binaries.
        var group = app.MapGroup("/api/server/update")
            .WithTags("ServerUpdate")
            .RequireAuthorization("Admin");

        // Running version, newest installer release, and where a previous
        // attempt got to. Also reports whether self-updating works here at all.
        group.MapGet("/", async (ServerUpdateService updates, CancellationToken ct) =>
            Results.Ok(await updates.GetInfoAsync(ct)));

        // Ask GitHub right now, bypassing the throttle, and say what happened --
        // the same diagnostic the app-update section offers.
        group.MapPost("/check", async (ServerUpdateService updates, CancellationToken ct) =>
            Results.Ok(await updates.CheckNowAsync(ct)));

        // Cheap enough to poll: no network, just the recorded state re-judged
        // against the clock. This is what the clients hammer while the service
        // is restarting.
        group.MapGet("/status", (ServerUpdateService updates) =>
            Results.Ok(updates.GetStatus()));

        // Starts the update and returns immediately. The response is the last
        // thing this process says on the subject -- the installer stops it a
        // few seconds later.
        group.MapPost("/", async (ServerUpdateService updates, CancellationToken ct) =>
        {
            var result = await updates.StartAsync(ct);
            return result.Started
                ? Results.Accepted("/api/server/update/status", result.Status)
                : Results.BadRequest(new { error = result.Error, status = result.Status });
        });

        return app;
    }
}
