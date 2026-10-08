using Gym.Gateway.Adapters;
using Gym.Gateway.Execution;
using Microsoft.Extensions.Logging.Abstractions;
using NetSDKCS;
using Xunit;

namespace Gym.Gateway.Tests;

/// <summary>
/// The live adapter, not the fake reader. The reader clock and validity are India local time.
/// </summary>
public class LiveReaderAdapterTests : IDisposable
{
    private static readonly TimeSpan India = TimeSpan.FromHours(5.5);
    private static readonly DateTimeOffset From = new(2026, 10, 8, 0, 0, 0, India);
    private static readonly DateTimeOffset To = new(2026, 10, 8, 23, 59, 59, India);
    private static readonly byte[] Face = [1, 2, 3, 4];
    private readonly string _directory = Path.Combine(Path.GetTempPath(), "gym-live-" + Guid.NewGuid().ToString("N"));

    public LiveReaderAdapterTests()
    {
        Directory.CreateDirectory(_directory);
    }

    [Fact]
    public async Task Reader_clock_is_set_to_india_local_time()
    {
        var utc = new DateTimeOffset(2026, 10, 7, 7, 35, 25, TimeSpan.Zero);
        var local = ReaderLocalClock.Now(utc);
        var clock = TrueFaceDeviceAdapter.DeviceClock(utc);

        Assert.Equal(new DateTimeOffset(2026, 10, 7, 13, 5, 25, India), local);
        Assert.Equal(2026u, clock.dwYear);
        Assert.Equal(10u, clock.dwMonth);
        Assert.Equal(7u, clock.dwDay);
        Assert.Equal(13u, clock.dwHour);
        Assert.Equal(5u, clock.dwMinute);
        Assert.Equal(25u, clock.dwSecond);

        var adapter = new MockDeviceAdapter();
        adapter.Connect(new DeviceConnectionConfig("dev-1", "127.0.0.1", 37777, "admin", "x"));
        var dispatcher = new CommandDispatcher(
            new Dictionary<string, IDeviceAdapter> { ["dev-1"] = adapter },
            NullLogger<CommandDispatcher>.Instance);
        var synced = await dispatcher.DispatchAsync(new GatewayEnvelope
        {
            Type = "SYNC_DEVICE_TIME",
            DeviceId = "dev-1",
            CorrelationId = Guid.NewGuid().ToString(),
            Payload = System.Text.Json.JsonSerializer.SerializeToElement(new { })
        });
        Assert.True(synced.Ok);
        Assert.NotNull(adapter.LastSynchronized);
        Assert.Equal(India, adapter.LastSynchronized.Value.Offset);

        var options = new GatewayOptions { Id = "g", Token = "t", BackendUrl = "http://127.0.0.1:9" };
        var store = new DurableOutboundStore(NullLogger<DurableOutboundStore>.Instance, _directory);
        var link = new BackendLink(options, NullLogger<BackendLink>.Instance, store);
        var worker = new GatewayWorker(
            options,
            link,
            NullLogger<GatewayWorker>.Instance,
            NullLoggerFactory.Instance,
            readers: null,
            desired: null,
            journalDirectory: _directory);
        var startup = new MockDeviceAdapter();
        startup.Connect(new DeviceConnectionConfig("reader", "127.0.0.1", 37777, "admin", "x"));
        Assert.True(await worker.SyncReaderClockAsync("reader", startup, CancellationToken.None));
        Assert.NotNull(startup.LastSynchronized);
        Assert.Equal(India, startup.LastSynchronized.Value.Offset);
        Assert.Equal(ReaderLocalClock.Now(startup.LastSynchronized.Value), startup.LastSynchronized.Value);
    }

    [Fact]
    public void Normal_name_and_validity_round_trip_on_the_reader_clock()
    {
        var device = new StructReader();
        var writer = new DeviceReaderAdapter(device);
        Assert.True(writer.CreateUser(User("1", "Asha Shah", null)).Ok);

        var read = new DeviceReaderAdapter(device).GetUser("1");
        Assert.True(read.Ok);
        Assert.Equal("Asha Shah", read.User!.Name);
        Assert.Null(read.User.NameEx);
        Assert.Equal(From, read.User.ValidFrom);
        Assert.Equal(To, read.User.ValidTo);
        Assert.Equal(0, read.User.UserStatus);
        Assert.Equal("Customer", read.User.Authority);
    }

