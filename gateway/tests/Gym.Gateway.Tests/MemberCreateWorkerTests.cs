using System.Security.Cryptography;
using Gym.Gateway.Adapters;
using Gym.Gateway.Execution;
using Gym.Gateway;
using Xunit;

namespace Gym.Gateway.Tests;

public class MemberCreateWorkerTests : IDisposable
{
    private readonly string _directory = Path.Combine(Path.GetTempPath(), "gym-v1-" + Guid.NewGuid().ToString("N"));
    private static readonly DateTimeOffset ValidFrom = new(2026, 10, 8, 0, 0, 0, TimeSpan.FromHours(5.5));
    private static readonly DateTimeOffset ValidTo = new(2026, 10, 8, 23, 59, 59, TimeSpan.FromHours(5.5));
    private static readonly byte[] Face = [1, 2, 3, 4, 5];

    public MemberCreateWorkerTests()
    {
        Directory.CreateDirectory(_directory);
    }

    [Fact]
    public void Create_reads_back_then_acks_and_restart_does_not_create_again()
    {
        const string publicId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        var reader = new FakeReader();
        var journal = Path.Combine(_directory, "reader.sqlite");
        var desired = Member("1", nameEx: "Asha Test needs the long name field");

        using (var worker = Start(journal, reader))
        {
            var result = worker.ApplyMember(desired);
            Assert.Equal(MemberApplyKind.Applied, result.Kind);
            Assert.Equal(1, worker.AppliedRevision);
            Assert.Equal(new[] { 1L }, worker.PendingAcks.Select(ack => ack.Revision).ToArray());
        }

        var user = reader.GetUser("1").User;
        Assert.NotNull(user);
        Assert.Equal("1", user.DeviceUserId);
        Assert.Equal("Asha", user.Name);
        Assert.Equal("Asha Test needs the long name field", user.NameEx);
        Assert.Equal(0, user.UserStatus);
        Assert.Equal(ValidFrom, user.ValidFrom);
        Assert.Equal(ValidTo, user.ValidTo);
        Assert.Equal("Customer", user.Authority);
        Assert.Equal(1, user.DoorNum);
        Assert.Equal(1, user.TimeSectionNum);
        Assert.Equal(Face, reader.GetFace("1").Bytes);
        Assert.Equal(new[] { "CreateUser 1", "InsertFace 1" }, reader.Writes);
        Assert.DoesNotContain(reader.Writes, line => line.Contains(publicId, StringComparison.Ordinal));
        Assert.NotEqual(publicId, user.DeviceUserId);
        Assert.DoesNotContain(publicId, user.Name ?? "", StringComparison.Ordinal);
        Assert.DoesNotContain(publicId, user.NameEx ?? "", StringComparison.Ordinal);

        using var restarted = Start(journal, reader);
        Assert.Equal(MemberApplyKind.AlreadyApplied, restarted.ApplyMember(desired).Kind);
        Assert.Equal(new[] { "CreateUser 1", "InsertFace 1" }, reader.Writes);
    }

    [Fact]
    public void Occupied_id_is_not_overwritten_and_the_next_revision_uses_the_server_id()
    {
        var reader = new FakeReader();
        reader.CreateUser(new ReaderUser("1", "Already there", null, 0, ValidFrom, ValidTo, "Customer", 1, 1));
        var journal = Path.Combine(_directory, "occupied.sqlite");
        using var worker = Start(journal, reader);

        var occupied = worker.ApplyMember(Member("1", nameEx: null));
        Assert.Equal(MemberApplyKind.Occupied, occupied.Kind);
        Assert.Equal("1", occupied.Detail);
        Assert.Equal(0, worker.AppliedRevision);
        Assert.Empty(worker.PendingAcks);
        Assert.Equal("Already there", reader.GetUser("1").User!.Name);
        Assert.Equal(new[] { "CreateUser 1" }, reader.Writes);

        var retry = worker.ApplyMember(Member("2", nameEx: null));
        Assert.Equal(MemberApplyKind.Applied, retry.Kind);
        Assert.Equal("Already there", reader.GetUser("1").User!.Name);
        Assert.Equal("Asha", reader.GetUser("2").User!.Name);
        Assert.Equal(new[] { "CreateUser 1", "CreateUser 2", "InsertFace 2" }, reader.Writes);
    }

