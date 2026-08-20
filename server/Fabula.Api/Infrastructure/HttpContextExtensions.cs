using System.Globalization;
using System.IdentityModel.Tokens.Jwt;
using System.Security.Claims;

namespace Fabula.Api.Infrastructure;

public static class HttpContextExtensions
{
    public static int UserId(this HttpContext ctx)
    {
        var sub = ctx.User.FindFirstValue(JwtRegisteredClaimNames.Sub)
                  ?? ctx.User.FindFirstValue(ClaimTypes.NameIdentifier)
                  ?? throw new InvalidOperationException("Authenticated request is missing the sub claim.");
        return int.Parse(sub, CultureInfo.InvariantCulture);
    }

    // Reads the same claim the Admin policy checks, which OnTokenValidated has
    // already refreshed from the database -- so this is current, not whatever
    // was true when the token was issued.
    public static bool IsAdmin(this HttpContext ctx) =>
        ctx.User.FindFirstValue(TokenIdentity.AdminClaim) == "true";
}