    [Fact]
    public void Long_name_round_trips_szName_and_szNameEx()
    {
        const string full = "Priya Nandini Kapoor the reader name";
        var shown = full[..31];
        var device = new StructReader();
        Assert.True(new DeviceReaderAdapter(device).CreateUser(User("1", shown, full)).Ok);

        var stored = device.Stored("1");
        Assert.Equal(shown, stored.szName.Trim());
        Assert.Equal(full, stored.szNameEx.Trim());
        Assert.True(stored.bUseNameEx);

        var read = new DeviceReaderAdapter(device).GetUser("1");
        Assert.Equal(shown, read.User!.Name);
        Assert.Equal(full, read.User.NameEx);
        Assert.Equal(From, read.User.ValidFrom);
        Assert.Equal(To, read.User.ValidTo);
    }

    [Fact]
    public void A_local_end_date_is_not_moved_by_utc_conversion()
    {
        var device = new StructReader();
        Assert.True(new DeviceReaderAdapter(device).ReplaceUser(User("1", "Asha Shah", null)).Ok == false);
        Assert.True(new DeviceReaderAdapter(device).CreateUser(User("1", "Asha Shah", null)).Ok);

        var stored = device.Stored("1");
        Assert.Equal(2026u, stored.stuValidBeginTime.dwYear);
        Assert.Equal(10u, stored.stuValidBeginTime.dwMonth);
        Assert.Equal(8u, stored.stuValidBeginTime.dwDay);
        Assert.Equal(0u, stored.stuValidBeginTime.dwHour);
        Assert.Equal(8u, stored.stuValidEndTime.dwDay);
        Assert.Equal(23u, stored.stuValidEndTime.dwHour);
        Assert.Equal(59u, stored.stuValidEndTime.dwMinute);
        Assert.Equal(59u, stored.stuValidEndTime.dwSecond);

        var read = TrueFaceDeviceAdapter.ToSnapshot(stored);
        Assert.Equal(From, read.ValidFrom);
        Assert.Equal(To, read.ValidTo);
    }

    [Fact]
    public void A_validity_read_back_that_differs_is_not_acknowledged()
    {
        var device = new StructReader { ReportedEnd = new DateTimeOffset(2026, 10, 7, 23, 59, 59, India) };
        var reader = new DeviceReaderAdapter(device);
        using var worker = new ReaderWorker("reader", reader, Path.Combine(_directory, "reader.sqlite"), TimeSpan.Zero);

        var result = worker.ApplyMember(new DesiredMember(
            1, "1", "Asha Shah", null, 0, From, To, "Customer", 1, 1, Face));

        Assert.Equal(MemberApplyKind.Failed, result.Kind);
        Assert.Equal(0, worker.AppliedRevision);
        Assert.Empty(worker.PendingAcks);
        Assert.Equal("read-back mismatch", worker.Retry!.LastError);
        Assert.Equal(8u, device.Stored("1").stuValidEndTime.dwDay);
    }

    [Fact]
    public void Empty_update_keeps_the_photo_and_a_second_insert_is_photo_exist()
    {
        var device = new StructReader();
        var writer = new DeviceReaderAdapter(device);
        var replacement = new byte[] { 9, 8, 7, 6 };
        Assert.True(writer.CreateUser(User("1", "Asha Shah", null)).Ok);
        Assert.True(writer.InsertFace("1", Face).Ok);

        Assert.True(writer.UpdateFace("1", []).Ok);
        Assert.True(writer.UpdateFace("1", null).Ok);
        Assert.Equal(Face, writer.GetFace("1").Bytes);

        Assert.True(writer.UpdateFace("1", replacement).Ok);
        Assert.Equal(replacement, writer.GetFace("1").Bytes);

        var second = writer.InsertFace("1", Face);
        Assert.False(second.Ok);
        Assert.Equal(FakeReader.FailPhotoExist, second.FailCode);
        Assert.Equal(replacement, writer.GetFace("1").Bytes);
    }

    public void Dispose()
    {
        if (Directory.Exists(_directory))
        {
            Directory.Delete(_directory, true);
        }
    }

    private static ReaderUser User(string id, string name, string? nameEx) =>
        new(id, name, nameEx, 0, From, To, "Customer", 1, 1);

