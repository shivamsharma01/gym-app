using Gym.Gateway.Adapters;
using Microsoft.Extensions.Logging.Abstractions;
using Xunit;

namespace Gym.Gateway.Tests;

public class FaceSyncTests
{
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
