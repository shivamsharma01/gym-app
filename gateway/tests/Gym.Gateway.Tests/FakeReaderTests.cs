using Gym.Gateway.Adapters;
using Xunit;

namespace Gym.Gateway.Tests;

public class FakeReaderTests
{
    private static readonly DateTimeOffset Begin = new(2026, 10, 6, 0, 0, 0, TimeSpan.Zero);
    private static readonly DateTimeOffset End = new(2026, 10, 7, 23, 59, 59, TimeSpan.Zero);

    [Fact]
    public void Create_stores_the_supplied_id_and_full_record()
    {
        var reader = new FakeReader();
        var user = FullUser("1210");

        var created = reader.CreateUser(user);

        Assert.True(created.Ok);
        var read = reader.GetUser("1210");
        Assert.True(read.Ok);
        Assert.Equal(user, read.User);
        var list = reader.ListUsers();
        Assert.True(list.CountMatchesAnnouncedTotal);
        Assert.Equal(new[] { "1210" }, list.Users.Select(u => u.DeviceUserId).ToArray());
    }

    [Fact]
    public void Create_of_an_occupied_id_does_not_overwrite_or_allocate_another_id()
    {
        var reader = new FakeReader();
        reader.CreateUser(FullUser("1210", name: "Kept"));
        reader.InsertFace("1210", new byte[] { 1, 2, 3 });

        var occupied = reader.CreateUser(FullUser("1210", name: "Replaced"));

        Assert.False(occupied.Ok);
        Assert.Equal(FakeReader.FailOccupied, occupied.FailCode);
        Assert.Equal("Kept", reader.GetUser("1210").User!.Name);
        Assert.Equal(new byte[] { 1, 2, 3 }, reader.GetFace("1210").Bytes);
        Assert.Equal(new[] { "1210" }, reader.ListUsers().Users.Select(u => u.DeviceUserId).ToArray());
    }

    [Fact]
    public void Create_without_an_id_writes_nothing()
    {
        var reader = new FakeReader();

        var created = reader.CreateUser(FullUser("  "));

        Assert.False(created.Ok);
        Assert.Null(created.FailCode);
        Assert.Empty(reader.ListUsers().Users);
    }

    [Fact]
    public void Second_face_insert_returns_photo_exist_and_keeps_the_bytes()
    {
        var reader = new FakeReader();
        reader.CreateUser(FullUser("1210"));
        Assert.True(reader.InsertFace("1210", new byte[] { 9, 9 }).Ok);

        var again = reader.InsertFace("1210", new byte[] { 4, 5 });

        Assert.False(again.Ok);
        Assert.Equal(FakeReader.FailPhotoExist, again.FailCode);
        Assert.Equal(FakeReader.SdkErrorMissingRecord, again.SdkError);
        Assert.Equal(new byte[] { 9, 9 }, reader.GetFace("1210").Bytes);
    }

    [Fact]
    public void Same_face_bytes_may_exist_on_two_ids()
    {
        var reader = new FakeReader();
        var photo = new byte[] { 7, 7, 7 };
        reader.CreateUser(FullUser("1210"));
        reader.CreateUser(FullUser("1211"));

        Assert.True(reader.InsertFace("1210", photo).Ok);
        Assert.True(reader.InsertFace("1211", photo).Ok);

        Assert.Equal(photo, reader.GetFace("1210").Bytes);
        Assert.Equal(photo, reader.GetFace("1211").Bytes);
    }

    [Fact]
    public void Update_with_no_photo_leaves_the_stored_photo()
    {
        var reader = new FakeReader();
        reader.CreateUser(FullUser("1210"));
        reader.InsertFace("1210", new byte[] { 3, 3 });

        Assert.True(reader.UpdateFace("1210", null).Ok);
        Assert.True(reader.UpdateFace("1210", []).Ok);

        Assert.Equal(new byte[] { 3, 3 }, reader.GetFace("1210").Bytes);
    }

    [Fact]
    public void Update_replaces_the_photo()
    {
        var reader = new FakeReader();
        reader.CreateUser(FullUser("1210"));
        reader.InsertFace("1210", new byte[] { 1 });

        Assert.True(reader.UpdateFace("1210", new byte[] { 2, 2 }).Ok);

        Assert.Equal(new byte[] { 2, 2 }, reader.GetFace("1210").Bytes);
    }

    [Fact]
    public void Missing_photo_and_missing_user_share_the_sdk_error_and_differ_by_fail_code()
    {
        var reader = new FakeReader();
        reader.CreateUser(FullUser("1210"));

        var noPhoto = reader.GetFace("1210");
        var noUser = reader.GetUser("9999");
        var faceOfMissingUser = reader.GetFace("9999");

        Assert.False(noPhoto.Ok);
        Assert.Equal(FakeReader.FailUnknown, noPhoto.FailCode);
        Assert.Equal(FakeReader.SdkErrorMissingRecord, noPhoto.SdkError);
        Assert.Null(noPhoto.Bytes);

        Assert.False(noUser.Ok);
        Assert.Equal(FakeReader.FailNoRecord, noUser.FailCode);
        Assert.Equal(FakeReader.SdkErrorMissingRecord, noUser.SdkError);
        Assert.Null(noUser.User);

        Assert.Equal(FakeReader.FailNoRecord, faceOfMissingUser.FailCode);
        Assert.Equal(FakeReader.SdkErrorMissingRecord, faceOfMissingUser.SdkError);
    }

