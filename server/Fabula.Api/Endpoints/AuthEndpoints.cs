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
        app.MapPost("/api/auth/login", async (
            LoginRequest req,
            FabulaDbContext db,
            IPasswordHasher<User> hasher,
            JwtTokenService tokens,
            CancellationToken ct) =>
        {
            if (string.IsNullOrWhiteSpace(req.Username) || string.IsNullOrEmpty(req.Password))
                return Results.Unauthorized();

            var user = await db.Users.FirstOrDefaultAsync(
                u => u.Username == req.Username.Trim(),
                ct);
            if (user is null || string.IsNullOrEmpty(user.PasswordHash))
                return Results.Unauthorized();

            var verify = hasher.VerifyHashedPassword(user, user.PasswordHash, req.Password);
            if (verify == PasswordVerificationResult.Failed)
                return Results.Unauthorized();

            if (verify == PasswordVerificationResult.SuccessRehashNeeded)
            {
                user.PasswordHash = hasher.HashPassword(user, req.Password);
                await db.SaveChangesAsync(ct);
            }

            return Results.Ok(new AuthResponse(tokens.Issue(user), ToDto(user)));
        }).WithTags("Auth");

        app.MapGet("/api/auth/me", async (HttpContext http, FabulaDbContext db, CancellationToken ct) =>
        {
            var user = await db.Users.FirstOrDefaultAsync(u => u.Id == http.UserId(), ct);
            return user is null ? Results.Unauthorized() : Results.Ok(ToDto(user));
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
            if (await db.Users.AnyAsync(u => u.Username == name, ct))
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
