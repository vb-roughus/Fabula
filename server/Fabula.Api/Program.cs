using Fabula.Api.Endpoints;
using Fabula.Api.Infrastructure;
using Fabula.Core.Services;
using Fabula.Data;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Hosting.WindowsServices;

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
app.MapSeriesEndpoints();
app.MapStreamingEndpoints();
app.MapProgressEndpoints();
app.MapBookmarkEndpoints();

app.MapFallbackToFile("index.html");
app.UseDefaultFiles();
app.UseStaticFiles();

app.Run();
