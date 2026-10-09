using System.Reflection;
using System.Text.Json;
using Gym.Gateway.Adapters;
using Gym.Gateway.Execution;
using Microsoft.Extensions.Logging.Abstractions;
using Xunit;

namespace Gym.Gateway.Tests;

/// <summary>
/// Every reader has one member writer: the desired-state worker. The command dispatcher does not
/// call the adapter for member writes.
/// </summary>
public class OneWriterTests : IDisposable
{
    private static readonly DateTimeOffset ValidFrom = new(2026, 10, 8, 0, 0, 0, TimeSpan.FromHours(5.5));
    private static readonly DateTimeOffset ValidTo = new(2026, 10, 8, 23, 59, 59, TimeSpan.FromHours(5.5));
    private static readonly byte[] Face = [1, 2, 3, 4, 5];
    private readonly string _directory = Path.Combine(Path.GetTempPath(), "gym-v16-" + Guid.NewGuid().ToString("N"));

    public OneWriterTests()
    {
        Directory.CreateDirectory(_directory);
    }

    public void Dispose()
    {
        try
        {
            Directory.Delete(_directory, recursive: true);
        }
        catch (IOException)
        {
            // the journal can still be closing
        }
    }

    [Fact]
    public async Task Flagged_reader_is_written_only_by_the_desired_worker()
    {
        var (adapter, spy) = Spy("reader");
        var dispatcher = new CommandDispatcher(
            new Dictionary<string, IDeviceAdapter> { ["reader"] = adapter },
            NullLogger<CommandDispatcher>.Instance);

        var created = await dispatcher.DispatchAsync(Command("CREATE_USER", new { deviceUserId = "1", name = "Asha" }));

        Assert.False(created.Ok);
        Assert.Contains("desired revisions write this reader", JsonSerializer.Serialize(created.Payload));
        Assert.DoesNotContain("CreateUser", spy.Calls);

        var door = await dispatcher.DispatchAsync(Command("OPEN_DOOR", new { }));
        Assert.True(door.Ok);
        Assert.Contains("OpenDoor", spy.Calls);
        Assert.DoesNotContain("CreateUser", spy.Calls);

        var journal = Path.Combine(_directory, "reader.sqlite");
        using var worker = new ReaderWorker("reader", new DeviceReaderAdapter(adapter), journal);
        var applied = worker.ApplyMember(new DesiredMember(
            1, "1", "Asha", null, 0, ValidFrom, ValidTo, "USER", 1, 1, Face));

        Assert.Equal(MemberApplyKind.Applied, applied.Kind);
        Assert.Contains("CreateUser", spy.Calls);
    }

    [Fact]
    public async Task A_reader_without_a_prior_mark_still_refuses_member_commands()
    {
        var (adapter, spy) = Spy("reader");
        var dispatcher = new CommandDispatcher(
            new Dictionary<string, IDeviceAdapter> { ["reader"] = adapter },
            NullLogger<CommandDispatcher>.Instance);

        var created = await dispatcher.DispatchAsync(Command("CREATE_USER", new { deviceUserId = "1", name = "Asha" }));

        Assert.False(created.Ok);
        Assert.DoesNotContain("CreateUser", spy.Calls);
    }

    private static (IDeviceAdapter Adapter, CallSpy Spy) Spy(string deviceId)
    {
        var device = new MockDeviceAdapter();
        device.Connect(new DeviceConnectionConfig(deviceId, "10.0.0.8", 37777, "admin", "admin"));
        var adapter = DispatchProxy.Create<IDeviceAdapter, CallSpy>();
        var spy = (CallSpy)(object)adapter;
        spy.Target = device;
        return (adapter, spy);
    }

    private static GatewayEnvelope Command(string type, object payload) =>
        new()
        {
            Type = type,
            DeviceId = "reader",
            CorrelationId = Guid.NewGuid().ToString(),
            Payload = JsonSerializer.SerializeToElement(payload)
        };

    public class CallSpy : DispatchProxy
    {
        public List<string> Calls { get; } = [];

        public MockDeviceAdapter Target { get; set; } = new();

        public Func<IReadOnlyList<DeviceUserSnapshot>>? ListUsersOverride { get; set; }

        protected override object? Invoke(MethodInfo? targetMethod, object?[]? args)
        {
            Calls.Add(targetMethod!.Name);
            if (targetMethod.Name == "ListUsers" && ListUsersOverride != null)
            {
                return ListUsersOverride();
            }

            return targetMethod.Invoke(Target, args);
        }
    }

}
