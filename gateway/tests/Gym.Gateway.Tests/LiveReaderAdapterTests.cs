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
    public void An_end_time_the_reader_stores_as_end_of_the_same_day_is_acknowledged()
    {
        var device = new StructReader();
        var reader = new DeviceReaderAdapter(device);
        using var worker = new ReaderWorker("reader", reader, Path.Combine(_directory, "reader.sqlite"), TimeSpan.Zero);

        var result = worker.ApplyMember(new DesiredMember(
            1, "1", "Asha Shah", null, 0, From, new DateTimeOffset(2026, 10, 8, 0, 0, 0, India), "Customer", 1, 1, Face));

        Assert.Equal(MemberApplyKind.Applied, result.Kind);
        Assert.Equal(23u, device.Stored("1").stuValidEndTime.dwHour);
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
        Assert.StartsWith("verification failed (reason=field-mismatch", worker.Retry!.LastError, StringComparison.Ordinal);
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

    [Fact]
    public void Unknown_user_read_is_not_a_missing_user()
    {
        var unknown = DeviceReaderAdapter.FromLookup(TrueFaceDeviceAdapter.UserLookup.Unknown);
        Assert.Equal(FakeReader.FailUnknown, unknown.FailCode);
        Assert.Equal(FakeReader.SdkErrorMissingRecord, unknown.SdkError);
        Assert.NotEqual(FakeReader.FailNoRecord, unknown.FailCode);

        var missing = DeviceReaderAdapter.FromLookup(TrueFaceDeviceAdapter.UserLookup.Missing);
        Assert.Equal(FakeReader.FailNoRecord, missing.FailCode);
        Assert.Equal(FakeReader.SdkErrorMissingRecord, missing.SdkError);
    }

    [Fact]
    public void An_attendance_search_that_finds_nothing_is_an_empty_window()
    {
        var records = TrueFaceDeviceAdapter.ReadAttendance(new ScriptedSearch(true, (0, [])), 20);

        Assert.Empty(records);
    }

    [Fact]
    public void A_failed_attendance_search_is_a_failure_not_an_empty_window()
    {
        var error = Assert.Throws<AttendanceReadException>(() =>
            TrueFaceDeviceAdapter.ReadAttendance(new ScriptedSearch(false), 20));

        Assert.Contains("Attendance search failed", error.Message);
    }

    [Fact]
    public void A_failed_later_attendance_page_returns_no_partial_window()
    {
        var full = Enumerable.Range(1, 2).Select(i => Record(i, "1", 2026, 10, 11, 5, 0, i)).ToArray();
        var search = new ScriptedSearch(true, (2, full), (-1, []));

        var error = Assert.Throws<AttendanceReadException>(() => TrueFaceDeviceAdapter.ReadAttendance(search, 2));

        Assert.Contains("page 2 failed after 2 record(s)", error.Message);
    }

    [Fact]
    public void An_attendance_record_with_an_unreadable_time_fails_the_window()
    {
        var search = new ScriptedSearch(true,
            (2, [Record(1, "1", 2026, 10, 11, 5, 0, 0), Record(2, "2", 2026, 13, 0, 0, 0, 0)]));

        var error = Assert.Throws<AttendanceReadException>(() => TrueFaceDeviceAdapter.ReadAttendance(search, 20));

        Assert.Contains("record 2 has an unreadable time 2026-13-00", error.Message);
    }

    [Fact]
    public void Attendance_pages_are_read_to_the_end_and_returned_oldest_first_as_utc()
    {
        var search = new ScriptedSearch(true,
            (2, [Record(9, "3", 2026, 10, 11, 7, 0, 0), Record(8, "2", 2026, 10, 11, 6, 0, 0)]),
            (1, [Record(7, "1", 2026, 10, 11, 5, 0, 0)]));

        var records = TrueFaceDeviceAdapter.ReadAttendance(search, 2);

        Assert.Equal([7L, 8L, 9L], records.Select(r => r.RecNo!.Value));
        Assert.Equal(new DateTimeOffset(2026, 10, 11, 5, 0, 0, TimeSpan.Zero), records[0].OccurredAt);
        Assert.Equal(TimeSpan.Zero, records[0].OccurredAt.Offset);
    }

    [Fact]
    public void A_failed_live_attendance_read_is_a_failed_reader_window()
    {
        var device = new StructReader
        {
            Attendance = () => throw new AttendanceReadException("Attendance page 2 failed after 20 record(s): timeout")
        };

        var read = new DeviceReaderAdapter(device).QueryAttendance(
            DateTimeOffset.UtcNow.AddHours(-1), DateTimeOffset.UtcNow);

        Assert.False(read.Ok);
        Assert.Empty(read.Punches);
        Assert.Contains("page 2 failed", read.Error);
    }

    [Fact]
    public void A_reader_that_is_not_online_does_not_answer_the_reconnect_read()
    {
        var reader = new DeviceReaderAdapter(new MockDeviceAdapter());
        var listed = reader.ListUsers();
        Assert.False(listed.Ok);
    }

    [Fact]
    public void Removing_a_user_is_no_record_and_a_second_remove_succeeds()
    {
        var device = new MockDeviceAdapter();
        device.Connect(new DeviceConnectionConfig("reader", "127.0.0.1", 37777, "admin", "x"));
        Assert.True(device.CreateUser(new DeviceUserMutation("1", "Asha Shah", true, From, To, "Customer")).Ok);
        var writer = new DeviceReaderAdapter(device);

        Assert.True(writer.RemoveUser("1").Ok);
        var gone = writer.GetUser("1");
        Assert.Equal(FakeReader.FailNoRecord, gone.FailCode);
        Assert.Equal(FakeReader.SdkErrorMissingRecord, gone.SdkError);
        Assert.True(writer.RemoveUser("1").Ok);
        Assert.Equal(FakeReader.FailNoRecord, writer.GetUser("1").FailCode);
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

    private static NET_RECORDSET_ACCESS_CTL_CARDREC Record(
        int recNo, string userId, uint year, uint month, uint day, uint hour, uint minute, int second) => new()
    {
        nRecNo = recNo,
        szUserID = userId,
        bStatus = true,
        emMethod = EM_ACCESS_DOOROPEN_METHOD.FACE_RECOGNITION,
        stuTime = new NET_TIME
        {
            dwYear = year, dwMonth = month, dwDay = day, dwHour = hour, dwMinute = minute, dwSecond = (uint)second
        }
    };

    /// <summary>An SDK search that starts or not, then answers each page with a result and its records.</summary>
    private sealed class ScriptedSearch(bool starts, params (int Result, NET_RECORDSET_ACCESS_CTL_CARDREC[] Rows)[] pages)
        : TrueFaceDeviceAdapter.IAttendanceSearch
    {
        private int _next;

        public bool Start(out string error)
        {
            error = starts ? "" : "NET_ERROR_TIMEOUT";
            return starts;
        }

        public int Next(int max, out IReadOnlyList<NET_RECORDSET_ACCESS_CTL_CARDREC> records, out string error)
        {
            var (result, rows) = _next < pages.Length ? pages[_next++] : (0, []);
            records = result < 0 ? [] : rows;
            error = result < 0 ? "NET_ERROR_TIMEOUT" : "";
            return result;
        }
    }

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

        public Func<IReadOnlyList<DeviceAttendanceRecord>>? Attendance { get; set; }

        public IReadOnlyList<DeviceAttendanceRecord> FetchAttendance(DateTimeOffset? fromUtc, DateTimeOffset? toUtc) =>
            Attendance?.Invoke() ?? [];

        public void RegisterEventListener(IDeviceEventListener listener)
        {
        }

        public DeviceCommandResult OpenDoor() => DeviceCommandResult.Success();

        public DeviceCommandResult CloseDoor() => DeviceCommandResult.Success();

        public DeviceCommandResult SynchronizeTime(DateTimeOffset utcNow) => DeviceCommandResult.Success();

        public void Dispose()
        {
        }
    }
}