    [Fact]
    public void Link_replaces_the_different_name_and_keeps_the_reader_id()
    {
        var reader = new FakeReader();
        reader.CreateUser(new ReaderUser("12", "Door", null, 0, ValidFrom, ValidTo, "Customer", 1, 1));
        using var worker = Start(Path.Combine(_directory, "link.sqlite"), reader);

        var linked = new DesiredMember(
            3,
            "12",
            "Ria Shah",
            null,
            0,
            ValidFrom,
            ValidTo,
            "Customer",
            1,
            1,
            Face,
            true);
        var result = worker.ApplyMember(linked);

        Assert.Equal(MemberApplyKind.Applied, result.Kind);
        Assert.Equal("12", reader.GetUser("12").User!.DeviceUserId);
        Assert.Equal("Ria Shah", reader.GetUser("12").User!.Name);
        Assert.Equal(new[] { "CreateUser 12", "ReplaceUser 12", "InsertFace 12" }, reader.Writes);
        Assert.DoesNotContain(reader.Writes, write => write.Contains("CreateUser 13") || write == "CreateUser 1");
    }

    [Fact]
    public void Face_hash_mismatch_does_not_ack()
    {
        var reader = new FakeReader();
        reader.ScriptFaceReadBack([9, 9, 9]);
        using var worker = Start(Path.Combine(_directory, "mismatch.sqlite"), reader);

        var result = worker.ApplyMember(Member("1", nameEx: null));
        Assert.Equal(MemberApplyKind.Failed, result.Kind);
        Assert.Equal(0, worker.AppliedRevision);
        Assert.Empty(worker.PendingAcks);
        Assert.Equal("read-back mismatch", worker.Retry!.LastError);
        Assert.Equal(new[] { "CreateUser 1", "InsertFace 1" }, reader.Writes);
    }

    [Fact]
    public void Face_insert_failure_leaves_the_user_and_the_retry_inserts_the_face()
    {
        var reader = new FakeReader();
        reader.ScriptFailure(FakeReaderOperation.InsertFace, "failed");
        using var worker = Start(Path.Combine(_directory, "face-retry.sqlite"), reader);
        var desired = Member("1", nameEx: null);

        var failed = worker.ApplyMember(desired);
        Assert.Equal(MemberApplyKind.Failed, failed.Kind);
        Assert.Equal(0, worker.AppliedRevision);
        Assert.Empty(worker.PendingAcks);
        Assert.NotNull(reader.GetUser("1").User);
        Assert.False(reader.GetFace("1").Ok);
        Assert.Equal(new[] { "CreateUser 1" }, reader.Writes);

        var retried = worker.ApplyMember(desired);
        Assert.Equal(MemberApplyKind.Applied, retried.Kind);
        Assert.Equal(Face, reader.GetFace("1").Bytes);
        Assert.Equal(new[] { "CreateUser 1", "InsertFace 1" }, reader.Writes);
    }

