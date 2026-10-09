using System.Text.Json;
using Gym.Gateway.Adapters;
using Microsoft.Extensions.Logging.Abstractions;
using Xunit;

namespace Gym.Gateway.Tests;

public class CommandDispatcherTests
{
    [Fact]
    public async Task A_member_command_does_not_write_the_reader()
    {
        var adapter = new MockDeviceAdapter();
        adapter.Connect(new DeviceConnectionConfig("dev-1", "127.0.0.1", 37777, "admin", "x"));
        var dispatcher = new CommandDispatcher(
            new Dictionary<string, IDeviceAdapter> { ["dev-1"] = adapter },
            NullLogger<CommandDispatcher>.Instance);

        var result = await dispatcher.DispatchAsync(
            Command("CREATE_USER", "dev-1", new { deviceUserId = "1001", name = "Ada" }));
        var disable = await dispatcher.DispatchAsync(Command("DISABLE_USER", "dev-1",
            new { deviceUserId = "1001", enabled = false, validFrom = "2027-02-05", validTo = "2027-03-04" }));

        Assert.False(result.Ok);
        Assert.False(disable.Ok);
        Assert.Contains("desired revisions write this reader", JsonSerializer.Serialize(result.Payload));
        Assert.Empty(adapter.KnownUserIds);
    }

    [Fact]
    public async Task Attendance_reconcile_reads_the_reader_and_does_not_write_a_user()
    {
        var adapter = new MockDeviceAdapter();
        adapter.Connect(new DeviceConnectionConfig("dev-1", "127.0.0.1", 37777, "admin", "x"));
        var dispatcher = new CommandDispatcher(
            new Dictionary<string, IDeviceAdapter> { ["dev-1"] = adapter },
            NullLogger<CommandDispatcher>.Instance);

        var result = await dispatcher.DispatchAsync(Command("RECONCILE_DEVICE", "dev-1", new { }));

        Assert.True(result.Ok);
        Assert.Empty(adapter.KnownUserIds);
    }

    [Fact]
    public async Task A_door_command_delivered_twice_is_applied_once_and_answered_twice()
    {
        var adapter = new MockDeviceAdapter();
        adapter.Connect(new DeviceConnectionConfig("dev-1", "127.0.0.1", 37777, "admin", "x"));
        var dispatcher = new CommandDispatcher(
            new Dictionary<string, IDeviceAdapter> { ["dev-1"] = adapter },
            NullLogger<CommandDispatcher>.Instance);
        var open = Command("OPEN_DOOR", "dev-1", new { });

        var both = await Task.WhenAll(dispatcher.DispatchAsync(open), dispatcher.DispatchAsync(open));
        var late = await dispatcher.DispatchAsync(open);

        Assert.All(both.Append(late), r => Assert.True(r.Ok));
    }

    [Fact]
    public async Task A_failed_door_command_runs_again_when_the_server_retries_it()
    {
        var adapter = new MockDeviceAdapter();
        adapter.Connect(new DeviceConnectionConfig("dev-1", "127.0.0.1", 37777, "admin", "x"));
        var dispatcher = new CommandDispatcher(
            new Dictionary<string, IDeviceAdapter> { ["dev-1"] = adapter },
            NullLogger<CommandDispatcher>.Instance);
        var open = Command("OPEN_DOOR", "dev-1", new { });
        adapter.Disconnect();

        var failed = await dispatcher.DispatchAsync(open);
        adapter.Connect(new DeviceConnectionConfig("dev-1", "127.0.0.1", 37777, "admin", "x"));
        var retried = await dispatcher.DispatchAsync(open);

        Assert.False(failed.Ok);
        Assert.True(retried.Ok);
    }

    [Fact]
    public async Task A_member_face_command_does_not_write_the_reader()
    {
        var adapter = new MockDeviceAdapter();
        adapter.Connect(new DeviceConnectionConfig("dev-1", "127.0.0.1", 37777, "admin", "x"));
        var dispatcher = new CommandDispatcher(
            new Dictionary<string, IDeviceAdapter> { ["dev-1"] = adapter },
            NullLogger<CommandDispatcher>.Instance);

        var result = await dispatcher.DispatchAsync(
            Command("ENROLL_FACE", "dev-1", new { deviceUserId = "1001" }));
        Assert.False(result.Ok);
        Assert.Contains("desired revisions write this reader", JsonSerializer.Serialize(result.Payload));
        Assert.Empty(adapter.KnownUserIds);
    }

    [Fact]
    public async Task Unknown_device_fails_without_touching_adapter()
    {
        var adapter = new MockDeviceAdapter();
        adapter.Connect(new DeviceConnectionConfig("dev-1", "127.0.0.1", 37777, "admin", "x"));
        var dispatcher = new CommandDispatcher(
            new Dictionary<string, IDeviceAdapter> { ["dev-1"] = adapter },
            NullLogger<CommandDispatcher>.Instance);

        var result = await dispatcher.DispatchAsync(Command("OPEN_DOOR", "other", new { }));
        Assert.False(result.Ok);
        Assert.Contains("Unknown or unconfigured deviceId", JsonSerializer.Serialize(result.Payload));
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
