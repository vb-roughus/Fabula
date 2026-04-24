using Fabula.Core.Services;
using Microsoft.AspNetCore.StaticFiles;

namespace Fabula.Api.Endpoints;

public static class StreamingEndpoints
{
    private static readonly FileExtensionContentTypeProvider MimeProvider = new();

    public static IEndpointRouteBuilder MapStreamingEndpoints(this IEndpointRouteBuilder app)
    {
        app.MapGet("/api/stream/{fileId:int}", async (int fileId, IStreamingService streaming, CancellationToken ct) =>
        {
            var file = await streaming.GetAudioFileAsync(fileId, ct);
            if (file is null || !File.Exists(file.Path)) return Results.NotFound();

            if (!MimeProvider.TryGetContentType(file.Path, out var contentType))
                contentType = "application/octet-stream";

            return Results.File(
                path: file.Path,
                contentType: contentType,
                enableRangeProcessing: true,
                fileDownloadName: null);
        }).WithTags("Streaming");

        return app;
    }
}
