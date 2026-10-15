using Gym.Gateway.Adapters;
using Gym.Gateway.Execution;
using Microsoft.Data.Sqlite;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;
using Xunit;

namespace Gym.Gateway.Tests;

/// <summary>
/// Every read of a reader is numbered in its journal, and every upload, acknowledgement and
/// occupant report names the scan it came from, so the server can refuse an older one. A reader
/// that changes between a scan, a write and its read-back is reported, never overwritten. A reader
/// that was off or whose session broke is read again before anything is written to it.
/// </summary>
public class ScanOrderTests : IDisposable
{
    private readonly string _directory = Path.Combine(Path.GetTempPath(), "gym-scan-" + Guid.NewGuid().ToString("N"));
    private static readonly DateTimeOffset ValidFrom = new(2026, 10, 8, 0, 0, 0, TimeSpan.FromHours(5.5));
    private static readonly DateTimeOffset ValidTo = new(2026, 10, 8, 23, 59, 59, TimeSpan.FromHours(5.5));
    private static readonly byte[] Face = [1, 2, 3, 4, 5];
    private static readonly byte[] RaviFace = [9, 9, 9];
    private DateTimeOffset _now = new(2026, 10, 15, 6, 0, 0, TimeSpan.Zero);

    public ScanOrderTests() => Directory.CreateDirectory(_directory);

    public void Dispose()
    {
        SqliteConnection.ClearAllPools();
        if (Directory.Exists(_directory))
        {
            Directory.Delete(_directory, true);
        }
    }

    [Fact]
    public void Scan_numbers_survive_a_restart_and_a_new_journal_numbers_its_own_scans()
    {
        var journal = Path.Combine(_directory, "r.sqlite");
        ScanStamp first;
        using (var worker = Worker(new FakeReader(), journal))
        {
            Assert.True(worker.IsNewJournal);
            Assert.Equal(0, worker.CurrentScan.Seq);
            worker.BeginScan();
            first = worker.BeginScan();
            Assert.Equal(2, first.Seq);
        }

        using (var restarted = Worker(new FakeReader(), journal))
        {
            Assert.False(restarted.IsNewJournal);
            Assert.Equal(first, restarted.CurrentScan);
            Assert.Equal(new ScanStamp(first.Epoch, 3), restarted.BeginScan());
        }

        SqliteConnection.ClearAllPools();
        File.Delete(journal);
        using var replaced = Worker(new FakeReader(), journal);
        Assert.True(replaced.IsNewJournal);
        Assert.NotEqual(first.Epoch, replaced.CurrentScan.Epoch);
        Assert.Equal(1, replaced.BeginScan().Seq);
    }

    [Fact]
    public async Task Uploads_name_the_scan_that_read_them_and_an_acknowledgement_names_the_scan_after_its_read_back()
    {
        var reader = new FakeReader();
        Enrol(reader, "5", "Ravi", RaviFace);
        var server = new Server();
        var upload = new PendingEnrollmentTests.RecordingUpload();
        using var worker = Worker(reader, Path.Combine(_directory, "r.sqlite"));
        var path = Revisions(worker, reader, server, upload);

        await path.ReconnectAsync(CancellationToken.None);

        var scan = worker.CurrentScan;
        Assert.Equal(1, scan.Seq);
        Assert.NotEmpty(upload.Stamps);
        Assert.All(upload.Stamps, stamp => Assert.Equal(scan, stamp));

        server.Items = [Item(1, "8", "Asha")];
        await path.HandleAsync(new DesiredRevisionNotice("r", 1), CancellationToken.None);

        var ack = Assert.Single(server.Acks);
        Assert.Equal(1, ack.Revision);
        Assert.Equal(worker.CurrentScan, ack.ReadAfter);
        Assert.True(ack.ReadAfter!.Seq >= scan.Seq);

        await path.ObserveAsync(CancellationToken.None);

        Assert.True(upload.Stamps[^1].Seq > ack.ReadAfter.Seq);
        Assert.Equal(upload.Stamps[^1], worker.CurrentScan);
    }

