using System.Net;
using System.Text;
using System.Text.Json;
using Gym.Gateway.Adapters;
using Gym.Gateway.Execution;
using Microsoft.Extensions.Logging;
using Xunit;

namespace Gym.Gateway.Tests;

public class AttendancePollTests
{
    private static readonly DateTimeOffset Now = new(2026, 10, 11, 9, 0, 0, TimeSpan.Zero);
    private static readonly AttendancePollSettings Settings = new(TimeSpan.FromMinutes(2), TimeSpan.FromHours(24));

    [Fact]
    public async Task A_repeated_window_posts_each_punch_once()
    {
        var reader = new FakeReader();
        reader.AddPunch(Punch("1", Now.AddMinutes(-5), 11));
        var server = new StoringServer();
        var poll = Poll(reader, server);

        await poll.PollOnceAsync(CancellationToken.None);
        var second = await poll.PollOnceAsync(CancellationToken.None);

        Assert.Single(server.Posts);
        Assert.Single(server.Stored);
        Assert.Equal(1, second.Read);
        Assert.Equal(0, second.Sent);
    }

    [Fact]
    public async Task An_empty_window_posts_nothing()
    {
        var reader = new FakeReader();
        var server = new StoringServer();

        var result = await Poll(reader, server).PollOnceAsync(CancellationToken.None);

        Assert.True(result.Ok);
        Assert.Empty(server.Posts);
        Assert.Empty(server.Stored);
    }

    [Fact]
    public async Task The_window_starts_at_the_last_stored_punch_less_the_overlap()
    {
        var reader = new FakeReader();
        var server = new StoringServer { LastEventAt = Now.AddHours(-1) };

        var result = await Poll(reader, server).PollOnceAsync(CancellationToken.None);

        Assert.Equal(Now.AddHours(-1).AddMinutes(-2), result.FromUtc);
        Assert.Equal(Now, result.ToUtc);
    }

    [Fact]
    public async Task A_reader_with_no_stored_punch_reads_back_the_first_lookback()
    {
        var result = await Poll(new FakeReader(), new StoringServer()).PollOnceAsync(CancellationToken.None);

        Assert.Equal(Now.AddHours(-24).AddMinutes(-2), result.FromUtc);
    }

    [Fact]
    public async Task The_next_window_follows_the_stored_punch_time_returned_by_the_server()
    {
        var reader = new FakeReader();
        reader.AddPunch(Punch("1", Now.AddMinutes(-5), 11));
        var server = new StoringServer();
        var poll = Poll(reader, server);

        await poll.PollOnceAsync(CancellationToken.None);
        var next = await poll.PollOnceAsync(CancellationToken.None);

        Assert.Equal(Now.AddMinutes(-5), poll.LastStored);
        Assert.Equal(Now.AddMinutes(-7), next.FromUtc);
    }

    [Fact]
    public async Task A_failed_read_posts_nothing_and_the_same_window_is_read_again()
    {
        var reader = new FakeReader();
        reader.AddPunch(Punch("1", Now.AddMinutes(-5), 11));
        reader.ScriptFailure(FakeReaderOperation.QueryAttendance, "reader busy");
        var server = new StoringServer { LastEventAt = Now.AddHours(-1) };
        var poll = Poll(reader, server);

        var failed = await poll.PollOnceAsync(CancellationToken.None);
        var retried = await poll.PollOnceAsync(CancellationToken.None);

        Assert.False(failed.Ok);
        Assert.Equal(failed.FromUtc, retried.FromUtc);
        Assert.Single(server.Posts);
        Assert.Single(server.Stored);
    }

    [Fact]
    public async Task A_failed_upload_is_sent_again_on_the_next_poll()
    {
        var reader = new FakeReader();
        reader.AddPunch(Punch("1", Now.AddMinutes(-5), 11));
        var server = new StoringServer { FailNextPost = true };
        var poll = Poll(reader, server);

        var failed = await poll.PollOnceAsync(CancellationToken.None);
        var retried = await poll.PollOnceAsync(CancellationToken.None);

        Assert.False(failed.Ok);
        Assert.Equal(0, failed.Sent);
        Assert.Equal(1, retried.Sent);
        Assert.Single(server.Stored);
    }

