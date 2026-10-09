using Gym.Gateway.Adapters;
using Microsoft.Extensions.Logging.Abstractions;
using Xunit;

namespace Gym.Gateway.Tests;

public class FaceSyncTests
{
    [Fact]
    public void Roster_checksum_ignores_list_order_and_sees_every_field()
    {
        var a = new DeviceUserSnapshot("1", "Asha", false, new DateTimeOffset(2026, 1, 1, 0, 0, 0, TimeSpan.Zero),
            new DateTimeOffset(2026, 12, 31, 23, 59, 59, TimeSpan.Zero), Authority: "USER");
        var b = new DeviceUserSnapshot("2", "Ravi", true);
        var digest = RosterDigest.Compute([a, b]);

        Assert.Equal(digest, RosterDigest.Compute([b, a]));
        Assert.NotEqual(digest, RosterDigest.Compute([a with { Name = "Asha K" }, b]));
        Assert.NotEqual(digest, RosterDigest.Compute([a with { Frozen = true }, b]));
        Assert.NotEqual(digest, RosterDigest.Compute([a with { ValidTo = a.ValidTo!.Value.AddDays(1) }, b]));
        Assert.NotEqual(digest, RosterDigest.Compute([a with { Authority = "ADMIN" }, b]));
        Assert.NotEqual(digest, RosterDigest.Compute([a]));
    }

    [Fact]
    public void Repeated_connection_errors_mark_the_reader_degraded()
    {
        var (mock, _) = Device();
        mock.SimulateLocalUserChange("8601", "One", [0xFF, 0xD8, 0xFF], emitEvent: false);
        var reader = new TimedDeviceAdapter(mock, NullLogger.Instance);
        mock.FaceReadFault = _ => "Wait time out";

        for (var i = 0; i < 3; i++)
        {
            reader.GetFace("8601");
        }

        Assert.Equal("ONLINE", reader.GetHealth().ConnectionState);
        reader.GetFace("8601");
        Assert.Equal(TimedDeviceAdapter.DegradedState, reader.GetHealth().ConnectionState);
        Assert.True(TimedDeviceAdapter.IsConnectionError("encrypt data fail"));
        Assert.False(TimedDeviceAdapter.IsConnectionError("INVALID_USER: user does not exist on device"));
    }

    [Fact]
    public async Task Reader_lock_reports_what_the_reader_is_busy_with()
    {
        var locks = new DeviceLocks();
        Assert.Null(locks.BusyWith("dev-1"));

        using (await locks.AcquireAsync("dev-1", "scheduled user check"))
        {
            Assert.StartsWith("scheduled user check", locks.BusyWith("dev-1"));
        }

        Assert.Null(locks.BusyWith("dev-1"));
    }

    private static (MockDeviceAdapter Adapter, Dictionary<string, IDeviceAdapter> Adapters) Device()
    {
        var adapter = new MockDeviceAdapter();
        adapter.Connect(new DeviceConnectionConfig("dev-1", "127.0.0.1", 37777, "admin", "x"));
        return (adapter, new Dictionary<string, IDeviceAdapter> { ["dev-1"] = adapter });
    }
}
