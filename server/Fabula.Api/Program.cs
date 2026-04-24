using Fabula.Api.Endpoints;
using Fabula.Api.Infrastructure;
using Fabula.Core.Services;
using Fabula.Data;
using Microsoft.EntityFrameworkCore;

var builder = WebApplication.CreateBuilder(args);

builder.Services.Configure<FabulaOptions>(builder.Configuration.GetSection(FabulaOptions.SectionName));
var fabulaOptions = builder.Configuration.GetSection(FabulaOptions.SectionName).Get<FabulaOptions>() ?? new FabulaOptions();
Directory.CreateDirectory(fabulaOptions.DataDirectory);

var dbPath = Path.Combine(fabulaOptions.DataDirectory, "fabula.db");
var connectionString = $"Data Source={dbPath}";

builder.Services.AddFabulaData(connectionString);
builder.Services.AddScoped<ILibraryRepository, LibraryRepository>();
builder.Services.AddScoped<IStreamingService, StreamingService>();
builder.Services.AddScoped<ILibraryScanner, LibraryScanner>();
builder.Services.AddSingleton<IAudioMetadataReader, AtlAudioMetadataReader>();
builder.Services.AddSingleton<ICoverStore, FileSystemCoverStore>();

builder.Services.AddOpenApi();

var app = builder.Build();

using (var scope = app.Services.CreateScope())
{
    var db = scope.ServiceProvider.GetRequiredService<FabulaDbContext>();
    db.Database.Migrate();
}

app.MapOpenApi();

app.MapGet("/health", () => Results.Ok(new { status = "ok" }));

app.MapLibraryEndpoints();
app.MapBookEndpoints();
app.MapStreamingEndpoints();
app.MapProgressEndpoints();

app.MapFallbackToFile("index.html");
app.UseDefaultFiles();
app.UseStaticFiles();

app.Run();
