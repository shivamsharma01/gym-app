using Gym.Gateway.Adapters;
using Gym.Gateway.Execution;
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
}