    [Fact]
    public void Freeze_and_enable_keep_the_same_id_and_face()
    {
        var reader = new FakeReader();
        using var worker = Start(Path.Combine(_directory, "freeze.sqlite"), reader);
        var created = Member("1", nameEx: null);
        Assert.Equal(MemberApplyKind.Applied, worker.ApplyMember(created).Kind);
        var face = reader.GetFace("1").Bytes;

        var frozen = created with { Revision = 2, UserStatus = 1 };
        Assert.Equal(MemberApplyKind.Applied, worker.ApplyMember(frozen).Kind);
        Assert.Equal(1, reader.GetUser("1").User!.UserStatus);
        Assert.Equal("1", reader.GetUser("1").User!.DeviceUserId);
        Assert.Equal(face, reader.GetFace("1").Bytes);
        Assert.Equal(ValidFrom, reader.GetUser("1").User!.ValidFrom);
        Assert.Equal(ValidTo, reader.GetUser("1").User!.ValidTo);

        var enabled = created with { Revision = 3, UserStatus = 0 };
        Assert.Equal(MemberApplyKind.Applied, worker.ApplyMember(enabled).Kind);
        Assert.Equal(0, reader.GetUser("1").User!.UserStatus);
        Assert.Equal(face, reader.GetFace("1").Bytes);
        Assert.Equal(
            new[] { "CreateUser 1", "InsertFace 1", "ReplaceUser 1", "ReplaceUser 1" },
            reader.Writes);
        Assert.DoesNotContain(reader.Writes, line => line.Contains("Delete", StringComparison.Ordinal));
    }

    [Fact]
    public void Status_read_back_still_enabled_does_not_ack_and_retries()
    {
        var reader = new FakeReader();
        using var worker = Start(Path.Combine(_directory, "held.sqlite"), reader);
        var created = Member("1", nameEx: null);
        Assert.Equal(MemberApplyKind.Applied, worker.ApplyMember(created).Kind);
        var face = reader.GetFace("1").Bytes;

        reader.ScriptReportedStatus(0);
        var frozen = created with { Revision = 2, UserStatus = 1 };
        Assert.Equal(MemberApplyKind.Failed, worker.ApplyMember(frozen).Kind);
        Assert.Equal(1, worker.AppliedRevision);
        Assert.Equal(new[] { 1L }, worker.PendingAcks.Select(ack => ack.Revision));
        Assert.Equal(2, worker.Retry!.Revision);
        Assert.Equal(face, reader.GetFace("1").Bytes);
        Assert.Equal("1", reader.GetUser("1").User!.DeviceUserId);

        reader.ScriptReportedStatus(null);
        Assert.Equal(MemberApplyKind.Applied, worker.ApplyMember(frozen).Kind);
        Assert.Equal(2, worker.AppliedRevision);
        Assert.Equal(1, reader.GetUser("1").User!.UserStatus);
        Assert.Equal(face, reader.GetFace("1").Bytes);
    }

    [Fact]
    public void Name_and_dates_replace_the_same_user_and_keep_the_face()
    {
        const string full = "Priya Nandini Kapoor the reader name";
        var shown = full[..31];
        var from = new DateTimeOffset(2026, 11, 1, 0, 0, 0, TimeSpan.FromHours(5.5));
        var to = new DateTimeOffset(2027, 1, 20, 23, 59, 59, TimeSpan.FromHours(5.5));
        var reader = new FakeReader();
        var journal = Path.Combine(_directory, "rename.sqlite");
        var created = Member("1", nameEx: null);

        using (var worker = Start(journal, reader))
        {
            Assert.Equal(MemberApplyKind.Applied, worker.ApplyMember(created).Kind);
        }

        var face = reader.GetFace("1").Bytes;
        var renamed = created with
        {
            Revision = 2,
            Name = shown,
            NameEx = full,
            ValidFrom = from,
            ValidTo = to
        };
        using (var worker = Start(journal, reader))
        {
            Assert.Equal(MemberApplyKind.Applied, worker.ApplyMember(renamed).Kind);
            Assert.Equal(2, worker.AppliedRevision);
        }

        var user = reader.GetUser("1").User;
        Assert.NotNull(user);
        Assert.Equal("1", user.DeviceUserId);
        Assert.Equal(shown, user.Name);
        Assert.Equal(full, user.NameEx);
        Assert.Equal(from, user.ValidFrom);
        Assert.Equal(to, user.ValidTo);
        Assert.Equal(TimeSpan.FromHours(5.5), user.ValidFrom!.Value.Offset);
        Assert.Equal(23, user.ValidTo!.Value.Hour);
        Assert.Equal(59, user.ValidTo.Value.Minute);
        Assert.Equal(59, user.ValidTo.Value.Second);
        Assert.Equal(face, reader.GetFace("1").Bytes);
        Assert.False(reader.GetUser("2").Ok);
        Assert.Equal(new[] { "CreateUser 1", "InsertFace 1", "ReplaceUser 1" }, reader.Writes);
        Assert.DoesNotContain(reader.Writes, line => line.Contains("SynchronizeTime", StringComparison.Ordinal));
        Assert.DoesNotContain(reader.Writes, line => line.Contains("Delete", StringComparison.Ordinal));
    }

