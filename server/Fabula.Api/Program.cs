using System.Text.Json.Serialization;
using Fabula.Api.Endpoints;
using Fabula.Api.Infrastructure;
using Fabula.Core.Services;
using Fabula.Data;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Hosting.WindowsServices;
using NReco.Logging.File;

var isWindowsService = WindowsServiceHelpers.IsWindowsService();

var builder = WebApplication.CreateBuilder(new WebApplicationOptions
{
    Args = args,
    // When started by the Windows SCM the working directory is %WINDIR%\System32;
    // anchor everything to the install directory so relative paths in
    // appsettings/Production resolve correctly.
    ContentRootPath = isWindowsService ? AppContext.BaseDirectory : null
});

builder.Host.UseWindowsService(o => o.ServiceName = "Fabula");

var rawOptions = builder.Configuration.GetSection(FabulaOptions.SectionName).Get<FabulaOptions>() ?? new FabulaOptions();

string ResolvePath(string path) => Path.IsPathRooted(path)
    ? path
    : Path.GetFullPath(Path.Combine(builder.Environment.ContentRootPath, path));

var dataDirectory = ResolvePath(rawOptions.DataDirectory);
var coversDirectory = string.IsNullOrWhiteSpace(rawOptions.CoversDirectory)
    ? Path.Combine(dataDirectory, "covers")
    : ResolvePath(rawOptions.CoversDirectory);
Directory.CreateDirectory(dataDirectory);
Directory.CreateDirectory(coversDirectory);

builder.Services.Configure<FabulaOptions>(o =>
{
    o.DataDirectory = dataDirectory;
    o.CoversDirectory = coversDirectory;
});

var dbPath = Path.Combine(dataDirectory, "fabula.db");
var connectionString = $"Data Source={dbPath}";

// Rolling text log alongside the data dir. Far easier to grep than the
// Windows Event Viewer when something goes wrong during a scan. Path and
// rotation are configured via "Logging:File" in appsettings.Production.json.
var logsDirectory = Path.Combine(Path.GetDirectoryName(dataDirectory) ?? dataDirectory, "logs");
Directory.CreateDirectory(logsDirectory);
builder.Logging.AddFile(builder.Configuration.GetSection("Logging:File"));

builder.Services.AddFabulaData(connectionString);
builder.Services.AddScoped<ILibraryRepository, LibraryRepository>();
builder.Services.AddScoped<IStreamingService, StreamingService>();
builder.Services.AddScoped<ILibraryScanner, LibraryScanner>();
builder.Services.AddSingleton<IAudioMetadataReader, AtlAudioMetadataReader>();
builder.Services.AddSingleton<ICoverStore, FileSystemCoverStore>();
builder.Services.AddSingleton<ScanCoordinator>();

// Serialise enums as their string names so the web client can compare
// against e.g. "Running" instead of the underlying numeric value.
builder.Services.ConfigureHttpJsonOptions(o =>
    o.SerializerOptions.Converters.Add(new JsonStringEnumConverter()));

builder.Services.AddOpenApi();

var app = builder.Build();

using (var scope = app.Services.CreateScope())
{
    var db = scope.ServiceProvider.GetRequiredService<FabulaDbContext>();
    db.Database.Migrate();
}

// Static files have to be wired up BEFORE the API endpoints and the
// SPA fallback, otherwise index.html and the /assets/*.js bundles
// never reach the browser.
app.UseDefaultFiles();
app.UseStaticFiles();

app.MapOpenApi();

app.MapGet("/health", () => Results.Ok(new { status = "ok" }));

app.MapLibraryEndpoints();
app.MapBookEndpoints();
app.MapSeriesEndpoints();
app.MapStreamingEndpoints();
app.MapProgressEndpoints();
app.MapBookmarkEndpoints();

app.MapFallbackToFile("index.html");

app.Run();
