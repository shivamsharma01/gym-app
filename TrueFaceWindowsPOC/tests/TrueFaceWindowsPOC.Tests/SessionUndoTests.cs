using Gym.Gateway.Adapters;
using TrueFaceWindowsPOC;
using Xunit;

namespace TrueFaceWindowsPOC.Tests;

public class SessionUndoTests
{
    [Fact]
    public void RememberAndForgetTrackCreatedIds()
    {
        var undo = new SessionUndo();
        undo.RememberCreate("POC-1");
        undo.RememberCreate("POC-1");
        undo.RememberCreate("POC-2");
        Assert.Equal(["POC-1", "POC-2"], undo.CreatedUserIds);
        undo.Forget("POC-1");
        Assert.Equal(["POC-2"], undo.CreatedUserIds);
    }

    [Fact]
    public void SnapshotsEqualIgnoresOrderAndBlanks()
    {
        var before = SessionUndo.SnapshotIds(["a", "b", ""]);
        var after = SessionUndo.SnapshotIds(["b", "a"]);
        Assert.True(SessionUndo.SnapshotsEqual(before, after));
    }

    [Fact]
    public void SnapshotsEqualDetectsExtraPocUser()
    {
        var before = SessionUndo.SnapshotIds(["gym-1"]);
        var after = SessionUndo.SnapshotIds(["gym-1", "POC-20260912-AAAA"]);
        Assert.False(SessionUndo.SnapshotsEqual(before, after));
        Assert.Equal(["POC-20260912-AAAA"], SessionUndo.SymmetricDifference(before, after));
    }

    [Fact]
    public void NewPocUserIdUsesDateAndPrefix()
    {
        var id = SessionUndo.NewPocUserId(new DateTimeOffset(2026, 9, 12, 0, 0, 0, TimeSpan.Zero));
        Assert.StartsWith("POC-20260912-", id);
        Assert.Matches(@"^POC-\d{8}-[A-F0-9]{4}$", id);
    }
}

public class AccessEventWaiterTests
{
    [Fact]
    public async Task WaitForConsumesMatchingAccessEventsInOrder()
    {
        var waiter = new AccessEventWaiter();
        waiter.Inject(new NormalizedDeviceEvent(
            "ACCESS", "POC-1", DateTimeOffset.UtcNow, "FACE", true, 1, null, null));
        waiter.Inject(new NormalizedDeviceEvent(
            "ACCESS", "POC-1", DateTimeOffset.UtcNow, "FACE", false, 2, null, null));

        var grant = await waiter.WaitForAsync("POC-1", granted: true, TimeSpan.FromSeconds(1), CancellationToken.None);
        Assert.NotNull(grant);
        Assert.True(grant!.Granted);

        var deny = await waiter.WaitForAsync("POC-1", granted: false, TimeSpan.FromSeconds(1), CancellationToken.None);
        Assert.NotNull(deny);
        Assert.False(deny!.Granted);
    }

    [Fact]
    public async Task WaitForMatchesUserIdCaseInsensitive()
    {
        var waiter = new AccessEventWaiter();
        waiter.Inject(new NormalizedDeviceEvent(
            "ACCESS", "poc-1", DateTimeOffset.UtcNow, "FACE", true, 9, null, null));

        var grant = await waiter.WaitForAsync("POC-1", granted: true, TimeSpan.FromSeconds(1), CancellationToken.None);
        Assert.NotNull(grant);
        Assert.True(grant!.Granted);
    }
}