    [Fact]
    public void A_user_write_without_validity_makes_no_adapter_calls()
    {
        var reader = new FakeReader();
        reader.ScriptFailure(FakeReaderOperation.GetUser, "should not be called");
        reader.ScriptFailure(FakeReaderOperation.CreateUser, "should not be called");
        reader.ScriptFailure(FakeReaderOperation.GetFace, "should not be called");
        reader.ScriptFailure(FakeReaderOperation.InsertFace, "should not be called");
        using var worker = Start(Path.Combine(_directory, "partial.sqlite"), reader);

        var result = worker.ApplyMember(Member("1", nameEx: null) with { ValidFrom = null, ValidTo = null });

        Assert.Equal(MemberApplyKind.Failed, result.Kind);
        Assert.Equal(0, worker.AppliedRevision);
        Assert.Empty(worker.PendingAcks);
        Assert.Empty(reader.Writes);
        Assert.Equal("should not be called", reader.GetUser("1").Error);
        Assert.Equal(
            "should not be called",
            reader.CreateUser(new ReaderUser("9", "X", null, 0, ValidFrom, ValidTo, "Customer", 1, 1)).Error);
        Assert.Empty(reader.Writes);
    }

    [Fact]
    public void Fake_reader_zeros_omitted_validity_and_keeps_the_face()
    {
        var reader = new FakeReader();
        var face = new byte[] { 9, 8, 7 };
        Assert.True(reader.CreateUser(new ReaderUser("1", "Asha", null, 0, null, ValidTo, "Customer", 1, 1)).Ok);
        Assert.True(reader.InsertFace("1", face).Ok);
        var created = reader.GetUser("1").User;
        Assert.NotNull(created);
        Assert.Null(created.ValidFrom);
        Assert.Null(created.ValidTo);

        Assert.True(reader.ReplaceUser(new ReaderUser(
            "1", "Asha Renamed", "Asha Renamed needs the long field", 0, null, null, "Customer", 1, 1)).Ok);
        var replaced = reader.GetUser("1").User;
        Assert.NotNull(replaced);
        Assert.Equal("Asha Renamed", replaced.Name);
        Assert.Equal("Asha Renamed needs the long field", replaced.NameEx);
        Assert.Null(replaced.ValidFrom);
        Assert.Null(replaced.ValidTo);
        Assert.Equal(face, reader.GetFace("1").Bytes);
    }

