using System.Text.Json.Serialization;
using Fabula.Api.Endpoints;
using Fabula.Api.Infrastructure;
using Fabula.Core.Domain;
using Fabula.Core.Services;
using Fabula.Data;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.AspNetCore.Identity;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Hosting.WindowsServices;
using Microsoft.Extensions.Options;
using Microsoft.IdentityModel.Tokens;
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

// Operator-owned settings that must survive an in-place upgrade. appsettings.*
// live under the install dir (Program Files) and get overwritten on every
// reinstall, so the installer instead writes things like the update token to
// %ProgramData%\Fabula\fabula.settings.json. Loaded last => it overrides the
// shipped defaults. Override the location with FABULA_SETTINGS_FILE (handy on
// Linux/Docker).
var operatorSettingsFile = Environment.GetEnvironmentVariable("FABULA_SETTINGS_FILE")
    ?? Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData),
        "Fabula", "fabula.settings.json");
builder.Configuration.AddJsonFile(operatorSettingsFile, optional: true, reloadOnChange: true);

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
    o.UpdateRepo = rawOptions.UpdateRepo;
    o.UpdateGithubToken = rawOptions.UpdateGithubToken;
    o.UpdateCheckMinutes = rawOptions.UpdateCheckMinutes;
    o.SettingsFilePath = operatorSettingsFile;
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
builder.Services.AddSingleton<AppUpdateService>();
builder.Services.AddSingleton<ServerUpdateService>();

// Serialise enums as their string names so the web client can compare
// against e.g. "Running" instead of the underlying numeric value.
builder.Services.ConfigureHttpJsonOptions(o =>
    o.SerializerOptions.Converters.Add(new JsonStringEnumConverter()));

// --- Auth ---------------------------------------------------------------
var jwtKeyBytes = JwtKeyProvider.LoadOrCreate(dataDirectory);
builder.Services.Configure<JwtOptions>(o => o.SigningKey = Convert.ToBase64String(jwtKeyBytes));
builder.Services.AddSingleton<IPasswordHasher<User>, PasswordHasher<User>>();
builder.Services.AddSingleton<JwtTokenService>();

builder.Services.AddAuthentication(JwtBearerDefaults.AuthenticationScheme)
    .AddJwtBearer(o =>
    {
        o.TokenValidationParameters = new TokenValidationParameters
        {
            ValidateIssuer = true,
            ValidIssuer = "fabula",
            ValidateAudience = true,
            ValidAudience = "fabula",
            ValidateLifetime = true,
            ValidateIssuerSigningKey = true,
            IssuerSigningKey = new SymmetricSecurityKey(jwtKeyBytes),
            ClockSkew = TimeSpan.FromMinutes(1)
        };
        o.Events = new JwtBearerEvents
        {
            // ?access_token=... is honored ONLY for /api/stream so the web
            // <audio> element (which can't send Authorization headers) can
            // still authenticate. Every other endpoint keeps requiring the
            // header.
            OnMessageReceived = ctx =>
            {
                if (ctx.HttpContext.Request.Path.StartsWithSegments("/api/stream") &&
                    ctx.Request.Query.TryGetValue("access_token", out var token))
                {
                    ctx.Token = token;
                }
                return Task.CompletedTask;
            },

            // A valid signature only proves the token was ours; it says nothing
            // about whether the account still exists or still has the rights
            // the token claims. Tokens live for 30 days and carry the admin
            // flag, so without this a deleted user keeps reading and streaming
            // and a demoted admin keeps administering until it expires.
            //
            // Costs one primary-key lookup per authenticated request, which is
            // the price of the answer being current rather than up to a month
            // old. Deliberately not cached: a revocation that takes effect
            // "soon" is the thing we are fixing.
            OnTokenValidated = async ctx =>
            {
                var id = TokenIdentity.SubjectId(ctx.Principal!);
                if (id is null)
                {
                    ctx.Fail("Token ohne verwertbare Benutzerkennung.");
                    return;
                }

                var db = ctx.HttpContext.RequestServices.GetRequiredService<FabulaDbContext>();
                var account = await db.Users
                    .AsNoTracking()
                    .Where(u => u.Id == id.Value)
                    .Select(u => new { u.IsAdmin })
                    .FirstOrDefaultAsync(ctx.HttpContext.RequestAborted);

                if (account is null)
                {
                    // 401, which both clients already handle as "log out".
                    ctx.Fail("Benutzerkonto existiert nicht mehr.");
                    return;
                }

                ctx.Principal = TokenIdentity.WithCurrentAdmin(ctx.Principal!, account.IsAdmin);
            }
        };
    });

builder.Services.AddAuthorization(o =>
    o.AddPolicy("Admin", p => p.RequireAuthenticatedUser().RequireClaim(TokenIdentity.AdminClaim, "true")));
// ------------------------------------------------------------------------

builder.Services.AddOpenApi();

var app = builder.Build();

using (var scope = app.Services.CreateScope())
{
    var db = scope.ServiceProvider.GetRequiredService<FabulaDbContext>();
    db.Database.Migrate();
}

// Resolved eagerly so it judges the outcome of a self-update on boot and logs
// it. If we waited for the first request, an update that nobody watched would
// leave no trace at all -- and the log is the fallback account when the admin
// closed the browser while the service was down.
app.Services.GetRequiredService<ServerUpdateService>();

// Static files have to be wired up BEFORE the API endpoints and the
// SPA fallback, otherwise index.html and the /assets/*.js bundles
// never reach the browser.
app.UseDefaultFiles();
app.UseStaticFiles();

app.UseAuthentication();
app.UseAuthorization();

app.MapOpenApi();

app.MapGet("/health", () => Results.Ok(new { status = "ok" }));

app.MapAuthEndpoints();
app.MapUserEndpoints();
app.MapLibraryEndpoints();
app.MapBookEndpoints();
app.MapSeriesEndpoints();
app.MapStreamingEndpoints();
app.MapProgressEndpoints();
app.MapBookmarkEndpoints();
app.MapHighlightEndpoints();
app.MapAppUpdateEndpoints();
app.MapServerUpdateEndpoints();

app.MapFallbackToFile("index.html");

app.Run();
