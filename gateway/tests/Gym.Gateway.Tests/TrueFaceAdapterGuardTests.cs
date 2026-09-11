using Gym.Gateway.Adapters;
using Xunit;

namespace Gym.Gateway.Tests;

public class TrueFaceAdapterGuardTests
{
    [Fact]
    public void Connect_fails_clearly_on_non_windows()
    {
        if (OperatingSystem.IsWindows())
        {
            return;
        }

        using var adapter = new TrueFaceDeviceAdapter();
        var status = adapter.Connect(new DeviceConnectionConfig(
            "dev-1", "192.0.2.10", 37777, "admin", "secret"));
        Assert.False(status.Ok);
        Assert.Equal("OFFLINE", status.ConnectionState);
        Assert.Contains("Windows", status.Error);
        Assert.DoesNotContain("secret", status.Error);
    }
}
