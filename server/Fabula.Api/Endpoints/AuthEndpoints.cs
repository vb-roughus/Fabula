using Fabula.Api.Infrastructure;
using Fabula.Core.Domain;
using Fabula.Data;
using Microsoft.AspNetCore.Identity;
using Microsoft.EntityFrameworkCore;

namespace Fabula.Api.Endpoints;

public static class AuthEndpoints
{
    private const int MinPasswordLength = 6;

    public static IEndpointRouteBuilder MapAuthEndpoints(this IEndpointRouteBuilder app)
    {
        // Setup wizard ----------------------------------------------------
        app.MapGet("/api/setup", async (FabulaDbContext db, CancellationToken ct) =>
        {
            var needsSetup = !await db.Users.AnyAsync(
                u => u.IsAdmin && u.PasswordHash != "" && u.PasswordHash != null,
                ct);
            return Results.Ok(new SetupStatus(needsSetup));
        }).WithTags("Auth");

        app.MapPost("/api/setup", async (
            SetupRequest req,
            FabulaDbContext db,
            IPasswordHasher<User> hasher,
            JwtTokenService tokens,
            CancellationToken ct) =>
        {
            var error = ValidateCredentials(req.Username, req.Password);
            if (error is not null) return Results.BadRequest(new { error });

            var hasAdmin = await db.Users.AnyAsync(
                u => u.IsAdmin && u.PasswordHash != "" && u.PasswordHash != null,
                ct);
            if (hasAdmin)
                return Results.Conflict(new { error = "Setup already completed." });

            // Migration: the previous "TemporaryUserId = 1" code path created
            // a stub user (Id=1, empty PasswordHash). Adopt that row so the
            // existing PlaybackProgress / Bookmark FKs survive.
            var user = await db.Users.FirstOrDefaultAsync(
                u => u.Id == 1 && (u.PasswordHash == "" || u.PasswordHash == null),
                ct);

            if (user is null)
            {
                user = new User
                {
                    Username = req.Username.Trim(),
                    IsAdmin = true,
                    CreatedAt = DateTime.UtcNow
                };
                user.PasswordHash = hasher.HashPassword(user, req.Password);
                db.Users.Add(user);
            }
            else
            {
                user.Username = req.Username.Trim();
                user.IsAdmin = true;
                user.PasswordHash = hasher.HashPassword(user, req.Password);
            }

            await db.SaveChangesAsync(ct);
            return Results.Ok(new AuthResponse(tokens.Issue(user), ToDto(user)));
        }).WithTags("Auth");

        // Login + me ------------------------------------------------------
        // Four different refusals share one 401 on the wire, and that is
        // deliberate: telling a caller "this user exists but the password is
        // wrong" hands them half the credential. The distinction belongs in the
        // log, where the operator can see it and an attacker cannot.
        app.MapPost("/api/auth/login", async (
            LoginRequest req,
            FabulaDbContext db,
            IPasswordHasher<User> hasher,
            JwtTokenService tokens,
            ILoggerFactory loggers,
            CancellationToken ct) =>
        {
            var log = loggers.CreateLogger("Fabula.Auth");

            if (string.IsNullOrWhiteSpace(req.Username) || string.IsNullOrEmpty(req.Password))
            {
                log.LogWarning("Anmeldung abgewiesen: Benutzername oder Passwort war leer.");
                return Results.Unauthorized();
            }

            var name = req.Username.Trim();
            var user = await db.Users.WhereUsernameMatches(name).FirstOrDefaultAsync(ct);
            if (user is null)
            {
                log.LogWarning("Anmeldung abgewiesen für \"{User}\": kein Konto mit diesem Namen.", name);
                return Results.Unauthorized();
            }
            if (string.IsNullOrEmpty(user.PasswordHash))
            {
                log.LogWarning(
                    "Anmeldung abgewiesen für \"{User}\" (id {UserId}): für das Konto ist kein Passwort gesetzt.",
                    name, user.Id);
                return Results.Unauthorized();
            }

            var verify = hasher.VerifyHashedPassword(user, user.PasswordHash, req.Password);
            if (verify == PasswordVerificationResult.Failed)
            {
                log.LogWarning(
                    "Anmeldung abgewiesen für \"{User}\" (id {UserId}): Passwort stimmt nicht.",
                    name, user.Id);
                return Results.Unauthorized();
            }

            if (verify == PasswordVerificationResult.SuccessRehashNeeded)
            {
                user.PasswordHash = hasher.HashPassword(user, req.Password);
                await db.SaveChangesAsync(ct);
            }

            // The counterpart to the refusals: seeing a success here and a
            // rejection a moment later is what separates "cannot log in" from
            // "logged in, then thrown out again".
            log.LogInformation(
                "Anmeldung erfolgreich: \"{User}\" (id {UserId}, Admin: {IsAdmin}).",
                name, user.Id, user.IsAdmin);
            return Results.Ok(new AuthResponse(tokens.Issue(user), ToDto(user)));
        }).WithTags("Auth");

        app.MapGet("/api/auth/me", async (
            HttpContext http,
            FabulaDbContext db,
            ILoggerFactory loggers,
            CancellationToken ct) =>
        {
            var id = http.UserId();
            var user = await db.Users.FirstOrDefaultAsync(u => u.Id == id, ct);
            if (user is null)
            {
                // A token that names an account which is not there. Same
                // question the token check asks, reached by a different route,
                // so it is worth being able to tell the two apart in the log.
                loggers.CreateLogger("Fabula.Auth").LogWarning(
                    "/api/auth/me abgewiesen: Token nennt Konto {UserId}, das es nicht gibt.", id);
                return Results.Unauthorized();
            }
            return Results.Ok(ToDto(user));
        }).RequireAuthorization().WithTags("Auth");

        app.MapPost("/api/me/password", async (
            ChangePasswordRequest req,
            HttpContext http,
            FabulaDbContext db,
            IPasswordHasher<User> hasher,
            CancellationToken ct) =>
        {
            if (req.NewPassword is null || req.NewPassword.Length < MinPasswordLength)
                return Results.BadRequest(new { error = $"Password must be at least {MinPasswordLength} characters." });

            var user = await db.Users.FirstOrDefaultAsync(u => u.Id == http.UserId(), ct);
            if (user is null) return Results.Unauthorized();

            var verify = hasher.VerifyHashedPassword(user, user.PasswordHash, req.CurrentPassword ?? "");
            if (verify == PasswordVerificationResult.Failed)
                return Results.BadRequest(new { error = "Current password is incorrect." });

            user.PasswordHash = hasher.HashPassword(user, req.NewPassword);
            await db.SaveChangesAsync(ct);
            return Results.NoContent();
        }).RequireAuthorization().WithTags("Auth");

        return app;
    }