    [Fact]
    public async Task A_scan_waits_for_a_running_write_and_its_read_back_so_its_number_is_after_them()
    {
        var fake = new FakeReader();
        using var writeEntered = new ManualResetEventSlim();
        using var release = new ManualResetEventSlim();
        var reader = new Hooked(fake)
        {
            BeforeInsertFace = () =>
            {
                writeEntered.Set();
                release.Wait(TimeSpan.FromSeconds(30));
            }
        };
        var server = new Server();
        var upload = new PendingEnrollmentTests.RecordingUpload();
        using var worker = Worker(reader, Path.Combine(_directory, "r.sqlite"));
        var path = Revisions(worker, reader, server, upload);
        await path.ReconnectAsync(CancellationToken.None);
        server.Items = [Item(1, "8", "Asha")];

        var pass = Task.Run(() => path.HandleAsync(new DesiredRevisionNotice("r", 1), CancellationToken.None));
        Assert.True(writeEntered.Wait(TimeSpan.FromSeconds(10)));
        var lists = reader.Lists;
        var scan = Task.Run(() => path.ObserveAsync(CancellationToken.None));
        await Task.Delay(300);

        Assert.Equal(lists, reader.Lists);
        Assert.False(scan.IsCompleted);

        release.Set();
        await pass;
        await scan;

        var ack = Assert.Single(server.Acks);
        Assert.Equal(1, ack.ReadAfter!.Seq);
        Assert.Equal(2, upload.Stamps[^1].Seq);
        var insert = reader.Log.LastIndexOf("InsertFace");
        var list = reader.Log.LastIndexOf("ListUsers");
        Assert.True(list > insert, "the scan read the reader after the write");
        Assert.Contains("GetUser", reader.Log.Skip(insert).Take(list - insert));
    }

    [Fact]
    public async Task Someone_enrolled_between_the_scan_and_the_write_is_reported_and_left_on_the_reader()
    {
        var fake = new FakeReader();
        var reader = new Hooked(fake);
        var server = new Server();
        using var worker = Worker(reader, Path.Combine(_directory, "r.sqlite"));
        var path = Revisions(worker, reader, server, new PendingEnrollmentTests.RecordingUpload());
        await path.ReconnectAsync(CancellationToken.None);
        server.Items = [Item(1, "8", "Asha")];
        reader.BeforeFirstGetUser = () => Enrol(fake, "8", "Ravi", RaviFace);

        await path.HandleAsync(new DesiredRevisionNotice("r", 1), CancellationToken.None);

        Assert.Equal(["8"], server.Occupied);
        Assert.Empty(server.Acks);
        Assert.Equal("Ravi", fake.GetUser("8").User!.Name);
        Assert.Equal(RaviFace, fake.GetFace("8").Bytes);
        Assert.Equal(["CreateUser 8", "InsertFace 8"], fake.Writes);
        Assert.Null(worker.Attempt(1));
    }

    [Fact]
    public async Task A_record_replaced_between_the_scan_and_the_write_is_reported_as_the_reader_shows_it_with_its_scan()
    {
        var fake = new FakeReader();
        Enrol(fake, "8", "Asha", Face);
        var reader = new Hooked(fake);
        var server = new Server();
        using var worker = Worker(reader, Path.Combine(_directory, "r.sqlite"));
        var path = Revisions(worker, reader, server, new PendingEnrollmentTests.RecordingUpload());
        await path.ReconnectAsync(CancellationToken.None);
        var before = worker.CurrentScan;
        server.Items = [Item(1, "8", "Asha Shah", expected: Verified("Asha", Face))];
        reader.BeforeFirstGetUser = () =>
        {
            Assert.True(fake.ReplaceUser(Person("8", "Ravi")).Ok);
            Assert.True(fake.UpdateFace("8", RaviFace).Ok);
        };

        await path.HandleAsync(new DesiredRevisionNotice("r", 1), CancellationToken.None);

        var report = Assert.Single(server.Reports);
        Assert.Equal("Ravi", report.User!.Name);
        Assert.Equal(FaceHash.Sha256Hex(RaviFace), report.FaceSha256);
        Assert.NotNull(report.ReadAfter);
        Assert.True(report.ReadAfter!.Seq >= before.Seq);
        Assert.Empty(server.Acks);
        Assert.Equal("Ravi", fake.GetUser("8").User!.Name);
        Assert.Equal(RaviFace, fake.GetFace("8").Bytes);
    }