    [Fact]
    public async Task A_large_window_is_uploaded_oldest_first_in_batches_of_100()
    {
        var reader = new FakeReader();
        foreach (var punch in Window(1201).Reverse())
        {
            reader.AddPunch(punch);
        }

        var server = new StoringServer();

        var result = await Poll(reader, server).PollOnceAsync(CancellationToken.None);

        Assert.True(result.Ok);
        Assert.Equal(Enumerable.Repeat(100, 12).Append(1), server.Posts.Select(p => p.Count));
        var sent = server.Posts.SelectMany(p => p).Select(p => p.OccurredAtUtc).ToArray();
        Assert.Equal(sent.Order(), sent);
        Assert.Equal(1201, server.Stored.Count);
        Assert.Equal(1201, result.Stored);
    }

    [Fact]
    public async Task A_failed_batch_keeps_the_cursor_before_the_unsent_punches_and_the_rest_is_sent_next_time()
    {
        var reader = new FakeReader();
        var punches = Window(1201);
        foreach (var punch in punches)
        {
            reader.AddPunch(punch);
        }

        var server = new StoringServer { FailPostNumber = 2 };
        var poll = Poll(reader, server);

        var failed = await poll.PollOnceAsync(CancellationToken.None);

        Assert.False(failed.Ok);
        Assert.Equal(100, failed.Sent);
        Assert.Equal(punches[99].OccurredAtUtc, server.LastEventAt);
        Assert.Equal(punches[99].OccurredAtUtc, poll.LastStored);
        Assert.True(poll.LastStored < punches[100].OccurredAtUtc);

        var retried = await poll.PollOnceAsync(CancellationToken.None);

        Assert.True(retried.Ok);
        Assert.True(retried.FromUtc <= punches[100].OccurredAtUtc);
        Assert.Equal(1101, retried.Sent);
        Assert.Equal(Enumerable.Repeat(100, 12).Append(1), server.Posts.Select(p => p.Count));
        Assert.Equal(1201, server.Stored.Count);
        Assert.Equal(punches[^1].OccurredAtUtc, server.LastEventAt);
    }

    [Fact]
    public async Task A_future_dated_punch_does_not_stop_later_real_punches_from_being_read()
    {
        var now = Now;
        var reader = new FakeReader();
        reader.AddPunch(Punch("1", now.AddMinutes(-5), 11));
        reader.AddPunch(Punch("9", now.AddDays(1), 12));
        var server = new StoringServer { ServerNow = now };
        var poll = new AttendancePoll("reader-1", new FutureReader(reader), server, Settings, clock: () => now);

        var first = await poll.PollOnceAsync(CancellationToken.None);

        Assert.Equal(2, first.Sent);
        Assert.Equal(now.AddMinutes(-5), server.LastEventAt);
        Assert.Equal(now.AddMinutes(-5), poll.LastStored);

        now = Now.AddMinutes(10);
        server.ServerNow = now;
        reader.AddPunch(Punch("2", Now.AddMinutes(3), 13));
        var next = await poll.PollOnceAsync(CancellationToken.None);

        Assert.Equal(Now.AddMinutes(-7), next.FromUtc);
        Assert.Equal(1, next.Sent);
        Assert.Contains(server.Stored, p => p.DeviceUserId == "2");
        Assert.Equal(Now.AddMinutes(3), poll.LastStored);
    }

    [Fact]
    public async Task A_last_stored_time_ahead_of_the_clock_never_starts_the_window_in_the_future()
    {
        var reader = new FakeReader();
        reader.AddPunch(Punch("1", Now.AddMinutes(-1), 11));
        var server = new StoringServer { LastEventAt = Now.AddDays(1), ReportedLastEventAt = Now.AddDays(1) };
        var poll = Poll(reader, server);

        var first = await poll.PollOnceAsync(CancellationToken.None);

        Assert.Equal(Now.AddMinutes(-2), first.FromUtc);
        Assert.Equal(1, first.Sent);
        Assert.Equal(Now, poll.LastStored);
    }

    [Fact]
    public async Task A_punch_by_an_unknown_reader_user_is_still_sent()
    {
        var reader = new FakeReader();
        reader.AddPunch(Punch("900", Now.AddMinutes(-3), 12));
        reader.AddPunch(Punch(null, Now.AddMinutes(-2), 13, granted: false, errorCode: 0x10));
        var server = new StoringServer();

        await Poll(reader, server).PollOnceAsync(CancellationToken.None);

        Assert.Equal(["900", null], server.Stored.Select(p => p.DeviceUserId));
    }

    [Fact]
    public async Task Polling_only_queries_attendance_and_never_writes_or_clears_the_reader()
    {
        var reader = new FakeReader();
        reader.AddPunch(Punch("1", Now.AddMinutes(-5), 11));
        var poll = Poll(reader, new StoringServer());

        await poll.PollOnceAsync(CancellationToken.None);
        await poll.PollOnceAsync(CancellationToken.None);

        Assert.Empty(reader.Writes);
        Assert.All(reader.Calls, call => Assert.Equal("QueryAttendance", call));
        Assert.Single(reader.QueryAttendance(Now.AddDays(-1), Now).Punches);
    }

