using Fabula.Api.Infrastructure;
using Xunit;

namespace Fabula.Tests;

/// <summary>
/// Which release the server would install, and whether it trusts the download.
///
/// Worth pinning down because every mistake here is quiet and expensive: the
/// wrong tag means installing the Android APK release's version number, a slack
/// repo pattern means fetching an executable from somewhere else entirely, and a
/// broken checksum parser means either refusing every update or accepting a file
/// nobody vouched for.
/// </summary>
public class ServerUpdateSelectionTests
{
    // --- repository validation ---------------------------------------------

    [Theory]
    [InlineData("vb-roughus/Fabula")]
    [InlineData("a/b")]
    [InlineData("owner.name/repo.name")]
    [InlineData("0wner/repo-with_underscores.and.dots")]
    public void Accepts_plain_owner_slash_name(string repo) =>
        Assert.True(ServerUpdateLogic.IsValidRepo(repo));

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("   ")]
    [InlineData("no-slash")]
    [InlineData("too/many/slashes")]
    [InlineData("owner/")]
    [InlineData("/repo")]
    [InlineData("owner repo/x")]
    [InlineData("https://evil.example/x")]
    public void Rejects_anything_that_is_not_owner_slash_name(string? repo) =>
        Assert.False(ServerUpdateLogic.IsValidRepo(repo));

    /// <summary>
    /// The value is interpolated into https://api.github.com/repos/{repo}/...,
    /// so a dots-only segment would climb out of the intended path. Both halves
    /// have to be closed off, not just the first.
    /// </summary>
    [Theory]
    [InlineData("../..")]
    [InlineData("..%2f..")]
    [InlineData(".././x")]
    [InlineData("owner/..")]
    [InlineData("owner/.")]
    [InlineData(".hidden/repo")]
    public void Rejects_attempts_to_climb_out_of_the_repos_path(string repo) =>
        Assert.False(ServerUpdateLogic.IsValidRepo(repo));

    // --- tag and version parsing -------------------------------------------

    [Fact]
    public void Reads_the_version_out_of_an_installer_tag()
    {
        Assert.Equal(new Version(0, 3, 34), ServerUpdateLogic.ParseWinTag("win-v0.3.34"));
        Assert.Equal(new Version(1, 0, 0), ServerUpdateLogic.ParseWinTag("win-v1.0.0"));
    }

    /// <summary>
    /// The APK releases live in the same repository under `apk-v*`. Confusing
    /// the two would have the server offer to "update" to the app's version.
    /// </summary>
    [Theory]
    [InlineData("apk-v0.2.34")]
    [InlineData("v0.3.34")]
    [InlineData("win-v0.3")]
    [InlineData("win-v0.3.34.1")]
    [InlineData("win-vX.Y.Z")]
    [InlineData("")]
    [InlineData(null)]
    public void Ignores_tags_that_are_not_installer_releases(string? tag) =>
        Assert.Null(ServerUpdateLogic.ParseWinTag(tag));

    /// <summary>
    /// The running build reports four components and may carry build metadata;
    /// the tags never do. Both have to reduce to the same three numbers or every
    /// comparison is off.
    /// </summary>
    [Theory]
    [InlineData("0.3.34.0", 0, 3, 34)]
    [InlineData("0.3.34", 0, 3, 34)]
    [InlineData("0.3.34+abc123", 0, 3, 34)]
    [InlineData("0.3.34-beta.1", 0, 3, 34)]
    [InlineData("1.0", 1, 0, 0)]
    public void Normalises_versions_for_comparison(string raw, int major, int minor, int build) =>
        Assert.Equal(new Version(major, minor, build), ServerUpdateLogic.ParseVersion(raw));

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("nonsense")]
    public void Returns_null_for_an_unreadable_version(string? raw) =>
        Assert.Null(ServerUpdateLogic.ParseVersion(raw));

    [Fact]
    public void An_installer_tag_compares_against_the_running_assembly_version()
    {
        var running = ServerUpdateLogic.ParseVersion("0.3.34.0")!;
        Assert.True(ServerUpdateLogic.ParseWinTag("win-v0.3.35") > running);
        Assert.False(ServerUpdateLogic.ParseWinTag("win-v0.3.34") > running);
        Assert.False(ServerUpdateLogic.ParseWinTag("win-v0.3.33") > running);
    }

    // --- release selection --------------------------------------------------

    private static string Release(
        string tag, bool draft = false, bool prerelease = false, string? setup = "Fabula-Setup-0.0.0.exe",
        bool withHash = true)
    {
        var assets = new List<string>();
        if (setup is not null)
        {
            assets.Add($$"""{"name": "{{setup}}", "url": "https://api/assets/1"}""");
            if (withHash)
                assets.Add($$"""{"name": "{{setup}}.sha256", "url": "https://api/assets/2"}""");
        }
        return $$"""
        {
          "tag_name": "{{tag}}",
          "draft": {{(draft ? "true" : "false")}},
          "prerelease": {{(prerelease ? "true" : "false")}},
          "assets": [{{string.Join(",", assets)}}]
        }
        """;
    }

    private static string Releases(params string[] releases) => "[" + string.Join(",", releases) + "]";

    [Fact]
    public void Picks_the_highest_installer_release()
    {
        var json = Releases(
            Release("win-v0.3.9", setup: "Fabula-Setup-0.3.9.exe"),
            Release("win-v0.3.34", setup: "Fabula-Setup-0.3.34.exe"),
            Release("win-v0.3.12", setup: "Fabula-Setup-0.3.12.exe"));

        var picked = ServerUpdateLogic.SelectNewestSetupRelease(json);

        Assert.NotNull(picked);
        Assert.Equal(new Version(0, 3, 34), picked!.Version);
        Assert.Equal("Fabula-Setup-0.3.34.exe", picked.SetupAssetName);
        Assert.Equal("https://api/assets/2", picked.Sha256AssetUrl);
    }

    /// <summary>
    /// 34 beats 9 numerically but loses as a string. GitHub returns releases
    /// newest-first, so a purely positional pick would look right most of the
    /// time and go wrong exactly when a release is re-published.
    /// </summary>
    [Fact]
    public void Compares_numerically_not_alphabetically()
    {
        var json = Releases(
            Release("win-v0.3.9", setup: "Fabula-Setup-0.3.9.exe"),
            Release("win-v0.3.34", setup: "Fabula-Setup-0.3.34.exe"));

        Assert.Equal(
            new Version(0, 3, 34),
            ServerUpdateLogic.SelectNewestSetupRelease(json)!.Version);
    }

    [Fact]
    public void Skips_drafts_prereleases_and_foreign_tags()
    {
        var json = Releases(
            Release("win-v0.9.0", draft: true, setup: "Fabula-Setup-0.9.0.exe"),
            Release("win-v0.8.0", prerelease: true, setup: "Fabula-Setup-0.8.0.exe"),
            Release("apk-v0.2.99", setup: "Fabula-Setup-0.2.99.exe"),
            Release("win-v0.3.34", setup: "Fabula-Setup-0.3.34.exe"));

        Assert.Equal(
            new Version(0, 3, 34),
            ServerUpdateLogic.SelectNewestSetupRelease(json)!.Version);
    }

    /// <summary>A tag on its own is not something that can be installed.</summary>
    [Fact]
    public void Ignores_a_release_without_an_installer_asset()
    {
        var json = Releases(
            Release("win-v0.9.0", setup: null),
            Release("win-v0.3.34", setup: "Fabula-Setup-0.3.34.exe"));

        Assert.Equal(
            new Version(0, 3, 34),
            ServerUpdateLogic.SelectNewestSetupRelease(json)!.Version);
    }

    /// <summary>
    /// Still selected, but with no checksum URL -- the caller refuses to execute
    /// it, which is a clearer failure than silently falling back to an older
    /// release the admin didn't ask for.
    /// </summary>
    [Fact]
    public void Reports_a_missing_checksum_rather_than_hiding_the_release()
    {
        var picked = ServerUpdateLogic.SelectNewestSetupRelease(
            Releases(Release("win-v0.3.34", setup: "Fabula-Setup-0.3.34.exe", withHash: false)));

        Assert.NotNull(picked);
        Assert.Null(picked!.Sha256AssetUrl);
    }

    [Fact]
    public void Returns_null_when_there_is_no_installer_release_at_all()
    {
        Assert.Null(ServerUpdateLogic.SelectNewestSetupRelease("[]"));
        Assert.Null(ServerUpdateLogic.SelectNewestSetupRelease(
            Releases(Release("apk-v0.2.34", setup: null))));
    }

    // --- checksum -----------------------------------------------------------

    private const string Hash = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";

    /// <summary>
    /// CI writes the sha256sum form deliberately, so both `sha256sum -c` and
    /// Get-FileHash can read it. Two spaces, and the filename follows.
    /// </summary>
    [Fact]
    public void Reads_the_hash_from_the_sha256sum_form()
    {
        Assert.Equal(Hash, ServerUpdateLogic.ExpectedHash($"{Hash}  Fabula-Setup-0.3.34.exe"));
        Assert.Equal(Hash, ServerUpdateLogic.ExpectedHash($"{Hash} *Fabula-Setup-0.3.34.exe\n"));
        Assert.Equal(Hash, ServerUpdateLogic.ExpectedHash(Hash));
        Assert.Equal(Hash, ServerUpdateLogic.ExpectedHash($"  {Hash}  file\r\n"));
    }

    [Fact]
    public void Normalises_the_hash_to_lower_case()
    {
        Assert.Equal(Hash, ServerUpdateLogic.ExpectedHash(Hash.ToUpperInvariant() + "  file"));
    }

    /// <summary>
    /// Anything unreadable has to come back null so the caller refuses to run
    /// the installer. Returning a wrong-but-plausible value would be worse than
    /// returning nothing.
    /// </summary>
    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("not a hash at all")]
    [InlineData("abc123  file")]
    [InlineData("zzzzd081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08  file")]
    public void Refuses_an_unreadable_checksum(string? content) =>
        Assert.Null(ServerUpdateLogic.ExpectedHash(content));
}