    [Fact]
    public void Date_read_back_that_differs_does_not_ack_and_retries()
    {
        var reader = new FakeReader();
        using var worker = Start(Path.Combine(_directory, "dates.sqlite"), reader);
        var created = Member("1", nameEx: null);
        Assert.Equal(MemberApplyKind.Applied, worker.ApplyMember(created).Kind);
        var face = reader.GetFace("1").Bytes;
        var shifted = created with
        {
            Revision = 2,
            ValidTo = new DateTimeOffset(2026, 12, 31, 23, 59, 59, TimeSpan.FromHours(5.5))
        };

        reader.ScriptReportedValidity(ValidFrom, ValidTo);
        Assert.Equal(MemberApplyKind.Failed, worker.ApplyMember(shifted).Kind);
        Assert.Equal(1, worker.AppliedRevision);
        Assert.Equal(new[] { 1L }, worker.PendingAcks.Select(ack => ack.Revision).ToArray());
        Assert.Equal(2, worker.Retry!.Revision);
        Assert.Equal("read-back mismatch", worker.Retry.LastError);
        Assert.Equal(face, reader.GetFace("1").Bytes);
        Assert.DoesNotContain(reader.Writes, line => line.Contains("SynchronizeTime", StringComparison.Ordinal));

        reader.ClearReportedValidity();
        Assert.Equal(MemberApplyKind.Applied, worker.ApplyMember(shifted).Kind);
        Assert.Equal(2, worker.AppliedRevision);
        Assert.Equal(shifted.ValidTo, reader.GetUser("1").User!.ValidTo);
        Assert.Equal(face, reader.GetFace("1").Bytes);
    }

    [Fact]
    public void Face_replace_read_back_hash_matches_and_acks()
    {
        var reader = new FakeReader();
        var replacement = new byte[] { 9, 8, 7, 6, 5 };
        using var worker = Start(Path.Combine(_directory, "face-ok.sqlite"), reader);
        Assert.Equal(MemberApplyKind.Applied, worker.ApplyMember(Member("1", null)).Kind);

        var next = Member("1", null) with { Revision = 2, Face = replacement };
        Assert.Equal(MemberApplyKind.Applied, worker.ApplyMember(next).Kind);
        Assert.Equal(replacement, reader.GetFace("1").Bytes);
        Assert.Equal(SHA256.HashData(replacement), SHA256.HashData(reader.GetFace("1").Bytes!));
        Assert.Equal(2, worker.AppliedRevision);
        Assert.Equal(new[] { 1L, 2L }, worker.PendingAcks.Select(ack => ack.Revision).ToArray());
        Assert.Equal(["CreateUser 1", "InsertFace 1", "UpdateFace 1"], reader.Writes);
        Assert.Equal("1", reader.GetUser("1").User!.DeviceUserId);
    }

    [Fact]
    public void Photo_exist_does_not_ack_and_leaves_the_applied_revision_behind()
    {
        var reader = new FakeReader();
        var replacement = new byte[] { 9, 8, 7, 6, 5 };
        using var worker = Start(Path.Combine(_directory, "face-exist.sqlite"), reader);
        Assert.Equal(MemberApplyKind.Applied, worker.ApplyMember(Member("1", null)).Kind);
        reader.ScriptFailure(FakeReaderOperation.UpdateFace, "update failed");
        reader.ScriptFailure(FakeReaderOperation.RemoveFace, "remove failed");

        var next = Member("1", null) with { Revision = 2, Face = replacement };
        Assert.Equal(MemberApplyKind.Failed, worker.ApplyMember(next).Kind);
        Assert.Equal(1, worker.AppliedRevision);
        Assert.Equal(new[] { 1L }, worker.PendingAcks.Select(ack => ack.Revision).ToArray());
        Assert.Equal(2, worker.Retry!.Revision);
        Assert.Equal(FakeReader.FailPhotoExist, worker.Retry.LastError);
        Assert.Equal(Face, reader.GetFace("1").Bytes);
        Assert.Equal(["CreateUser 1", "InsertFace 1"], reader.Writes);
    }

    [Fact]
    public void Empty_update_keeps_the_previous_bytes()
    {
        var reader = new FakeReader();
        using var worker = Start(Path.Combine(_directory, "face-empty.sqlite"), reader);
        Assert.Equal(MemberApplyKind.Applied, worker.ApplyMember(Member("1", null)).Kind);

        Assert.True(reader.UpdateFace("1", []).Ok);
        Assert.True(reader.UpdateFace("1", null).Ok);
        Assert.Equal(Face, reader.GetFace("1").Bytes);
        Assert.DoesNotContain(reader.Writes, line => line.StartsWith("RemoveFace", StringComparison.Ordinal));
        Assert.Equal(["CreateUser 1", "InsertFace 1"], reader.Writes);
    }

