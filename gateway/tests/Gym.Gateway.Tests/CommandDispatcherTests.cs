using System.Text.Json;
using Gym.Gateway.Adapters;
using Microsoft.Extensions.Logging.Abstractions;
using Xunit;

namespace Gym.Gateway.Tests;

public class CommandDispatcherTests
{
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
    public async Task A_requested_scan_runs_outside_the_reader_lock_and_reports_its_result()
    {
        var adapter = new MockDeviceAdapter();
        adapter.Connect(new DeviceConnectionConfig("dev-1", "127.0.0.1", 37777, "admin", "x"));
        var locks = new DeviceLocks();
        var lockedDuringScan = new List<string?>();
        var results = new Queue<bool?>([true, false, null]);
        var dispatcher = new CommandDispatcher(
            new Dictionary<string, IDeviceAdapter> { ["dev-1"] = adapter },
            NullLogger<CommandDispatcher>.Instance,
            locks,
            (deviceId, _) =>
            {
                lockedDuringScan.Add(locks.BusyWith(deviceId));
                return Task.FromResult(results.Dequeue());
            });

        var scanned = await dispatcher.DispatchAsync(Command("SCAN_READER", "dev-1", new { }));
        var shortList = await dispatcher.DispatchAsync(Command("SCAN_READER", "dev-1", new { }));
        var noWorker = await dispatcher.DispatchAsync(Command("SCAN_READER", "dev-1", new { }));

        Assert.True(scanned.Ok);
        Assert.Equal(ProtocolTypes.SyncResult, scanned.ResultType);
        Assert.False(shortList.Ok);
        Assert.Contains("did not match its announced total", JsonSerializer.Serialize(shortList.Payload));
        Assert.False(noWorker.Ok);
        Assert.Contains("no worker", JsonSerializer.Serialize(noWorker.Payload));
        Assert.Equal(new string?[] { null, null, null }, lockedDuringScan);
    }

    [Fact]
    public async Task A_requested_scan_fails_on_a_gateway_that_cannot_scan()
    {
        var adapter = new MockDeviceAdapter();
        adapter.Connect(new DeviceConnectionConfig("dev-1", "127.0.0.1", 37777, "admin", "x"));
        var dispatcher = new CommandDispatcher(
            new Dictionary<string, IDeviceAdapter> { ["dev-1"] = adapter },
            NullLogger<CommandDispatcher>.Instance);

        var result = await dispatcher.DispatchAsync(Command("SCAN_READER", "dev-1", new { }));

        Assert.False(result.Ok);
        Assert.Contains("cannot scan readers", JsonSerializer.Serialize(result.Payload));
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