    [Fact]
    public void Removing_a_missing_face_succeeds_and_does_not_remove_the_user()
    {
        var reader = new FakeReader();
        reader.CreateUser(FullUser("1210"));

        Assert.True(reader.RemoveFace("1210").Ok);
        Assert.True(reader.RemoveFace("1210").Ok);
        Assert.True(reader.RemoveFace("missing").Ok);
        Assert.True(reader.GetUser("1210").Ok);
    }

    [Fact]
    public void Face_write_for_an_unknown_user_does_not_create_that_user()
    {
        var reader = new FakeReader();

        Assert.Equal(FakeReader.FailNoRecord, reader.InsertFace("1210", new byte[] { 1 }).FailCode);
        Assert.Equal(FakeReader.FailNoRecord, reader.UpdateFace("1210", new byte[] { 1 }).FailCode);
        Assert.Empty(reader.ListUsers().Users);
    }

    [Fact]
    public void Short_and_empty_lists_do_not_change_the_stored_roster()
    {
        var reader = new FakeReader();
        reader.CreateUser(FullUser("1210"));
        reader.CreateUser(FullUser("1211"));
        reader.ScriptList(1212, FullUser("1210"));
        reader.ScriptList(0);
        reader.ScriptListFailure("read failed");

        var shortList = reader.ListUsers();
        Assert.True(shortList.Ok);
        Assert.False(shortList.CountMatchesAnnouncedTotal);
        Assert.Equal(1212, shortList.AnnouncedTotal);
        Assert.Single(shortList.Users);

        var empty = reader.ListUsers();
        Assert.True(empty.Ok);
        Assert.True(empty.CountMatchesAnnouncedTotal);
        Assert.Equal(0, empty.AnnouncedTotal);
        Assert.Empty(empty.Users);

        var failed = reader.ListUsers();
        Assert.False(failed.Ok);
        Assert.False(failed.CountMatchesAnnouncedTotal);
        Assert.Equal("read failed", failed.Error);

        var stored = reader.ListUsers();
        Assert.True(stored.CountMatchesAnnouncedTotal);
        Assert.Equal(new[] { "1210", "1211" }, stored.Users.Select(u => u.DeviceUserId).ToArray());
    }

    [Fact]
    public void A_failed_call_does_not_change_stored_state()
    {
        var reader = new FakeReader();
        reader.CreateUser(FullUser("1210"));
        reader.InsertFace("1210", new byte[] { 1 });
        reader.ScriptFailure(FakeReaderOperation.CreateUser, "create failed");
        reader.ScriptFailure(FakeReaderOperation.InsertFace, "insert failed");
        reader.ScriptFailure(FakeReaderOperation.GetUser, "get failed");

        Assert.False(reader.CreateUser(FullUser("1211")).Ok);
        Assert.False(reader.InsertFace("1210", new byte[] { 9 }).Ok);
        var failedGet = reader.GetUser("1210");
        Assert.False(failedGet.Ok);
        Assert.Null(failedGet.FailCode);

        Assert.Equal(new[] { "1210" }, reader.ListUsers().Users.Select(u => u.DeviceUserId).ToArray());
        Assert.Equal(new byte[] { 1 }, reader.GetFace("1210").Bytes);
        Assert.True(reader.GetUser("1210").Ok);
    }

    [Fact]
    public void Punch_query_is_a_time_window_and_a_failure_is_not_an_empty_log()
    {
        var reader = new FakeReader();
        var inside = new DateTimeOffset(2026, 10, 7, 12, 0, 0, TimeSpan.Zero);
        reader.AddPunch(new ReaderPunch("1210", inside, 10, "FACE", true, null));
        reader.AddPunch(new ReaderPunch("1210", inside.AddHours(-2), 9, "FACE", false, 0x14));
        reader.AddPunch(new ReaderPunch("1211", inside.AddHours(3), 11, "FACE", false, 0xA4));
        reader.ScriptFailure(FakeReaderOperation.QueryPunches, "query failed");

        var failed = reader.QueryPunches(inside.AddHours(-1), inside.AddHours(1));
        Assert.False(failed.Ok);
        Assert.Empty(failed.Punches);

        var window = reader.QueryPunches(inside, inside);
        Assert.True(window.Ok);
        Assert.Equal(new long[] { 10 }, window.Punches.Select(p => p.RecordNumber).ToArray());
        Assert.Equal("1210", window.Punches[0].DeviceUserId);
    }

    private static ReaderUser FullUser(string deviceUserId, string name = "Asha") =>
        new(deviceUserId, name, name, UserStatus: 0, Begin, End, Authority: "Customer", DoorNum: 1, TimeSectionNum: 1);
}
