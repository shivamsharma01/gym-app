using Microsoft.Extensions.Logging.Abstractions;
using Xunit;

namespace Gym.Gateway.Tests;

public class GatewayOptionsTests
{
    [Fact]
    public void Overlay_environment_fills_gateway_and_device()
    {
        var options = new GatewayOptions { BackendUrl = "http://localhost" };
        options.OverlayEnvironment(new Dictionary<string, string?>
        {
            ["GYM_GATEWAY_ID"] = "gw-1",
            ["GYM_GATEWAY_TOKEN"] = "tok",
            ["GYM_BACKEND"] = "http://127.0.0.1:8080/",
            ["GYM_ADAPTER"] = "TrueFace",
            ["GYM_DEVICE_ID"] = "dev-1",
            ["GYM_DEVICE_IP"] = "192.168.31.91",
            ["GYM_DEVICE_PORT"] = "37777",
            ["GYM_DEVICE_USERNAME"] = "admin"
        });

        Assert.Empty(options.Validate());
        Assert.Equal("gw-1", options.Id);
        Assert.Equal("http://127.0.0.1:8080", options.BackendUrl);
        Assert.Equal("TrueFace", options.Adapter);
        Assert.Single(options.Devices);
        Assert.Equal("192.168.31.91", options.Devices[0].Ip);
        Assert.Equal((ushort)37777, options.Devices[0].Port);
    }

    [Fact]
    public void Validate_requires_id_and_token()
    {
        var options = new GatewayOptions();
        Assert.NotEmpty(options.Validate());
    }

    [Fact]
    public void WebSocket_uri_uses_ws_and_redacts_nothing_in_path()
    {
        var options = new GatewayOptions
        {
            Id = "gw",
            Token = "secret-token",
            BackendUrl = "http://127.0.0.1:8080"
        };
        var outbox = new DurableOutboundStore(
            NullLogger<DurableOutboundStore>.Instance,
            Path.Combine(Path.GetTempPath(), "gym-outbox-" + Guid.NewGuid().ToString("N")));
        var link = new BackendLink(options, NullLogger<BackendLink>.Instance, outbox);
        Assert.Equal("ws", link.WebSocketUri.Scheme);
        Assert.Equal("/gateway", link.WebSocketUri.AbsolutePath);
        Assert.Contains("token=", link.WebSocketUri.Query);
        Assert.Equal("http://127.0.0.1:8080/", link.HttpBase.ToString());
    }
}
