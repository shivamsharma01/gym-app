using System.Reflection;
using System.Text.Json;
using Gym.Gateway.Adapters;
using Gym.Gateway.Execution;
using Microsoft.Extensions.Logging.Abstractions;
using Xunit;

namespace Gym.Gateway.Tests;

/// <summary>
/// A flagged reader has one member writer. The command dispatcher does not call the adapter or
/// timestamp-wins. An unflagged reader still does. A short scan does not fall through to the old
/// deletion detector.
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
        var wins = new TimestampWins { FailMemberWrites = true };
        var dispatcher = new CommandDispatcher(
            new Dictionary<string, IDeviceAdapter> { ["reader"] = adapter },
            NullLogger<CommandDispatcher>.Instance,
            memberSync: wins);
        dispatcher.MemberWritesFollowDesiredState(["reader"]);

        var created = await dispatcher.DispatchAsync(Command("CREATE_USER", new { deviceUserId = "1", name = "Asha" }));
        var reconciled = await dispatcher.DispatchAsync(Command("RECONCILE_DEVICE", new { }));

        Assert.False(created.Ok);
        Assert.False(reconciled.Ok);
        Assert.Contains("desired revisions write this reader", JsonSerializer.Serialize(created.Payload));
        Assert.False(wins.Called);
        Assert.DoesNotContain("CreateUser", spy.Calls);
        Assert.DoesNotContain("Reconcile", spy.Calls);
        Assert.DoesNotContain("ListUsers", spy.Calls);

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
        Assert.False(wins.Called);
    }

    [Fact]
    public async Task Unflagged_reader_still_uses_the_dispatcher()
    {
        var (adapter, spy) = Spy("reader");
        var wins = new TimestampWins();
        var dispatcher = new CommandDispatcher(
            new Dictionary<string, IDeviceAdapter> { ["reader"] = adapter },
            NullLogger<CommandDispatcher>.Instance,
            memberSync: wins);

        var created = await dispatcher.DispatchAsync(Command("CREATE_USER", new { deviceUserId = "1", name = "Asha" }));

        Assert.True(created.Ok);
        Assert.True(wins.Called);
        Assert.Contains("CreateUser", spy.Calls);
    }

    [Fact]
    public async Task A_short_scan_on_a_flagged_reader_does_not_run_the_old_deletion_detector()
    {
        var (adapter, spy) = Spy("reader");
        spy.Target.CreateUser(new DeviceUserMutation("1", "Asha", true, null, null, null));
        spy.Target.CreateUser(new DeviceUserMutation("2", "Other", true, null, null, null));
        spy.Calls.Clear();
        spy.ListUsersOverride = () => [new DeviceUserSnapshot("2", "Other", false)];

        var roster = new RosterStateStore(null);
        roster.RecordProfile("reader", new DeviceUserSnapshot("1", "Asha", false));
        roster.RecordProfile("reader", new DeviceUserSnapshot("2", "Other", false));
        var published = new List<string>();
        var watcher = new DeviceChangeWatcher(
            new Dictionary<string, IDeviceAdapter> { ["reader"] = adapter },
            roster,
            new DeviceLocks(),
            new QuietFaces(),
            (deviceId, _) =>
            {
                published.Add(deviceId);
                return Task.CompletedTask;
            },
            NullLogger.Instance);
        watcher.DesiredWorkersExecute(["reader"]);

        Assert.Equal(0, await watcher.ScanDeviceAsync("reader", null, faceSweep: false, CancellationToken.None));

        Assert.DoesNotContain("ListUsers", spy.Calls);
        Assert.DoesNotContain("DeleteUser", spy.Calls);
        Assert.Empty(published);
        Assert.NotNull(spy.Target.GetUser("1"));
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

    private sealed class TimestampWins : ILocalMemberSync
    {
        public bool Called { get; private set; }

        public bool FailMemberWrites { get; init; }

        public Task<DispatchOutcome?> TryApplyAsync(
            GatewayEnvelope command, byte[]? face, CancellationToken cancellationToken)
        {
            if (command.Type is "CREATE_USER" or "UPDATE_USER" or "UPDATE_ACCESS_POLICY" or "DISABLE_USER"
                or "ENABLE_USER" or "UPDATE_VALIDITY" or "REMOVE_USER" or "ENROLL_FACE" or "UPSERT_FACE"
                or "DELETE_FACE" or "REPORT_DEVICE_USER" or "REFRESH_DEVICE_USERS" or "RECONCILE_DEVICE")
            {
                Called = true;
                if (FailMemberWrites)
                {
                    throw new InvalidOperationException("timestamp-wins");
                }
            }

            return Task.FromResult<DispatchOutcome?>(null);
        }

        public bool LastUserListTrusted(string deviceId) => true;

        public byte[]? CachedFace(string sha256) => null;
    }

    private sealed class QuietFaces : IFaceTransfer
    {
        public Task<FaceDownload> DownloadFaceAsync(string memberId, int version, CancellationToken cancellationToken) =>
            Task.FromResult(new FaceDownload(false, null, "unused"));

        public Task<FaceUpload> UploadFaceAsync(byte[] jpegBytes, CancellationToken cancellationToken) =>
            Task.FromResult(new FaceUpload(null));
    }
}