    [Fact]
    public async Task A_member_write_waits_for_the_attendance_query_but_not_for_the_upload()
    {
        var fake = new FakeReader();
        fake.AddPunch(Punch("1", Now.AddMinutes(-5), 11));
        var gate = new GatedReader(fake);
        var locks = new DeviceLocks();
        var reader = new LockedReaderAdapter("reader-1", gate, locks);
        var server = new StoringServer { HoldPosts = true };
        var poll = new AttendancePoll("reader-1", reader, server, Settings, clock: () => Now);

        var polling = poll.PollOnceAsync(CancellationToken.None);
        Assert.True(gate.QueryStarted.Wait(TimeSpan.FromSeconds(5)));
        var write = Task.Run(() => reader.CreateUser(Member("2")));

        await Task.Delay(150);
        Assert.False(write.IsCompleted);
        Assert.StartsWith("reading attendance", locks.BusyWith("reader-1"));

        gate.ReleaseQuery.Set();
        Assert.True(server.PostStarted.Wait(TimeSpan.FromSeconds(5)));
        var written = await write.WaitAsync(TimeSpan.FromSeconds(5));

        Assert.True(written.Ok);
        Assert.False(polling.IsCompleted);
        server.ReleasePost.Set();
        await polling.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.Single(server.Stored);
    }

    [Fact]
    public async Task A_punch_on_the_fake_reader_is_posted_once_by_the_gateway()
    {
        var directory = Path.Combine(Path.GetTempPath(), "gym-attendance-" + Guid.NewGuid().ToString("N"));
        try
        {
            var reader = new FakeReader();
            reader.AddPunch(Punch("1", DateTimeOffset.UtcNow.AddMinutes(-1), 7));
            var backend = new AttendanceBackend();
            var gateway = Gateway(directory, reader, new AttendanceClient(new HttpClient(backend), "http://backend"));

            await gateway.PollAttendanceAsync(CancellationToken.None);
            await gateway.PollAttendanceAsync(CancellationToken.None);

            Assert.Equal(1, backend.WindowPosts);
            var stored = Assert.Single(backend.Stored);
            Assert.Equal("reader-1", stored.GetProperty("deviceId").GetString());
            var punch = stored.GetProperty("punch");
            Assert.Equal("1", punch.GetProperty("deviceUserId").GetString());
            Assert.Equal(7, punch.GetProperty("recNo").GetInt64());
            Assert.EndsWith("Z", punch.GetProperty("occurredAt").GetString());
            Assert.Empty(reader.Writes);
        }
        finally
        {
            if (Directory.Exists(directory))
            {
                Directory.Delete(directory, true);
            }
        }
    }

    private static AttendancePoll Poll(IReaderAdapter reader, IAttendanceClient client) =>
        new("reader-1", reader, client, Settings, clock: () => Now);

    private static ReaderPunch Punch(
        string? userId, DateTimeOffset at, long recNo, bool granted = true, int? errorCode = null) =>
        new(userId, at, recNo, "FACE", granted, errorCode);

    /// <summary>One punch per second, oldest first, ending a minute before <see cref="Now"/>.</summary>
    private static ReaderPunch[] Window(int count) =>
        Enumerable.Range(0, count)
            .Select(i => Punch((1000 + i).ToString(), Now.AddMinutes(-1).AddSeconds(i - count), i))
            .ToArray();

    private static ReaderUser Member(string id) => new(id, "Member " + id, null, 0, null, null, "Customer", 1, 1);

    private static GatewayWorker Gateway(string directory, FakeReader reader, IAttendanceClient attendance)
    {
        var options = new GatewayOptions
        {
            Id = "gw-1",
            Token = "token",
            BackendUrl = "http://backend",
            UseWebSocket = false,
            Devices =
            [
                new DeviceEndpointOptions
                {
                    DeviceId = "reader-1",
                    Ip = "10.0.0.9",
                    Port = 37777,
                    Username = "admin",
                    Password = "admin"
                }
            ]
        };
        var logs = LoggerFactory.Create(_ => { });
        var link = new BackendLink(
            options,
            logs.CreateLogger<BackendLink>(),
            new DurableOutboundStore(logs.CreateLogger<DurableOutboundStore>(), Path.Combine(directory, "outbox")));
        var gateway = new GatewayWorker(
            options,
            link,
            logs.CreateLogger<GatewayWorker>(),
            logs,
            new SingleReaderFactory(reader),
            new NothingDesired(),
            Path.Combine(directory, "journals"),
            attendance);
        gateway.AttachReaders();
        return gateway;
    }