    [Fact]
    public void Reencoded_face_read_back_does_not_ack()
    {
        var reader = new FakeReader();
        using var worker = Start(Path.Combine(_directory, "face-reencode.sqlite"), reader);
        Assert.Equal(MemberApplyKind.Applied, worker.ApplyMember(Member("1", null)).Kind);
        reader.ScriptFaceReadBack([4, 4, 4, 4]);

        var next = Member("1", null) with { Revision = 2, Face = [9, 8, 7, 6, 5] };
        Assert.Equal(MemberApplyKind.Failed, worker.ApplyMember(next).Kind);
        Assert.Equal(1, worker.AppliedRevision);
        Assert.Equal(new[] { 1L }, worker.PendingAcks.Select(ack => ack.Revision).ToArray());
        Assert.Equal("read-back mismatch", worker.Retry!.LastError);
    }

    [Fact]
    public void Remove_acks_only_when_get_user_is_no_record_and_a_second_remove_is_idempotent()
    {
        var reader = new FakeReader();
        using var worker = Start(Path.Combine(_directory, "remove-ok.sqlite"), reader);
        Assert.Equal(MemberApplyKind.Applied, worker.ApplyMember(Member("1", null)).Kind);

        Assert.Equal(MemberApplyKind.Applied, worker.ApplyRemoval(2, "1").Kind);
        Assert.Equal(FakeReader.FailNoRecord, reader.GetUser("1").FailCode);
        Assert.Equal(FakeReader.SdkErrorMissingRecord, reader.GetUser("1").SdkError);
        Assert.Equal(2, worker.AppliedRevision);
        Assert.Equal(new[] { 1L, 2L }, worker.PendingAcks.Select(ack => ack.Revision).ToArray());

        Assert.Equal(MemberApplyKind.Applied, worker.ApplyRemoval(3, "1").Kind);
        Assert.Equal(3, worker.AppliedRevision);
        Assert.Equal(FakeReader.FailNoRecord, reader.GetUser("1").FailCode);
        Assert.Equal(["CreateUser 1", "InsertFace 1", "RemoveUser 1", "RemoveUser 1"], reader.Writes);
        Assert.DoesNotContain(reader.Writes, line => line.StartsWith("ReplaceUser ", StringComparison.Ordinal));
    }

    [Fact]
    public void Remove_that_leaves_the_user_does_not_ack()
    {
        var reader = new FakeReader();
        using var worker = Start(Path.Combine(_directory, "remove-kept.sqlite"), reader);
        Assert.Equal(MemberApplyKind.Applied, worker.ApplyMember(Member("1", null)).Kind);
        Assert.True(reader.RemoveFace("1").Ok);
        reader.ScriptRemoveKeepsUser();

        Assert.Equal(MemberApplyKind.Failed, worker.ApplyRemoval(2, "1").Kind);
        Assert.Equal(1, worker.AppliedRevision);
        Assert.Equal(new[] { 1L }, worker.PendingAcks.Select(ack => ack.Revision).ToArray());
        Assert.Equal("user still present", worker.Retry!.LastError);
        Assert.True(reader.GetUser("1").Ok);
        Assert.Equal(FakeReader.FailUnknown, reader.GetFace("1").FailCode);
        Assert.Equal(FakeReader.SdkErrorMissingRecord, reader.GetFace("1").SdkError);
    }