    [Fact]
    public async Task A_record_changed_between_the_write_and_its_read_back_is_not_acknowledged_and_its_retry_does_not_overwrite_it()
    {
        var fake = new FakeReader();
        var reader = new Hooked(fake);
        var server = new Server();
        using var worker = Worker(reader, Path.Combine(_directory, "r.sqlite"));
        var path = Revisions(worker, reader, server, new PendingEnrollmentTests.RecordingUpload());
        await path.ReconnectAsync(CancellationToken.None);
        server.Items = [Item(1, "8", "Asha")];
        reader.AfterInsertFace = () => Assert.True(fake.ReplaceUser(Person("8", "Ravi")).Ok);

        await path.HandleAsync(new DesiredRevisionNotice("r", 1), CancellationToken.None);

        Assert.Empty(server.Acks);
        var attempt = worker.Attempt(1);
        Assert.NotNull(attempt);
        Assert.Equal(1, attempt!.AttemptCount);
        Assert.Contains("read-back mismatch", attempt.LastError, StringComparison.Ordinal);
        Assert.Equal("Ravi", fake.GetUser("8").User!.Name);

        reader.AfterInsertFace = null;
        var writes = fake.Writes.Count;
        _now += TimeSpan.FromMinutes(5);
        await path.RetryAsync(CancellationToken.None);

        Assert.Empty(server.Acks);
        Assert.Equal(["8"], server.Occupied);
        Assert.Equal(writes, fake.Writes.Count);
        Assert.Equal("Ravi", fake.GetUser("8").User!.Name);
    }

    [Fact]
    public void A_new_journal_drops_the_observations_queued_before_it_and_a_restart_keeps_them()
    {
        var store = Store();
        store.Persist(GatewayEnvelope.Create("gw-1", ProtocolTypes.DeviceUserChanged, new { deviceUserId = "7" }, "reader-1"));
        store.Persist(GatewayEnvelope.Create("gw-1", ProtocolTypes.DeviceUserChanged, new { deviceUserId = "7" }, "reader-2"));
        store.Persist(GatewayEnvelope.Create("gw-1", ProtocolTypes.DeviceStatus, new { connectionState = "ONLINE" }, "reader-1"));

        using (var gateway = Start(new Server(), [("reader-1", new FakeReader())], store))
        {
            Assert.True(gateway.HasWorker("reader-1"));
        }

        var left = store.LoadPending();
        Assert.DoesNotContain(left, message => message.Type == ProtocolTypes.DeviceUserChanged && message.DeviceId == "reader-1");
        Assert.Contains(left, message => message.Type == ProtocolTypes.DeviceUserChanged && message.DeviceId == "reader-2");
        Assert.Contains(left, message => message.Type == ProtocolTypes.DeviceStatus && message.DeviceId == "reader-1");

        store.Persist(GatewayEnvelope.Create("gw-1", ProtocolTypes.DeviceUserChanged, new { deviceUserId = "9" }, "reader-1"));
        using (Start(new Server(), [("reader-1", new FakeReader())], store))
        {
        }

        Assert.Contains(store.LoadPending(), message => message.Type == ProtocolTypes.DeviceUserChanged && message.DeviceId == "reader-1");
    }