    /// <summary>
    /// Keeps punches on the same key as the backend and reports the last stored punch time. Like the
    /// backend, a punch after <see cref="ServerNow"/> is stored but does not move that time.
    /// </summary>
    private sealed class StoringServer : IAttendanceClient
    {
        private readonly HashSet<string> _keys = [];
        private int _posts;

        public DateTimeOffset? LastEventAt { get; set; }

        public DateTimeOffset ServerNow { get; set; } = DateTimeOffset.MaxValue;

        /// <summary>Reported after each upload instead of the stored time, as a server without the clock rule would.</summary>
        public DateTimeOffset? ReportedLastEventAt { get; set; }

        public bool FailNextPost { get; set; }

        /// <summary>The upload with this number (1-based, counting failures) fails once.</summary>
        public int? FailPostNumber { get; set; }

        public bool HoldPosts { get; init; }

        public ManualResetEventSlim PostStarted { get; } = new();

        public ManualResetEventSlim ReleasePost { get; } = new();

        public List<IReadOnlyList<ReaderPunch>> Posts { get; } = [];

        public List<ReaderPunch> Stored { get; } = [];

        public Task<DateTimeOffset?> LastStoredAsync(string deviceId, CancellationToken cancellationToken) =>
            Task.FromResult(LastEventAt);

        public async Task<AttendanceStored> StoreAsync(
            string deviceId, IReadOnlyList<ReaderPunch> punches, CancellationToken cancellationToken)
        {
            PostStarted.Set();
            if (HoldPosts)
            {
                await Task.Run(() => ReleasePost.Wait(TimeSpan.FromSeconds(10)), cancellationToken);
            }

            _posts++;
            if (FailNextPost || FailPostNumber == _posts)
            {
                FailNextPost = false;
                throw new HttpRequestException("backend unavailable");
            }

            if (punches.Count > AttendancePoll.MaxBatch)
            {
                throw new HttpRequestException("400: more than 500 punches");
            }

            Posts.Add(punches);
            var stored = 0;
            foreach (var punch in punches)
            {
                if (_keys.Add($"{punch.DeviceUserId}|{punch.OccurredAtUtc.UtcTicks}|{punch.Method}|{punch.Granted}|{punch.RecordNumber}"))
                {
                    Stored.Add(punch);
                    stored++;
                }

                if (punch.OccurredAtUtc <= ServerNow && (LastEventAt is null || punch.OccurredAtUtc > LastEventAt))
                {
                    LastEventAt = punch.OccurredAtUtc;
                }
            }

            return new AttendanceStored(stored, ReportedLastEventAt ?? LastEventAt);
        }
    }

    /// <summary>The backend's attendance endpoints over HTTP, keyed like the backend fingerprint.</summary>
    private sealed class AttendanceBackend : HttpMessageHandler
    {
        private readonly HashSet<string> _keys = [];
        private string? _lastEventAt;

        public int WindowPosts { get; private set; }

        public List<JsonElement> Stored { get; } = [];

        protected override async Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request, CancellationToken cancellationToken)
        {
            var path = request.RequestUri!.AbsolutePath;
            if (request.Method == HttpMethod.Get && path == "/internal/gateway/attendance/cursor")
            {
                return Json(new { deviceId = "reader-1", lastEventAt = _lastEventAt, stored = 0 });
            }

            if (request.Method != HttpMethod.Post || path != "/internal/gateway/attendance")
            {
                return new HttpResponseMessage(HttpStatusCode.NotFound);
            }

            WindowPosts++;
            using var body = JsonDocument.Parse(await request.Content!.ReadAsStringAsync(cancellationToken));
            var deviceId = body.RootElement.GetProperty("deviceId").GetString();
            var stored = 0;
            foreach (var punch in body.RootElement.GetProperty("punches").EnumerateArray())
            {
                var at = punch.GetProperty("occurredAt").GetString()!;
                if (_keys.Add(punch.GetRawText()))
                {
                    Stored.Add(JsonDocument.Parse(JsonSerializer.Serialize(new { deviceId, punch })).RootElement);
                    stored++;
                }

                if (_lastEventAt is null || string.CompareOrdinal(at, _lastEventAt) > 0)
                {
                    _lastEventAt = at;
                }
            }

            return Json(new { deviceId, lastEventAt = _lastEventAt, stored });
        }