    public static IEndpointRouteBuilder MapUserEndpoints(this IEndpointRouteBuilder app)
    {
        var group = app.MapGroup("/api/users")
            .WithTags("Users")
            .RequireAuthorization("Admin");

        group.MapGet("/", async (FabulaDbContext db, CancellationToken ct) =>
        {
            var users = await db.Users
                .AsNoTracking()
                .OrderBy(u => u.Username)
                .Select(u => new UserDetailDto(u.Id, u.Username, u.IsAdmin, u.CreatedAt))
                .ToListAsync(ct);
            return Results.Ok(users);
        });

        group.MapPost("/", async (
            CreateUserRequest req,
            FabulaDbContext db,
            IPasswordHasher<User> hasher,
            CancellationToken ct) =>
        {
            var error = ValidateCredentials(req.Username, req.Password);
            if (error is not null) return Results.BadRequest(new { error });

            var name = req.Username.Trim();
            // Case-insensitively, matching how login looks names up: two
            // accounts differing only in case would make a login ambiguous.
            if (await db.Users.WhereUsernameMatches(name).AnyAsync(ct))
                return Results.Conflict(new { error = $"User \"{name}\" already exists." });

            var user = new User
            {
                Username = name,
                IsAdmin = req.IsAdmin,
                CreatedAt = DateTime.UtcNow
            };
            user.PasswordHash = hasher.HashPassword(user, req.Password);
            db.Users.Add(user);
            await db.SaveChangesAsync(ct);
            return Results.Created($"/api/users/{user.Id}",
                new UserDetailDto(user.Id, user.Username, user.IsAdmin, user.CreatedAt));
        });

        group.MapDelete("/{id:int}", async (
            int id,
            HttpContext http,
            FabulaDbContext db,
            CancellationToken ct) =>
        {
            if (id == http.UserId())
                return Results.BadRequest(new { error = "You cannot delete your own account." });

            var user = await db.Users.FirstOrDefaultAsync(u => u.Id == id, ct);
            if (user is null) return Results.NotFound();

            if (user.IsAdmin)
            {
                var otherAdmins = await db.Users.CountAsync(u => u.Id != id && u.IsAdmin, ct);
                if (otherAdmins == 0)
                    return Results.BadRequest(new { error = "Cannot delete the last admin." });
            }

            db.Users.Remove(user);
            await db.SaveChangesAsync(ct);
            return Results.NoContent();
        });

        group.MapPost("/{id:int}/password", async (
            int id,
            AdminResetPasswordRequest req,
            FabulaDbContext db,
            IPasswordHasher<User> hasher,
            CancellationToken ct) =>
        {
            if (req.NewPassword is null || req.NewPassword.Length < MinPasswordLength)
                return Results.BadRequest(new { error = $"Password must be at least {MinPasswordLength} characters." });

            var user = await db.Users.FirstOrDefaultAsync(u => u.Id == id, ct);
            if (user is null) return Results.NotFound();

            user.PasswordHash = hasher.HashPassword(user, req.NewPassword);
            await db.SaveChangesAsync(ct);
            return Results.NoContent();
        });

        group.MapPost("/{id:int}/admin", async (
            int id,
            SetAdminRequest req,
            HttpContext http,
            FabulaDbContext db,
            CancellationToken ct) =>
        {
            var user = await db.Users.FirstOrDefaultAsync(u => u.Id == id, ct);
            if (user is null) return Results.NotFound();

            if (!req.IsAdmin && user.IsAdmin)
            {
                var otherAdmins = await db.Users.CountAsync(u => u.Id != id && u.IsAdmin, ct);
                if (otherAdmins == 0)
                    return Results.BadRequest(new { error = "Cannot demote the last admin." });
            }

            user.IsAdmin = req.IsAdmin;
            await db.SaveChangesAsync(ct);
            return Results.NoContent();
        });

        return app;
    }

    private static AuthUser ToDto(User user) => new(user.Id, user.Username, user.IsAdmin);

    private static string? ValidateCredentials(string? username, string? password)
    {
        if (string.IsNullOrWhiteSpace(username))
            return "Username is required.";
        if (string.IsNullOrEmpty(password) || password.Length < MinPasswordLength)
            return $"Password must be at least {MinPasswordLength} characters.";
        return null;
    }
}

public record SetupStatus(bool NeedsSetup);
public record SetupRequest(string Username, string Password);
public record LoginRequest(string Username, string Password);
public record AuthResponse(string Token, AuthUser User);
public record AuthUser(int Id, string Username, bool IsAdmin);
public record ChangePasswordRequest(string CurrentPassword, string NewPassword);
public record CreateUserRequest(string Username, string Password, bool IsAdmin);
public record AdminResetPasswordRequest(string NewPassword);
public record SetAdminRequest(bool IsAdmin);
public record UserDetailDto(int Id, string Username, bool IsAdmin, DateTime CreatedAt);