    /// <summary>Stores the SDK record the live adapter writes, and reads it back through ToSnapshot.</summary>
    private sealed class StructReader : IDeviceAdapter
    {
        private readonly Dictionary<string, NET_ACCESS_USER_INFO> _users = new(StringComparer.Ordinal);
        private readonly Dictionary<string, byte[]> _faces = new(StringComparer.Ordinal);

        public DateTimeOffset? ReportedEnd { get; set; }

        public string DeviceId => "reader";

        public NET_ACCESS_USER_INFO Stored(string id) => _users[id];

        public DeviceConnectionStatus Connect(DeviceConnectionConfig config) => DeviceConnectionStatus.Online();

        public void Disconnect()
        {
        }

        public DeviceInfoSnapshot GetDeviceInfo() => new("LIVE", 0, 1, 0, 0, 0);

        public DeviceHealth GetHealth() => new("ONLINE", DateTimeOffset.UtcNow, null);

        public DeviceCommandResult CreateUser(DeviceUserMutation mutation)
        {
            if (_users.ContainsKey(mutation.DeviceUserId))
            {
                return DeviceCommandResult.Fail("exists");
            }

            _users[mutation.DeviceUserId] = TrueFaceDeviceAdapter.BuildUser(mutation, mutation.Enabled == false);
            return DeviceCommandResult.Success();
        }

        public DeviceCommandResult UpdateUser(DeviceUserMutation mutation)
        {
            if (!_users.TryGetValue(mutation.DeviceUserId, out var existing))
            {
                return DeviceCommandResult.Fail("missing");
            }

            _users[mutation.DeviceUserId] = TrueFaceDeviceAdapter.ApplyMutation(existing, mutation);
            return DeviceCommandResult.Success();
        }

        public DeviceCommandResult DisableUser(string deviceUserId) =>
            UpdateUser(new DeviceUserMutation(deviceUserId, Enabled: false));

        public DeviceCommandResult EnableUser(string deviceUserId) =>
            UpdateUser(new DeviceUserMutation(deviceUserId, Enabled: true));

        public DeviceCommandResult DeleteUser(string deviceUserId) => DeviceCommandResult.Fail("not used");

        public DeviceCommandResult UpdateValidity(DeviceUserMutation mutation) => UpdateUser(mutation);

        public IReadOnlyList<DeviceUserSnapshot> ListUsers() =>
            _users.Values.Select(TrueFaceDeviceAdapter.ToSnapshot).ToArray();

        public DeviceUserSnapshot? GetUser(string deviceUserId)
        {
            if (!_users.TryGetValue(deviceUserId, out var user))
            {
                return null;
            }

            if (ReportedEnd is DateTimeOffset end)
            {
                user.stuValidEndTime = NET_TIME.FromDateTime(ReaderLocalClock.Wall(end));
            }

            return TrueFaceDeviceAdapter.ToSnapshot(user);
        }

        public DeviceCommandResult UpsertFace(string deviceUserId, byte[] jpegBytes)
        {
            _faces[deviceUserId] = jpegBytes.ToArray();
            return DeviceCommandResult.Success();
        }

        public DeviceFaceRead GetFace(string deviceUserId)
        {
            if (!_users.ContainsKey(deviceUserId))
            {
                return DeviceFaceRead.Fail("missing user");
            }

            return _faces.TryGetValue(deviceUserId, out var photo)
                ? DeviceFaceRead.Found(photo.ToArray(), null)
                : DeviceFaceRead.None();
        }

        public DeviceCommandResult DeleteFace(string deviceUserId) => DeviceCommandResult.Fail("not used");

        public FaceProbeResult ProbeRemoteFaceInsert(string deviceUserId, byte[] jpegBytes) =>
            throw new NotSupportedException();

        public IReadOnlyList<DeviceAttendanceRecord> FetchAttendance(DateTimeOffset? fromUtc, DateTimeOffset? toUtc) => [];

        public void RegisterEventListener(IDeviceEventListener listener)
        {
        }

        public DeviceCommandResult OpenDoor() => DeviceCommandResult.Success();

        public DeviceCommandResult CloseDoor() => DeviceCommandResult.Success();

        public DeviceCommandResult SynchronizeTime(DateTimeOffset utcNow) => DeviceCommandResult.Success();

        public DeviceReconciliationResult Reconcile(DateTimeOffset? fromUtc = null, DateTimeOffset? toUtc = null) =>
            new(true, null, [], ListUsers());

        public void Dispose()
        {
        }
    }
}