        private static HttpResponseMessage Json(object value) => new(HttpStatusCode.OK)
        {
            Content = new StringContent(JsonSerializer.Serialize(value), Encoding.UTF8, "application/json")
        };
    }

    /// <summary>Holds the attendance query open until released.</summary>
    private sealed class GatedReader(FakeReader inner) : IReaderAdapter
    {
        public ManualResetEventSlim QueryStarted { get; } = new();

        public ManualResetEventSlim ReleaseQuery { get; } = new();

        public ReaderPunchResult QueryAttendance(DateTimeOffset fromUtc, DateTimeOffset toUtc)
        {
            QueryStarted.Set();
            ReleaseQuery.Wait(TimeSpan.FromSeconds(10));
            return inner.QueryAttendance(fromUtc, toUtc);
        }

        public ReaderListResult ListUsers() => inner.ListUsers();

        public ReaderUserResult GetUser(string deviceUserId) => inner.GetUser(deviceUserId);

        public ReaderCallResult CreateUser(ReaderUser user) => inner.CreateUser(user);

        public ReaderCallResult ReplaceUser(ReaderUser user) => inner.ReplaceUser(user);

        public ReaderFaceResult GetFace(string deviceUserId) => inner.GetFace(deviceUserId);

        public ReaderCallResult InsertFace(string deviceUserId, byte[]? jpeg) => inner.InsertFace(deviceUserId, jpeg);

        public ReaderCallResult UpdateFace(string deviceUserId, byte[]? jpeg) => inner.UpdateFace(deviceUserId, jpeg);

        public ReaderCallResult RemoveFace(string deviceUserId) => inner.RemoveFace(deviceUserId);

        public ReaderCallResult RemoveUser(string deviceUserId) => inner.RemoveUser(deviceUserId);
    }

    /// <summary>A reader whose log answers past the end of the window, as one with its clock ahead would.</summary>
    private sealed class FutureReader(FakeReader inner) : IReaderAdapter
    {
        public ReaderPunchResult QueryAttendance(DateTimeOffset fromUtc, DateTimeOffset toUtc) =>
            inner.QueryAttendance(fromUtc, DateTimeOffset.MaxValue);

        public ReaderListResult ListUsers() => inner.ListUsers();

        public ReaderUserResult GetUser(string deviceUserId) => inner.GetUser(deviceUserId);

        public ReaderCallResult CreateUser(ReaderUser user) => inner.CreateUser(user);

        public ReaderCallResult ReplaceUser(ReaderUser user) => inner.ReplaceUser(user);

        public ReaderFaceResult GetFace(string deviceUserId) => inner.GetFace(deviceUserId);

        public ReaderCallResult InsertFace(string deviceUserId, byte[]? jpeg) => inner.InsertFace(deviceUserId, jpeg);

        public ReaderCallResult UpdateFace(string deviceUserId, byte[]? jpeg) => inner.UpdateFace(deviceUserId, jpeg);

        public ReaderCallResult RemoveFace(string deviceUserId) => inner.RemoveFace(deviceUserId);

        public ReaderCallResult RemoveUser(string deviceUserId) => inner.RemoveUser(deviceUserId);
    }

    private sealed class SingleReaderFactory(IReaderAdapter reader) : IReaderAdapterFactory
    {
        public IReaderAdapter? Open(string deviceId, IDeviceAdapter? connected) => reader;
    }

    private sealed class NothingDesired : IDesiredStateClient
    {
        public Task<DesiredPull> PullAsync(string deviceId, long after, CancellationToken cancellationToken) =>
            Task.FromResult(new DesiredPull(0, 0, []));

        public Task AcknowledgeAsync(DesiredAcknowledgement ack, CancellationToken cancellationToken) =>
            Task.CompletedTask;

        public Task ReportOccupiedAsync(
            string deviceId, long revision, string deviceUserId, CancellationToken cancellationToken) =>
            Task.CompletedTask;

        public Task<bool> ObservationProcessedAsync(string deviceId, IReadOnlyList<string> messageIds,
            CancellationToken cancellationToken) => Task.FromResult(true);

        public Task ReportFailedAsync(string deviceId, RetryState attempt, CancellationToken cancellationToken) =>
            Task.CompletedTask;

        public Task ReportOccupantAsync(string deviceId, long revision, string deviceUserId, ReaderOccupant occupant,
            CancellationToken cancellationToken) =>
            throw new InvalidOperationException("Unexpected occupant at " + deviceUserId);
    }
}
