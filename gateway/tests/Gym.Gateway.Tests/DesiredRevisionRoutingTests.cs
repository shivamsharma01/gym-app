using System.Text.Json;
using Gym.Gateway.Adapters;
using Gym.Gateway.Execution;
using Microsoft.Extensions.Logging.Abstractions;
using Xunit;

namespace Gym.Gateway.Tests;

public class DesiredRevisionRoutingTests
{
    [Fact]
    public async Task Desired_revision_is_not_handed_to_the_command_callback()
    {
        var commanded = false;
        long revision = 0;
        var json = """{"type":"DESIRED_REVISION","deviceId":"reader-public","revision":4}""";
        var handled = await InboundDispatch.RouteAsync(
            json,
            _ =>
            {
                commanded = true;
                return Task.CompletedTask;
            },
            (notice, _) =>
            {
                revision = notice.Revision;
                Assert.Equal("reader-public", notice.DeviceId);
                return Task.CompletedTask;
            },
            CancellationToken.None);

        Assert.True(handled);
        Assert.Equal(4, revision);
        Assert.False(commanded);
    }

    [Fact]
    public async Task Outbox_command_is_not_a_desired_revision()
    {
        var desired = false;
        var json = """{"type":"CREATE_USER","deviceId":"reader-public","payload":{"deviceUserId":"9"}}""";
        var handled = await InboundDispatch.RouteAsync(
            json,
            _ => Task.CompletedTask,
            (_, _) =>
            {
                desired = true;
                return Task.CompletedTask;
            },
            CancellationToken.None);

        Assert.False(handled);
        Assert.False(desired);
    }

    [Fact]
    public async Task Legacy_dispatcher_cannot_execute_a_desired_revision()
    {
        var reader = new MockDeviceAdapter();
        reader.Connect(new DeviceConnectionConfig("reader-1", "10.0.0.8", 37777, "admin", "admin"));
        var adapters = new Dictionary<string, IDeviceAdapter> { ["reader-1"] = reader };
        var dispatcher = new CommandDispatcher(adapters, NullLogger<CommandDispatcher>.Instance);
        using var document = JsonDocument.Parse("{}");
        var outcome = await dispatcher.DispatchAsync(new GatewayEnvelope
        {
            Type = InboundDispatch.DesiredRevisionType,
            DeviceId = "reader-1",
            Payload = document.RootElement.Clone()
        });

        Assert.False(outcome.Ok);
        Assert.Contains("unsupported command DESIRED_REVISION", JsonSerializer.Serialize(outcome.Payload));
        Assert.Empty(reader.ListUsers());
    }

    [Fact]
    public void Reader_local_time_round_trips_the_kolkata_offset()
    {
        const string from = "2026-10-08T00:00:00+05:30";
        const string to = "2026-10-08T23:59:59+05:30";
        Assert.Equal(from, ReaderLocalTime.Format(ReaderLocalTime.Parse(from)));
        Assert.Equal(to, ReaderLocalTime.Format(ReaderLocalTime.Parse(to)));
    }
}
