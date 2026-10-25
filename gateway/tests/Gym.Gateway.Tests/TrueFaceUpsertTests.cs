using Gym.Gateway.Adapters;
using NetSDKCS;
using Xunit;
using Lookup = Gym.Gateway.Adapters.TrueFaceDeviceAdapter.UserLookup;

namespace Gym.Gateway.Tests;

/// <summary>
/// A reader write replaces the whole user record, so a user the gateway could not read must never be
/// rebuilt from a partial command (that turned names into IDs, dropped admin and cleared validity).
/// </summary>
public class TrueFaceUpsertTests
{
    private static NET_ACCESS_USER_INFO Stored(string id = "648") =>
        new()
        {
            szUserID = id,
            szName = "Annu Rana",
            szNameEx = "Annu Rana",
            bUseNameEx = true,
            nUserStatus = 0,
            stuValidBeginTime = NET_TIME.FromDateTime(new DateTime(2018, 1, 1)),
            stuValidEndTime = NET_TIME.FromDateTime(new DateTime(2026, 6, 5, 23, 59, 59)),
            emAuthority = EM_ATTENDANCE_AUTHORITY.Administrators
        };

    [Fact]
    public void A_returned_record_is_found()
    {
        Assert.Equal(Lookup.Found, TrueFaceDeviceAdapter.ClassifyUserRead(true, [Stored()], EM_FAILCODE.NOERROR, true));
    }

    [Fact]
    public void A_successful_read_without_a_user_or_a_not_found_code_is_missing()
    {
        Assert.Equal(Lookup.Missing, TrueFaceDeviceAdapter.ClassifyUserRead(true, [new NET_ACCESS_USER_INFO()], EM_FAILCODE.NOERROR, true));
        Assert.Equal(Lookup.Missing, TrueFaceDeviceAdapter.ClassifyUserRead(true, [], EM_FAILCODE.NOERROR, true));
        Assert.Equal(Lookup.Missing, TrueFaceDeviceAdapter.ClassifyUserRead(false, null, EM_FAILCODE.NO_RECORD, true));
        Assert.Equal(Lookup.Missing, TrueFaceDeviceAdapter.ClassifyUserRead(false, null, EM_FAILCODE.INVALID_USER, true));
    }

    [Fact]
    public void A_failed_read_is_not_an_empty_id()
    {
        var present = new DeviceUserSnapshot("1238", "abc", false);

        Assert.Null(TrueFaceDeviceAdapter.RequireConfirmedRead(Lookup.Missing, null, "1238"));
        Assert.Same(present, TrueFaceDeviceAdapter.RequireConfirmedRead(Lookup.Found, present, "1238"));
        Assert.Throws<DeviceReadException>(() =>
            TrueFaceDeviceAdapter.RequireConfirmedRead(Lookup.Unreadable, null, "1238"));
        Assert.Throws<DeviceReadException>(() =>
            TrueFaceDeviceAdapter.RequireConfirmedRead(Lookup.Unknown, null, "1238"));
    }

    [Fact]
    public void A_transport_failure_is_unreadable_and_an_unexplained_error_is_unknown()
    {
        Assert.Equal(Lookup.Unreadable, TrueFaceDeviceAdapter.ClassifyUserRead(false, null, EM_FAILCODE.UNKNOWN, false));
        Assert.Equal(Lookup.Unreadable, TrueFaceDeviceAdapter.ClassifyUserRead(false, null, EM_FAILCODE.NO_RECORD, false));
        Assert.Equal(Lookup.Unreadable, TrueFaceDeviceAdapter.ClassifyUserRead(false, null, EM_FAILCODE.INVALID_USER, false));
        Assert.Equal(Lookup.Unknown, TrueFaceDeviceAdapter.ClassifyUserRead(false, null, EM_FAILCODE.UNKNOWN, true));
        Assert.Equal(Lookup.Unknown, TrueFaceDeviceAdapter.ClassifyUserRead(false, null, EM_FAILCODE.NOERROR, true));
    }

    [Fact]
    public void An_empty_list_or_a_list_holding_the_user_does_not_confirm_absence()
    {
        Assert.Equal(Lookup.Unreadable, TrueFaceDeviceAdapter.ConfirmAbsent([], "648"));
        Assert.Equal(Lookup.Unreadable, TrueFaceDeviceAdapter.ConfirmAbsent([new DeviceUserSnapshot("648", "Annu Rana", false)], "648"));
        Assert.Equal(Lookup.Missing, TrueFaceDeviceAdapter.ConfirmAbsent([new DeviceUserSnapshot("12", "Other", false)], "648"));
    }

    [Fact]
    public void A_validity_change_on_a_found_user_keeps_name_admin_and_unrelated_fields()
    {
        var mutation = new DeviceUserMutation("648", ValidFrom: new DateTimeOffset(2026, 1, 1, 0, 0, 0, TimeSpan.Zero),
            ValidTo: new DateTimeOffset(2026, 12, 31, 0, 0, 0, TimeSpan.Zero));

        var written = TrueFaceDeviceAdapter.ApplyMutation(Stored(), mutation);

        Assert.Equal("Annu Rana", TrueFaceDeviceAdapter.DeviceName(written));
        Assert.Equal(EM_ATTENDANCE_AUTHORITY.Administrators, written.emAuthority);
        Assert.Equal(0u, written.nUserStatus);
        Assert.Equal(2026u, written.stuValidBeginTime.dwYear);
        Assert.Equal(23u, written.stuValidEndTime.dwHour);
    }

