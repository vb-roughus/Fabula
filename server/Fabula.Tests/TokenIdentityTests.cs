using System.IdentityModel.Tokens.Jwt;
using System.Security.Claims;
using Fabula.Api.Infrastructure;
using Xunit;

namespace Fabula.Tests;

/// <summary>
/// Re-deciding what a token is allowed to do, every request.
///
/// The trap this guards is specific: the Admin policy is satisfied by *any*
/// matching claim, so adding the current value beside a stale "admin: true"
/// would look like it works while leaving a demoted account fully
/// administering. That failure would be invisible -- nothing errors, the
/// account simply keeps its rights.
/// </summary>
public class TokenIdentityTests
{
    private static ClaimsPrincipal principal(params Claim[] claims) =>
        new(new ClaimsIdentity(claims, "TestScheme"));

    private static Claim sub(string value) => new(JwtRegisteredClaimNames.Sub, value);

    // --- reading the subject -----------------------------------------------

    [Fact]
    public void Reads_the_user_id_from_the_subject_claim()
    {
        Assert.Equal(42, TokenIdentity.SubjectId(principal(sub("42"))));
    }

    /// <summary>
    /// Some handlers map `sub` onto the standard name identifier instead, so
    /// both spellings have to be understood -- reading neither would lock
    /// everyone out.
    /// </summary>
    [Fact]
    public void Falls_back_to_the_name_identifier_claim()
    {
        var p = principal(new Claim(ClaimTypes.NameIdentifier, "7"));
        Assert.Equal(7, TokenIdentity.SubjectId(p));
    }

    /// <summary>
    /// Null means "reject", so anything unreadable has to land here rather than
    /// resolve to some default id -- which would authenticate as whoever that is.
    /// </summary>
    [Theory]
    [InlineData("")]
    [InlineData("   ")]
    [InlineData("not-a-number")]
    [InlineData("1.5")]
    [InlineData("99999999999999999999")]
    public void Refuses_an_unusable_subject(string value)
    {
        Assert.Null(TokenIdentity.SubjectId(principal(sub(value))));
    }

    [Fact]
    public void Refuses_a_token_with_no_subject_at_all()
    {
        Assert.Null(TokenIdentity.SubjectId(principal(new Claim("username", "rolf"))));
    }

    // --- refreshing the admin flag -----------------------------------------

    /// <summary>The whole point: rights taken away take effect on the next request.</summary>
    [Fact]
    public void Drops_a_stale_admin_claim_when_the_account_is_no_longer_admin()
    {
        var stale = principal(sub("1"), new Claim(TokenIdentity.AdminClaim, "true"));

        var refreshed = TokenIdentity.WithCurrentAdmin(stale, isAdmin: false);

        Assert.Empty(refreshed.FindAll(TokenIdentity.AdminClaim));
        Assert.False(refreshed.HasClaim(TokenIdentity.AdminClaim, "true"));
    }

    /// <summary>
    /// And exactly one claim afterwards, not two. A leftover duplicate is what
    /// would keep the policy satisfied after a demotion.
    /// </summary>
    [Fact]
    public void Leaves_exactly_one_admin_claim_when_the_account_is_admin()
    {
        var stale = principal(sub("1"), new Claim(TokenIdentity.AdminClaim, "true"));

        var refreshed = TokenIdentity.WithCurrentAdmin(stale, isAdmin: true);

        Assert.Single(refreshed.FindAll(TokenIdentity.AdminClaim));
        Assert.True(refreshed.HasClaim(TokenIdentity.AdminClaim, "true"));
    }

    /// <summary>Promotion takes effect the same way, without re-issuing a token.</summary>
    [Fact]
    public void Grants_admin_to_a_token_that_was_issued_without_it()
    {
        var plain = principal(sub("1"), new Claim("username", "rolf"));

        var refreshed = TokenIdentity.WithCurrentAdmin(plain, isAdmin: true);

        Assert.True(refreshed.HasClaim(TokenIdentity.AdminClaim, "true"));
    }

    /// <summary>
    /// Everything else about the identity has to survive. Losing the subject
    /// would break every endpoint; losing the authentication type would make
    /// the principal count as unauthenticated.
    /// </summary>
    [Fact]
    public void Keeps_the_rest_of_the_identity_intact()
    {
        var original = principal(
            sub("42"),
            new Claim("username", "rolf"),
            new Claim(TokenIdentity.AdminClaim, "true"));

        var refreshed = TokenIdentity.WithCurrentAdmin(original, isAdmin: false);

        Assert.Equal(42, TokenIdentity.SubjectId(refreshed));
        Assert.Equal("rolf", refreshed.FindFirstValue("username"));
        Assert.True(refreshed.Identity!.IsAuthenticated);
        Assert.Equal("TestScheme", refreshed.Identity!.AuthenticationType);
    }

    /// <summary>
    /// A forged extra claim must not survive either -- the rebuild keeps only
    /// what the database just confirmed.
    /// </summary>
    [Fact]
    public void Removes_every_admin_claim_not_just_the_first()
    {
        var doubled = principal(
            sub("1"),
            new Claim(TokenIdentity.AdminClaim, "true"),
            new Claim(TokenIdentity.AdminClaim, "true"));

        var refreshed = TokenIdentity.WithCurrentAdmin(doubled, isAdmin: false);

        Assert.Empty(refreshed.FindAll(TokenIdentity.AdminClaim));
    }
}
