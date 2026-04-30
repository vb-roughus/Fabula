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

    public static bool IsAdmin(this HttpContext ctx) =>
        ctx.User.FindFirstValue("admin") == "true";
}
