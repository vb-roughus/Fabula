using Fabula.Api.Infrastructure;
using Xunit;

namespace Fabula.Tests;

/// <summary>
/// How an interrupted update attempt is judged after the fact.
///
/// This is the part that cannot be observed while it happens: the process that
/// starts an update is stopped by the installer it launched, so the only account
/// left is a file on disk plus whichever version came back up. Every branch here
/// is a story the admin gets told, and getting one wrong means either claiming
/// success while the old build is running or reporting a failure that never was.
/// </summary>
public class ServerUpdateStateTests
{
    private static readonly DateTime Now = new(2026, 8, 17, 12, 0, 0, DateTimeKind.Utc);
    private static readonly Version Old = new(0, 3, 34);
    private static readonly Version New = new(0, 3, 35);

    private static ServerUpdateStatus Installing(DateTime? handoff = null) =>
        new(ServerUpdateState.Installing,
            FromVersion: Old.ToString(),
            ToVersion: New.ToString(),
            StartedAtUtc: Now.AddMinutes(-2),
            HandoffAtUtc: handoff ?? Now.AddSeconds(-30));

    [Fact]
    public void Nothing_recorded_means_idle()
    {
        var result = ServerUpdateLogic.Evaluate(null, Old, Now, null);
        Assert.Equal(ServerUpdateState.Idle, result.State);
    }

    /// <summary>
    /// The download happened in memory in a process that is now gone. It cannot
    /// be resumed and pretending it is still running would hang the UI forever.
    /// </summary>
    [Theory]
    [InlineData(ServerUpdateState.Downloading)]
    [InlineData(ServerUpdateState.Verifying)]
    public void A_restart_during_the_download_counts_as_aborted(ServerUpdateState state)
    {
        var result = ServerUpdateLogic.Evaluate(
            new ServerUpdateStatus(state, ToVersion: New.ToString()), Old, Now, null);

        Assert.Equal(ServerUpdateState.Failed, result.State);
        Assert.Contains("Abgebrochen", result.Message!);
    }

    /// <summary>The happy path: we came back as the version we were aiming for.</summary>
    [Fact]
    public void Running_the_target_version_is_success()
    {
        var result = ServerUpdateLogic.Evaluate(Installing(), New, Now, null);

        Assert.Equal(ServerUpdateState.Succeeded, result.State);
        Assert.Contains("0.3.35", result.Message!);
    }

    /// <summary>
    /// Someone ran a newer installer by hand while this was pending. Still not a
    /// failure -- the point was to stop being out of date.
    /// </summary>
    [Fact]
    public void Running_something_newer_than_the_target_is_also_success()
    {
        var result = ServerUpdateLogic.Evaluate(Installing(), new Version(0, 4, 0), Now, null);
        Assert.Equal(ServerUpdateState.Succeeded, result.State);
    }

    /// <summary>
    /// The wrapper finished and reported a failure. Its unconditional `sc start`
    /// is why the server is answering at all -- on the old version.
    /// </summary>
    [Fact]
    public void A_failing_installer_is_reported_with_its_exit_code()
    {
        var result = ServerUpdateLogic.Evaluate(Installing(), Old, Now, 5);

        Assert.Equal(ServerUpdateState.Failed, result.State);
        Assert.Contains("5", result.Message!);
        Assert.Contains("0.3.34", result.Message!);
    }

    /// <summary>
    /// Exit code zero but the old version is running: the installer thinks it
    /// worked and the evidence says otherwise. Reporting success off the exit
    /// code alone would be the easy mistake here -- the running version is the
    /// only claim that actually matters.
    /// </summary>
    [Fact]
    public void A_silent_no_op_installer_is_not_success()
    {
        var result = ServerUpdateLogic.Evaluate(Installing(), Old, Now, 0);

        Assert.Equal(ServerUpdateState.Failed, result.State);
        Assert.Contains("meldete Erfolg", result.Message!);
    }

    /// <summary>
    /// No result file yet and the handoff was moments ago: the installer is
    /// presumably still working. Calling it failed here would flash an error
    /// during every normal update.
    /// </summary>
    [Fact]
    public void A_fresh_handoff_without_a_result_is_still_running()
    {
        var result = ServerUpdateLogic.Evaluate(Installing(Now.AddSeconds(-20)), Old, Now, null);
        Assert.Equal(ServerUpdateState.Installing, result.State);
    }

    /// <summary>
    /// A wrapper that died without writing its result would otherwise leave the
    /// UI on "installing" indefinitely, which reads as a hang rather than a
    /// failure.
    /// </summary>
    [Fact]
    public void An_installer_that_never_finished_times_out()
    {
        var handoff = Now - ServerUpdateLogic.InstallTimeout - TimeSpan.FromMinutes(1);

        var result = ServerUpdateLogic.Evaluate(Installing(handoff), Old, Now, null);

        Assert.Equal(ServerUpdateState.Failed, result.State);
        Assert.Contains("nicht abgeschlossen", result.Message!);
    }

    [Fact]
    public void The_timeout_is_not_reached_a_moment_early()
    {
        var handoff = Now - ServerUpdateLogic.InstallTimeout + TimeSpan.FromSeconds(1);

        var result = ServerUpdateLogic.Evaluate(Installing(handoff), Old, Now, null);

        Assert.Equal(ServerUpdateState.Installing, result.State);
    }

    /// <summary>
    /// An unknown running version must not be read as "matches the target".
    /// It is the one case where we genuinely cannot tell, and the timeout is
    /// what resolves it.
    /// </summary>
    [Fact]
    public void An_unknown_running_version_is_not_treated_as_success()
    {
        var result = ServerUpdateLogic.Evaluate(Installing(), null, Now, null);
        Assert.Equal(ServerUpdateState.Installing, result.State);

        var timedOut = ServerUpdateLogic.Evaluate(
            Installing(Now - ServerUpdateLogic.InstallTimeout - TimeSpan.FromMinutes(1)), null, Now, null);
        Assert.Equal(ServerUpdateState.Failed, timedOut.State);
        Assert.Contains("unbekannt", timedOut.Message!);
    }

    /// <summary>A verdict already reached stays put; re-reading must not churn it.</summary>
    [Theory]
    [InlineData(ServerUpdateState.Idle)]
    [InlineData(ServerUpdateState.Succeeded)]
    [InlineData(ServerUpdateState.Failed)]
    public void Settled_states_are_left_alone(ServerUpdateState state)
    {
        var persisted = new ServerUpdateStatus(state, Message: "unverändert");

        var result = ServerUpdateLogic.Evaluate(persisted, Old, Now, 0);

        Assert.Equal(state, result.State);
        Assert.Equal("unverändert", result.Message!);
    }
}