    [Fact]
    public async Task A_reader_off_when_the_gateway_starts_gets_a_worker_once_it_logs_in_and_is_read_before_it_is_written()
    {
        var device = new Login("reader-1") { Refuse = true };
        var timed = Timed(device);
        var fake = new FakeReader();
        var server = new Server { Items = [Item(1, "8", "Asha")] };
        using var gateway = Start(server, [("reader-1", fake)], Store(), timed);

        Assert.False(gateway.HasWorker("reader-1"));
        await gateway.ReceiveDesiredAsync(new DesiredRevisionNotice("reader-1", 1), CancellationToken.None);
        await gateway.CheckReaderHealthAsync(CancellationToken.None);
        Assert.False(gateway.HasWorker("reader-1"));
        Assert.Empty(fake.Calls);

        _now += TimeSpan.FromSeconds(11);
        await gateway.CheckReaderHealthAsync(CancellationToken.None);
        Assert.False(gateway.HasWorker("reader-1"));

        device.Refuse = false;
        _now += TimeSpan.FromSeconds(31);
        await gateway.CheckReaderHealthAsync(CancellationToken.None);

        Assert.True(gateway.HasWorker("reader-1"));
        Assert.Equal("ListUsers", fake.Calls[0]);
        Assert.Equal(["CreateUser 8", "InsertFace 8"], fake.Writes);
        var ack = Assert.Single(server.Acks);
        Assert.NotNull(ack.ReadAfter);
        Assert.True(server.Events.IndexOf("check") < server.Events.IndexOf("ack 1"));
    }

    [Fact]
    public async Task A_degraded_reader_counts_no_attempts_and_is_read_again_after_it_logs_back_in()
    {
        var device = new Login("reader-1");
        var timed = Timed(device);
        var fake = new FakeReader();
        var server = new Server();
        using var gateway = Start(server, [("reader-1", fake)], Store(), timed);
        await gateway.ReconnectReadersAsync(CancellationToken.None);
        var lists = fake.Calls.Count(call => call == "ListUsers");

        device.Broken = true;
        for (var i = 0; i < 4; i++)
        {
            timed.OpenDoor();
        }

        Assert.Equal(TimedDeviceAdapter.DegradedState, timed.GetHealth().ConnectionState);
        server.Items = [Item(1, "8", "Asha")];
        await gateway.ReceiveDesiredAsync(new DesiredRevisionNotice("reader-1", 1), CancellationToken.None);
        await gateway.ReceiveDesiredAsync(new DesiredRevisionNotice("reader-1", 1), CancellationToken.None);

        Assert.Empty(fake.Writes);
        Assert.Empty(server.Acks);
        Assert.Equal(0, AttemptCount("reader-1", 1));

        device.Broken = false;
        _now += TimeSpan.FromSeconds(11);
        var mark = server.Events.Count;
        await gateway.CheckReaderHealthAsync(CancellationToken.None);

        Assert.Equal(TimedDeviceAdapter.OnlineState, timed.GetHealth().ConnectionState);
        Assert.True(fake.Calls.Count(call => call == "ListUsers") > lists);
        Assert.Equal(["CreateUser 8", "InsertFace 8"], fake.Writes);
        Assert.Single(server.Acks);
        var after = server.Events.Skip(mark).ToList();
        Assert.True(after.IndexOf("check") >= 0 && after.IndexOf("check") < after.IndexOf("ack 1"));
    }

    [Fact]
    public async Task One_reader_comes_back_while_another_stays_off_and_only_the_one_that_is_back_is_written()
    {
        var first = new Login("reader-1") { Refuse = true };
        var second = new Login("reader-2") { Refuse = true };
        var firstFake = new FakeReader();
        var secondFake = new FakeReader();
        var server = new Server { Items = [Item(1, "8", "Asha")] };
        using var gateway = Start(server, [("reader-1", firstFake), ("reader-2", secondFake)], Store(),
            Timed(first), Timed(second));

        first.Refuse = false;
        _now += TimeSpan.FromSeconds(11);
        await gateway.CheckReaderHealthAsync(CancellationToken.None);

        Assert.True(gateway.HasWorker("reader-1"));
        Assert.False(gateway.HasWorker("reader-2"));
        Assert.Equal(["CreateUser 8", "InsertFace 8"], firstFake.Writes);
        Assert.Empty(secondFake.Calls);
        Assert.Equal(["reader-1"], server.Acks.Select(ack => ack.DeviceId));

        second.Refuse = false;
        _now += TimeSpan.FromSeconds(31);
        await gateway.CheckReaderHealthAsync(CancellationToken.None);

        Assert.True(gateway.HasWorker("reader-2"));
        Assert.Equal(["CreateUser 8", "InsertFace 8"], secondFake.Writes);
        Assert.Equal(["CreateUser 8", "InsertFace 8"], firstFake.Writes);
        Assert.Equal(["reader-1", "reader-2"], server.Acks.Select(ack => ack.DeviceId));
    }