    [Fact]
    public void A_disable_on_a_found_user_changes_only_the_status()
    {
        var written = TrueFaceDeviceAdapter.ApplyMutation(Stored(), new DeviceUserMutation("648", Enabled: false));

        Assert.Equal(1u, written.nUserStatus);
        Assert.Equal("Annu Rana", TrueFaceDeviceAdapter.DeviceName(written));
        Assert.Equal(EM_ATTENDANCE_AUTHORITY.Administrators, written.emAuthority);
        Assert.Equal(2018u, written.stuValidBeginTime.dwYear);
    }

    [Fact]
    public void A_user_created_without_dates_gets_a_past_validity_instead_of_a_zeroed_one()
    {
        var created = TrueFaceDeviceAdapter.BuildUser(new DeviceUserMutation("3000", "Shivam Sharma"), freeze: false);
        var snapshot = TrueFaceDeviceAdapter.ToSnapshot(created);

        Assert.Equal(new DateTimeOffset(2018, 1, 1, 0, 0, 0, TimeSpan.FromHours(5.5)), snapshot.ValidFrom);
        Assert.Equal(new DateTimeOffset(2018, 1, 1, 23, 59, 59, TimeSpan.FromHours(5.5)), snapshot.ValidTo);
    }

    [Fact]
    public void A_user_created_with_dates_keeps_them()
    {
        var from = new DateTimeOffset(2026, 10, 8, 0, 0, 0, TimeSpan.FromHours(5.5));
        var to = new DateTimeOffset(2026, 10, 8, 23, 59, 59, TimeSpan.FromHours(5.5));
        var created = TrueFaceDeviceAdapter.BuildUser(new DeviceUserMutation("3001", "Asha",
            ValidFrom: from, ValidTo: to), freeze: false);
        var snapshot = TrueFaceDeviceAdapter.ToSnapshot(created);

        Assert.Equal(8u, created.stuValidBeginTime.dwDay);
        Assert.Equal(0u, created.stuValidBeginTime.dwHour);
        Assert.Equal(8u, created.stuValidEndTime.dwDay);
        Assert.Equal(23u, created.stuValidEndTime.dwHour);
        Assert.Equal(from, snapshot.ValidFrom);
        Assert.Equal(to, snapshot.ValidTo);
    }

    [Fact]
    public void A_wrapped_reader_that_cannot_confirm_absence_is_not_treated_as_deleted()
    {
        var read = new DeviceReaderAdapter(new UnreadableUser()).GetUser("1238");

        Assert.False(read.Ok);
        Assert.NotEqual(FakeReader.FailNoRecord, read.FailCode);
        Assert.Contains("did not confirm", read.Error, StringComparison.Ordinal);
    }

    private sealed class UnreadableUser : IDeviceAdapter
    {
        public string DeviceId => "entry";
        public DeviceConnectionStatus Connect(DeviceConnectionConfig config) => DeviceConnectionStatus.Online();
        public void Disconnect() { }
        public DeviceInfoSnapshot GetDeviceInfo() => new(null, 0, 0, 0, 0, 0);
        public DeviceHealth GetHealth() => new("ONLINE", null, null);
        public DeviceCommandResult CreateUser(DeviceUserMutation mutation) => DeviceCommandResult.Fail("unused");
        public DeviceCommandResult UpdateUser(DeviceUserMutation mutation) => DeviceCommandResult.Fail("unused");
        public DeviceCommandResult DisableUser(string deviceUserId) => DeviceCommandResult.Fail("unused");
        public DeviceCommandResult EnableUser(string deviceUserId) => DeviceCommandResult.Fail("unused");
        public DeviceCommandResult DeleteUser(string deviceUserId) => DeviceCommandResult.Fail("unused");
        public DeviceCommandResult UpdateValidity(DeviceUserMutation mutation) => DeviceCommandResult.Fail("unused");
        public IReadOnlyList<DeviceUserSnapshot> ListUsers() => [];
        public DeviceUserSnapshot? GetUser(string deviceUserId) =>
            throw new DeviceReadException("could not read user 1238; the reader did not confirm that the user is absent");
        public DeviceCommandResult UpsertFace(string deviceUserId, byte[] jpegBytes) => DeviceCommandResult.Fail("unused");
        public DeviceFaceRead GetFace(string deviceUserId) => DeviceFaceRead.Fail("unused");
        public DeviceCommandResult DeleteFace(string deviceUserId) => DeviceCommandResult.Fail("unused");
        public FaceProbeResult ProbeRemoteFaceInsert(string deviceUserId, byte[] jpegBytes) =>
            new(false, 0, "0", null, "unused");
        public IReadOnlyList<DeviceAttendanceRecord> FetchAttendance(DateTimeOffset? fromUtc, DateTimeOffset? toUtc) => [];
        public void RegisterEventListener(IDeviceEventListener listener) { }
        public DeviceCommandResult OpenDoor() => DeviceCommandResult.Fail("unused");
        public DeviceCommandResult CloseDoor() => DeviceCommandResult.Fail("unused");
        public DeviceCommandResult SynchronizeTime(DateTimeOffset utcNow) => DeviceCommandResult.Fail("unused");
        public void Dispose() { }
    }
}
