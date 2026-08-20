using System.Globalization;
using System.IdentityModel.Tokens.Jwt;
using System.Security.Claims;

namespace Fabula.Api.Infrastructure;

/// <summary>
/// Claim handling for re-checking a token against the account it names.
///
/// A JWT is a snapshot. Fabula's live for 30 days and carry the admin flag
/// inside them, so left to themselves they outlast the decisions they describe:
/// a deleted account would keep reading and streaming, and an administrator
/// whose rights were taken away would keep administering, until the token
/// happened to expire.
/// </summary>
public static class TokenIdentity
{
    public const string AdminClaim = "admin";

    /// <summary>The user id a token names, or null when it carries none usable.</summary>
    public static int? SubjectId(ClaimsPrincipal principal)
    {
        var sub = principal.FindFirstValue(JwtRegisteredClaimNames.Sub)
                  ?? principal.FindFirstValue(ClaimTypes.NameIdentifier);
        return int.TryParse(sub, NumberStyles.Integer, CultureInfo.InvariantCulture, out var id)
            ? id
            : null;
    }

    /// <summary>
    /// The same identity, but with the admin claim saying what the database
    /// says rather than what the token said when it was issued.
    ///
    /// The stale claim is dropped rather than overridden. The Admin policy is
    /// satisfied by *any* matching claim, so merely adding the current value
    /// beside an old "admin: true" would leave a demoted account administering
    /// -- the failure mode this whole class exists to close.
    ///
    /// Rebuilt rather than mutated: removing a claim from an existing identity
    /// depends on who owns it, and quietly does nothing when it isn't ours.
    /// </summary>
    public static ClaimsPrincipal WithCurrentAdmin(ClaimsPrincipal principal, bool isAdmin)
    {
        var source = principal.Identity as ClaimsIdentity;
        var claims = principal.Claims.Where(c => c.Type != AdminClaim).ToList();
        if (isAdmin) claims.Add(new Claim(AdminClaim, "true"));

        return new ClaimsPrincipal(new ClaimsIdentity(
            claims,
            source?.AuthenticationType,
            source?.NameClaimType ?? ClaimsIdentity.DefaultNameClaimType,
            source?.RoleClaimType ?? ClaimsIdentity.DefaultRoleClaimType));
    }
}