    private ReaderWorker Worker(IReaderAdapter reader, string journal) =>
        new("r", reader, journal, TimeSpan.FromMinutes(1), () => _now);

    private static DesiredRevisionPath Revisions(
        ReaderWorker worker, IReaderAdapter reader, Server server, IReaderObservationUpload upload) =>
        new("r", worker, reader, server, upload, null, new DesiredPathOptions { ReconnectWait = TimeSpan.Zero });

    private DurableOutboundStore Store() =>
        new(NullLogger<DurableOutboundStore>.Instance, Path.Combine(_directory, "outbox"));

    private TimedDeviceAdapter Timed(Login device)
    {
        var timed = new TimedDeviceAdapter(device, NullLogger.Instance, () => _now);
        timed.Connect(new DeviceConnectionConfig(device.DeviceId, "10.0.0.8", 37777, "admin", "admin"));
        return timed;
    }

    /// <summary>The gateway as the host builds it; a reader's worker opens only for a logged-in reader.</summary>
    private GatewayWorker Start(
        Server server,
        IReadOnlyList<(string DeviceId, FakeReader Reader)> readers,
        DurableOutboundStore store,
        params IDeviceAdapter[] connected)
    {
        var options = new GatewayOptions
        {
            Id = "gw-1",
            Token = "token",
            BackendUrl = "http://127.0.0.1:9",
            UseWebSocket = false,
            Devices = readers
                .Select(reader => new DeviceEndpointOptions
                {
                    DeviceId = reader.DeviceId, Ip = "10.0.0.8", Port = 37777, Username = "admin", Password = "admin"
                })
                .ToList()
        };
        var logs = NullLoggerFactory.Instance;
        var link = new BackendLink(options, logs.CreateLogger<BackendLink>(), store);
        var byId = readers.ToDictionary(reader => reader.DeviceId, reader => (IReaderAdapter)reader.Reader);
        var gateway = new GatewayWorker(
            options,
            link,
            logs.CreateLogger<GatewayWorker>(),
            logs,
            new SuppliedReaderFactory((deviceId, adapter) =>
                connected.Length == 0 || adapter != null ? byId[deviceId] : null),
            server,
            Path.Combine(_directory, "journals"));
        foreach (var adapter in connected)
        {
            gateway.UseConnectedAdapter(adapter);
        }

        gateway.AttachReaders();
        return gateway;
    }

    private long AttemptCount(string deviceId, long revision)
    {
        using var connection = new SqliteConnection(
            $"Data Source={Path.Combine(_directory, "journals", deviceId + ".sqlite")};Mode=ReadOnly;Pooling=False");
        connection.Open();
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT attempt_count FROM revision_attempt WHERE reader_id = $reader AND revision = $revision";
        command.Parameters.AddWithValue("$reader", deviceId);
        command.Parameters.AddWithValue("$revision", revision);
        return command.ExecuteScalar() is long count ? count : 0;
    }

    private static DesiredPullItem Item(long revision, string id, string name, ExpectedOccupant? expected = null) => new(
        revision, id, name, null, 0, ReaderLocalTime.Format(ValidFrom), ReaderLocalTime.Format(ValidTo), "Customer", 1, 1,
        Face, true, true, expected ?? ExpectedOccupant.Absent);

    private static ExpectedOccupant Verified(string name, byte[] face) =>
        new(true, name, null, 0, ReaderLocalTime.Format(ValidFrom), ReaderLocalTime.Format(ValidTo), "Customer",
            FaceHash.Sha256Hex(face));