    [Fact]
    public void Sdk_error_without_no_record_does_not_ack_absence()
    {
        var reader = new FakeReader();
        using var worker = Start(Path.Combine(_directory, "remove-unknown.sqlite"), reader);
        Assert.Equal(MemberApplyKind.Applied, worker.ApplyMember(Member("1", null)).Kind);
        reader.ScriptUnknownUser();

        Assert.Equal(MemberApplyKind.Failed, worker.ApplyRemoval(2, "1").Kind);
        Assert.Equal(1, worker.AppliedRevision);
        Assert.Equal(new[] { 1L }, worker.PendingAcks.Select(ack => ack.Revision).ToArray());
        Assert.Equal(FakeReader.FailUnknown, worker.Retry!.LastError);
        var read = reader.GetUser("1");
        Assert.Equal(FakeReader.FailUnknown, read.FailCode);
        Assert.Equal(FakeReader.SdkErrorMissingRecord, read.SdkError);
        Assert.NotEqual(FakeReader.FailNoRecord, read.FailCode);
    }

    [Fact]
    public async Task Clearing_a_face_removes_it_acks_after_read_back_and_leaves_the_other_user()
    {
        var reader = new FakeReader();
        using var worker = Start(Path.Combine(_directory, "clear-face.sqlite"), reader);
        Assert.Equal(MemberApplyKind.Applied, worker.ApplyMember(Member("1", null)).Kind);
        reader.CreateUser(new ReaderUser("9", "Other", null, 0, ValidFrom, ValidTo, "Customer", 1, 1));
        reader.InsertFace("9", [9, 9, 9]);

        var item = ClearItem(2);
        var client = new RecordingDesired(item);
        var path = new DesiredRevisionPath("reader-1", worker, reader, client);
        await path.HandleAsync(new DesiredRevisionNotice("reader-1", 2), CancellationToken.None);

        Assert.Equal(2, worker.AppliedRevision);
        Assert.True(reader.GetUser("1").Ok);
        Assert.Equal(FakeReader.FailUnknown, reader.GetFace("1").FailCode);
        Assert.Equal(new byte[] { 9, 9, 9 }, reader.GetFace("9").Bytes);
        Assert.Contains("RemoveFace 1", reader.Writes);
        Assert.DoesNotContain(reader.Writes, write => write.StartsWith("RemoveUser") || write == "RemoveFace 9");
        var ack = Assert.Single(client.Acknowledgements);
        Assert.Equal("", ack.FaceSha256);
        Assert.Equal(FakeReader.FailUnknown, ack.FailCode);
        Assert.True(ack.Present);
        Assert.Equal("1", ack.DeviceUserId);
    }

    [Fact]
    public void An_already_missing_face_is_removed_again_and_the_user_stays()
    {
        var reader = new FakeReader();
        using var worker = Start(Path.Combine(_directory, "clear-missing.sqlite"), reader);
        Assert.Equal(MemberApplyKind.Applied, worker.ApplyMember(Member("1", null)).Kind);
        Assert.True(reader.RemoveFace("1").Ok);

        Assert.Equal(MemberApplyKind.Applied, worker.ApplyMember(Cleared(2)).Kind);

        Assert.Equal(2, worker.AppliedRevision);
        Assert.True(reader.GetUser("1").Ok);
        Assert.Equal(FakeReader.FailUnknown, reader.GetFace("1").FailCode);
        Assert.Equal(2, reader.Writes.Count(write => write == "RemoveFace 1"));
    }

    [Fact]
    public void A_missing_user_is_not_created_in_order_to_clear_a_face()
    {
        var reader = new FakeReader();
        using var worker = Start(Path.Combine(_directory, "clear-absent-user.sqlite"), reader);

        Assert.Equal(MemberApplyKind.Failed, worker.ApplyMember(Cleared(1)).Kind);

        Assert.Equal(0, worker.AppliedRevision);
        Assert.Empty(reader.ListUsers().Users);
        Assert.DoesNotContain(reader.Writes, write => write.StartsWith("CreateUser") || write.StartsWith("RemoveFace"));
    }

