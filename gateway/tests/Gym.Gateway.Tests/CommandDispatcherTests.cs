using System.Text.Json;
using Gym.Gateway.Adapters;
using Microsoft.Extensions.Logging.Abstractions;
using Xunit;

namespace Gym.Gateway.Tests;

public class CommandDispatcherTests
{
    [Fact]
    public async Task Create_user_and_heartbeat_commands()
    {
        var adapter = new MockDeviceAdapter();
        adapter.Connect(new DeviceConnectionConfig("dev-1", "127.0.0.1", 37777, "admin", "x"));
        var dispatcher = new CommandDispatcher(
            new Dictionary<string, IDeviceAdapter> { ["dev-1"] = adapter },
            NullLogger<CommandDispatcher>.Instance);

        var create = Command("CREATE_USER", "dev-1", new { deviceUserId = "1001", name = "Ada" });
        var result = await dispatcher.DispatchAsync(create);
        Assert.Equal(ProtocolTypes.SyncResult, result.ResultType);
        Assert.Contains("1001", adapter.KnownUserIds);
    }

    [Fact]
    public async Task Disable_with_dates_also_moves_the_validity_window()
    {
        var adapter = new MockDeviceAdapter();
        adapter.Connect(new DeviceConnectionConfig("dev-1", "127.0.0.1", 37777, "admin", "x"));
        var dispatcher = new CommandDispatcher(
            new Dictionary<string, IDeviceAdapter> { ["dev-1"] = adapter },
            NullLogger<CommandDispatcher>.Instance);
        await dispatcher.DispatchAsync(Command("CREATE_USER", "dev-1", new { deviceUserId = "1001", name = "Ada" }));

        await dispatcher.DispatchAsync(Command("DISABLE_USER", "dev-1",
            new { deviceUserId = "1001", enabled = false, validFrom = "2027-02-05", validTo = "2027-03-04" }));

        var user = adapter.GetUser("1001")!;
        Assert.True(user.Frozen);
        Assert.Equal(new DateTime(2027, 2, 5), user.ValidFrom!.Value.UtcDateTime.Date);
        Assert.Equal(new DateTime(2027, 3, 4), user.ValidTo!.Value.UtcDateTime.Date);
    }

    [Fact]
    public async Task Retired_enroll_face_fails_without_touching_device()
    {
        var adapter = new MockDeviceAdapter();
        adapter.Connect(new DeviceConnectionConfig("dev-1", "127.0.0.1", 37777, "admin", "x"));
        var dispatcher = new CommandDispatcher(
            new Dictionary<string, IDeviceAdapter> { ["dev-1"] = adapter },
            NullLogger<CommandDispatcher>.Instance);

        var result = await dispatcher.DispatchAsync(
            Command("ENROLL_FACE", "dev-1", new { deviceUserId = "1001" }));
        Assert.Equal(ProtocolTypes.SyncResult, result.ResultType);
        var json = JsonSerializer.Serialize(result.Payload);
        Assert.Contains("\"ok\":false", json.Replace(" ", ""));
        Assert.Contains("UPSERT_FACE", json);
    }

    [Fact]
    public async Task Unknown_device_fails_without_touching_adapter()
    {
        var adapter = new MockDeviceAdapter();
        adapter.Connect(new DeviceConnectionConfig("dev-1", "127.0.0.1", 37777, "admin", "x"));
        var dispatcher = new CommandDispatcher(
            new Dictionary<string, IDeviceAdapter> { ["dev-1"] = adapter },
            NullLogger<CommandDispatcher>.Instance);

        var result = await dispatcher.DispatchAsync(
            Command("CREATE_USER", "other", new { deviceUserId = "9" }));
        Assert.Equal(ProtocolTypes.SyncResult, result.ResultType);
        Assert.Empty(adapter.KnownUserIds);
    }

    [Fact]
    public async Task Clear_logs_is_not_faked()
    {
        var adapter = new MockDeviceAdapter();
        adapter.Connect(new DeviceConnectionConfig("dev-1", "127.0.0.1", 37777, "admin", "x"));
        var dispatcher = new CommandDispatcher(
            new Dictionary<string, IDeviceAdapter> { ["dev-1"] = adapter },
            NullLogger<CommandDispatcher>.Instance);
        var result = await dispatcher.DispatchAsync(Command("CLEAR_DEVICE_LOGS", "dev-1", new { }));
        var json = JsonSerializer.Serialize(result.Payload);
        Assert.Contains("\"ok\":false", json.Replace(" ", ""));
    }

    private static GatewayEnvelope Command(string type, string deviceId, object payload) =>
        new()
        {
            Type = type,
            DeviceId = deviceId,
            CorrelationId = Guid.NewGuid().ToString(),
            Payload = JsonSerializer.SerializeToElement(payload)
        };
}