    private static ReaderUser Person(string id, string name) =>
        new(id, name, null, 0, ValidFrom, ValidTo, "Customer", 1, 1);

    private static void Enrol(FakeReader reader, string id, string name, byte[] face)
    {
        Assert.True(reader.CreateUser(Person(id, name)).Ok);
        Assert.True(reader.InsertFace(id, face).Ok);
    }

    private sealed class SuppliedReaderFactory(Func<string, IDeviceAdapter?, IReaderAdapter?> open) : IReaderAdapterFactory
    {
        public IReaderAdapter? Open(string deviceId, IDeviceAdapter? connected) => open(deviceId, connected);
    }

    /// <summary>A reader someone uses between the gateway's calls.</summary>
    private sealed class Hooked(FakeReader inner) : IReaderAdapter
    {
        public Action? BeforeFirstGetUser { get; set; }
        public Action? BeforeInsertFace { get; set; }
        public Action? AfterInsertFace { get; set; }
        public int Lists { get; private set; }
        public List<string> Log { get; } = [];

        public ReaderListResult ListUsers()
        {
            Lists++;
            Log.Add("ListUsers");
            return inner.ListUsers();
        }

        public ReaderUserResult GetUser(string deviceUserId)
        {
            var hook = BeforeFirstGetUser;
            BeforeFirstGetUser = null;
            hook?.Invoke();
            Log.Add("GetUser");
            return inner.GetUser(deviceUserId);
        }

        public ReaderFaceResult GetFace(string deviceUserId)
        {
            Log.Add("GetFace");
            return inner.GetFace(deviceUserId);
        }

        public ReaderCallResult InsertFace(string deviceUserId, byte[]? jpeg)
        {
            BeforeInsertFace?.Invoke();
            Log.Add("InsertFace");
            var result = inner.InsertFace(deviceUserId, jpeg);
            AfterInsertFace?.Invoke();
            return result;
        }

        public ReaderCallResult CreateUser(ReaderUser user) => inner.CreateUser(user);
        public ReaderCallResult ReplaceUser(ReaderUser user) => inner.ReplaceUser(user);
        public ReaderCallResult UpdateFace(string deviceUserId, byte[]? jpeg) => inner.UpdateFace(deviceUserId, jpeg);
        public ReaderCallResult RemoveFace(string deviceUserId) => inner.RemoveFace(deviceUserId);
        public ReaderCallResult RemoveUser(string deviceUserId) => inner.RemoveUser(deviceUserId);
        public FaceChecksumList ListFaceChecksums() => inner.ListFaceChecksums();
        public ReaderPunchResult QueryAttendance(DateTimeOffset fromUtc, DateTimeOffset toUtc) => inner.QueryAttendance(fromUtc, toUtc);
    }

    /// <summary>A reader session: login can be refused, and a logged-in session can break.</summary>
    private sealed class Login(string deviceId) : IDeviceAdapter
    {
        private readonly MockDeviceAdapter _inner = new();

        public bool Refuse { get; set; }
        public bool Broken { get; set; }
        public string DeviceId => deviceId;

        public DeviceConnectionStatus Connect(DeviceConnectionConfig config) =>
            Refuse ? DeviceConnectionStatus.Failed("timeout") : _inner.Connect(config);

