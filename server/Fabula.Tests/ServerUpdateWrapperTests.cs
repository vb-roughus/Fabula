using Fabula.Api.Infrastructure;
using Xunit;

namespace Fabula.Tests;

/// <summary>
/// The script that installs a server update after the service hands over.
///
/// Its order is the whole point. A silent install that finds a file still in
/// use aborts with exit code 5 instead of asking, so the service has to be
/// down -- not merely asked to stop -- before the installer starts; and the
/// service has to be started again whatever the installer did.
/// </summary>
public class ServerUpdateWrapperTests
{
    private static string Script() => ServerUpdateService.WrapperScript(
        setupPath: @"C:\ProgramData\Fabula\server-updates\Fabula-Setup-0.3.17.exe",
        logPath: @"C:\ProgramData\Fabula\logs\server-update.log",
        resultFile: @"C:\ProgramData\Fabula\server-updates\server-update-result.json",
        systemDirectory: @"C:\Windows\System32");

    private static int At(string script, string text)
    {
        var index = script.IndexOf(text, StringComparison.Ordinal);
        Assert.True(index >= 0, $"\"{text}\" missing from the script");
        return index;
    }

    [Fact]
    public void Stops_the_service_before_installing()
    {
        var script = Script();

        Assert.True(At(script, "stop Fabula") < At(script, "/VERYSILENT"));
    }

    /// <summary>`sc stop` only asks and returns at once; `net stop` waits for Stopped.</summary>
    [Fact]
    public void Stops_with_net_stop_which_waits()
    {
        var script = Script();

        Assert.Contains("net.exe\" stop Fabula", script);
        Assert.DoesNotContain("sc.exe\" stop", script);
    }

    [Fact]
    public void Records_the_exit_code_before_starting_the_service_again()
    {
        var script = Script();

        Assert.True(At(script, "/VERYSILENT") < At(script, "exitCode"));
        Assert.True(At(script, "exitCode") < At(script, "start Fabula"));
    }

    [Fact]
    public void Installs_silently_and_keeps_a_log()
    {
        var script = Script();

        Assert.Contains("/SUPPRESSMSGBOXES", script);
        Assert.Contains(@"/LOG=""C:\ProgramData\Fabula\logs\server-update.log""", script);
    }
}