    [Fact]
    public void Face_read_back_that_still_has_the_photo_is_not_acked_and_retries()
    {
        var reader = new FakeReader();
        using var worker = Start(Path.Combine(_directory, "clear-verify.sqlite"), reader);
        Assert.Equal(MemberApplyKind.Applied, worker.ApplyMember(Member("1", null)).Kind);
        reader.RetainFaceOnRemove = true;

        Assert.Equal(MemberApplyKind.Failed, worker.ApplyMember(Cleared(2)).Kind);
        Assert.Equal(1, worker.AppliedRevision);
        Assert.Equal(Face, reader.GetFace("1").Bytes);
        Assert.Equal("face still present", worker.Retry!.LastError);

        reader.RetainFaceOnRemove = false;
        Assert.Equal(MemberApplyKind.Applied, worker.ApplyMember(Cleared(2)).Kind);
        Assert.Equal(2, worker.AppliedRevision);
        Assert.True(reader.GetUser("1").Ok);
        Assert.Equal(FakeReader.FailUnknown, reader.GetFace("1").FailCode);
    }

    [Fact]
    public void A_failed_face_remove_retries_without_removing_the_user()
    {
        var reader = new FakeReader();
        using var worker = Start(Path.Combine(_directory, "clear-retry.sqlite"), reader);
        Assert.Equal(MemberApplyKind.Applied, worker.ApplyMember(Member("1", null)).Kind);
        reader.ScriptFailure(FakeReaderOperation.RemoveFace, "remove failed");

        Assert.Equal(MemberApplyKind.Failed, worker.ApplyMember(Cleared(2)).Kind);
        Assert.Equal(1, worker.AppliedRevision);
        Assert.Equal(Face, reader.GetFace("1").Bytes);
        Assert.Equal("remove failed", worker.Retry!.LastError);
        Assert.True(reader.GetUser("1").Ok);

        Assert.Equal(MemberApplyKind.Applied, worker.ApplyMember(Cleared(2)).Kind);
        Assert.Equal(2, worker.AppliedRevision);
        Assert.True(reader.GetUser("1").Ok);
        Assert.Equal(FakeReader.FailUnknown, reader.GetFace("1").FailCode);
    }

    public void Dispose()
    {
        if (Directory.Exists(_directory))
        {
            Directory.Delete(_directory, true);
        }
    }

    private ReaderWorker Start(string journal, FakeReader reader)
    {
        return new ReaderWorker("reader-1", reader, journal, TimeSpan.Zero);
    }

    private static DesiredMember Member(string deviceUserId, string? nameEx)
    {
        return new DesiredMember(
            deviceUserId == "2" ? 2 : 1,
            deviceUserId,
            "Asha",
            nameEx,
            0,
            ValidFrom,
            ValidTo,
            "Customer",
            1,
            1,
            Face);
    }

    private static DesiredMember Cleared(long revision) =>
        Member("1", null) with { Revision = revision, Face = [], FacePresent = false };

    private static DesiredPullItem ClearItem(long revision) => new(
        revision,
        "1",
        "Asha",
        null,
        0,
        ReaderLocalTime.Format(ValidFrom),
        ReaderLocalTime.Format(ValidTo),
        "Customer",
        1,
        1,
        [],
        true,
        false,
        false);

    private sealed class RecordingDesired(DesiredPullItem item) : IDesiredStateClient
    {
        public List<DesiredAcknowledgement> Acknowledgements { get; } = [];

        public Task<DesiredPull> PullAsync(string deviceId, long after, CancellationToken cancellationToken)
        {
            var items = item.Revision > after ? new List<DesiredPullItem> { item } : [];
            return Task.FromResult(new DesiredPull(item.Revision, after, items));
        }

        public Task AcknowledgeAsync(DesiredAcknowledgement ack, CancellationToken cancellationToken)
        {
            Acknowledgements.Add(ack);
            return Task.CompletedTask;
        }

        public Task ReportOccupiedAsync(
            string deviceId, long revision, string deviceUserId, CancellationToken cancellationToken) =>
            Task.CompletedTask;
    }
}