        public void Disconnect() => _inner.Disconnect();
        public DeviceInfoSnapshot GetDeviceInfo() => _inner.GetDeviceInfo();
        public DeviceHealth GetHealth() => _inner.GetHealth();
        public DeviceCommandResult OpenDoor() => Broken ? DeviceCommandResult.Fail("timeout") : _inner.OpenDoor();
        public DeviceCommandResult CreateUser(DeviceUserMutation mutation) => _inner.CreateUser(mutation);
        public DeviceCommandResult UpdateUser(DeviceUserMutation mutation) => _inner.UpdateUser(mutation);
        public DeviceCommandResult DisableUser(string deviceUserId) => _inner.DisableUser(deviceUserId);
        public DeviceCommandResult EnableUser(string deviceUserId) => _inner.EnableUser(deviceUserId);
        public DeviceCommandResult DeleteUser(string deviceUserId) => _inner.DeleteUser(deviceUserId);
        public DeviceCommandResult UpdateValidity(DeviceUserMutation mutation) => _inner.UpdateValidity(mutation);
        public IReadOnlyList<DeviceUserSnapshot> ListUsers() => _inner.ListUsers();
        public DeviceUserSnapshot? GetUser(string deviceUserId) => _inner.GetUser(deviceUserId);
        public DeviceCommandResult UpsertFace(string deviceUserId, byte[] jpegBytes) => _inner.UpsertFace(deviceUserId, jpegBytes);
        public DeviceFaceRead GetFace(string deviceUserId) => _inner.GetFace(deviceUserId);
        public DeviceCommandResult DeleteFace(string deviceUserId) => _inner.DeleteFace(deviceUserId);
        public FaceProbeResult ProbeRemoteFaceInsert(string deviceUserId, byte[] jpegBytes) =>
            _inner.ProbeRemoteFaceInsert(deviceUserId, jpegBytes);
        public IReadOnlyList<DeviceAttendanceRecord> FetchAttendance(DateTimeOffset? fromUtc, DateTimeOffset? toUtc) =>
            _inner.FetchAttendance(fromUtc, toUtc);
        public void RegisterEventListener(IDeviceEventListener listener) => _inner.RegisterEventListener(listener);
        public DeviceCommandResult CloseDoor() => _inner.CloseDoor();
        public DeviceCommandResult SynchronizeTime(DateTimeOffset utcNow) => _inner.SynchronizeTime(utcNow);
        public void Dispose() => _inner.Dispose();
    }

    /// <summary>The server: records acknowledgements and reports in the order they arrive.</summary>
    private sealed class Server : IDesiredStateClient
    {
        public List<DesiredPullItem> Items { get; set; } = [];
        public List<DesiredAcknowledgement> Acks { get; } = [];
        public List<ReaderOccupant> Reports { get; } = [];
        public List<string> Occupied { get; } = [];
        public List<string> Events { get; } = [];

        public Task<DesiredPull> PullAsync(string deviceId, long after, CancellationToken cancellationToken)
        {
            lock (Events)
            {
                Events.Add("pull");
                var items = Items.Where(item => item.Revision > after).OrderBy(item => item.Revision).ToList();
                var desired = Items.Count == 0 ? 0 : Items.Max(item => item.Revision);
                return Task.FromResult(new DesiredPull(desired, after, items));
            }
        }

        public Task AcknowledgeAsync(DesiredAcknowledgement ack, CancellationToken cancellationToken)
        {
            lock (Events)
            {
                Events.Add("ack " + ack.Revision);
                Acks.Add(ack);
            }

            return Task.CompletedTask;
        }

        public Task ReportOccupiedAsync(string deviceId, long revision, string deviceUserId, CancellationToken cancellationToken)
        {
            lock (Events)
            {
                Events.Add("occupied " + deviceUserId);
                Occupied.Add(deviceUserId);
                Items = Items.Where(item => item.Revision != revision).ToList();
            }

            return Task.CompletedTask;
        }

        public Task ReportOccupantAsync(string deviceId, long revision, string deviceUserId, ReaderOccupant occupant,
            CancellationToken cancellationToken)
        {
            lock (Events)
            {
                Events.Add("occupant " + deviceUserId);
                Reports.Add(occupant);
                Items = Items.Where(item => item.Revision != revision).ToList();
            }

            return Task.CompletedTask;
        }

        public Task<bool> ObservationProcessedAsync(string deviceId, IReadOnlyList<string> messageIds,
            CancellationToken cancellationToken)
        {
            lock (Events)
            {
                Events.Add("check");
            }

            return Task.FromResult(true);
        }

        public Task ReportFailedAsync(string deviceId, RetryState attempt, CancellationToken cancellationToken) =>
            Task.CompletedTask;
    }
}
