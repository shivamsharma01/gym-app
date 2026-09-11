using System.Text.Json;
using Xunit;

namespace Gym.Gateway.Tests;

public class EnvelopeTests
{
    [Fact]
    public void Create_matches_backend_field_names()
    {
        var envelope = GatewayEnvelope.Create("gw-1", ProtocolTypes.DeviceEvent, new
        {
            deviceUserId = "1001",
            granted = true,
            recNo = 7
        }, "dev-1", "corr-1");

        var json = JsonSerializer.Serialize(envelope, JsonOptions.Outbound);
        using var doc = JsonDocument.Parse(json);
        var root = doc.RootElement;
        Assert.Equal("DEVICE_EVENT", root.GetProperty("type").GetString());
        Assert.Equal("gw-1", root.GetProperty("gatewayId").GetString());
        Assert.Equal("dev-1", root.GetProperty("deviceId").GetString());
        Assert.Equal("corr-1", root.GetProperty("correlationId").GetString());
        Assert.Equal("1001", root.GetProperty("payload").GetProperty("deviceUserId").GetString());
        Assert.True(root.GetProperty("payload").GetProperty("granted").GetBoolean());
        Assert.False(string.IsNullOrWhiteSpace(root.GetProperty("messageId").GetString()));
    }
}
