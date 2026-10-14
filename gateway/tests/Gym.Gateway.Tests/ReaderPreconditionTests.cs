using Gym.Gateway.Adapters;
using Gym.Gateway.Execution;
using Xunit;

namespace Gym.Gateway.Tests;

/// <summary>
/// A write or removal at a reader id goes ahead only while the reader shows the record the server
/// expects there, or the record this gateway last wrote there. Anything else is reported to the
/// server, and the person at the id is left as they are.
/// </summary>
public class ReaderPreconditionTests : IDisposable
{
    private readonly string _directory = System.IO.Path.Combine(System.IO.Path.GetTempPath(), "gym-precondition-" + Guid.NewGuid().ToString("N"));
    private static readonly DateTimeOffset ValidFrom = new(2026, 10, 8, 0, 0, 0, TimeSpan.FromHours(5.5));
    private static readonly DateTimeOffset ValidTo = new(2026, 10, 8, 23, 59, 59, TimeSpan.FromHours(5.5));
    private static readonly byte[] Face = [1, 2, 3, 4, 5];
    private static readonly byte[] BobFace = [9, 9, 9];

    public ReaderPreconditionTests() => Directory.CreateDirectory(_directory);

    public void Dispose()
    {
        if (Directory.Exists(_directory))
        {
            Directory.Delete(_directory, true);
        }
    }

    [Fact]
    public async Task A_stale_removal_does_not_delete_a_different_person_now_holding_the_id()
    {
        var reader = new FakeReader();
        var client = new Server { Items = [Item(1, "7", "Asha")] };
        using var worker = Worker(reader, "stale-removal");
        var path = Path(worker, reader, client);
        await path.HandleAsync(Notice(1), CancellationToken.None);

        Assert.True(reader.RemoveUser("7").Ok);
        Enrol(reader, "7", "Bob");
        client.Items = [Item(2, "7", "Asha", present: false, expected: Verified("Asha", Face))];

        await path.HandleAsync(Notice(2), CancellationToken.None);

        Assert.Equal("Bob", reader.GetUser("7").User!.Name);
        Assert.Equal(BobFace, reader.GetFace("7").Bytes);
        var report = Assert.Single(client.Reports);
        Assert.Equal((2L, "7", "Bob"), (report.Revision, report.DeviceUserId, report.Occupant.User!.Name));
        Assert.Equal(1, worker.AppliedRevision);
    }

    [Fact]
    public async Task Having_written_an_id_before_does_not_let_a_write_replace_someone_else_there()
    {
        var reader = new FakeReader();
        var client = new Server { Items = [Item(1, "7", "Asha")] };
        using var worker = Worker(reader, "written-before");
        var path = Path(worker, reader, client);
        await path.HandleAsync(Notice(1), CancellationToken.None);

        Assert.True(reader.RemoveUser("7").Ok);
        Enrol(reader, "7", "Bob");
        client.Items = [Item(2, "7", "Asha Menon", expected: Verified("Asha", Face))];

        await path.HandleAsync(Notice(2), CancellationToken.None);

        Assert.Equal("Bob", reader.GetUser("7").User!.Name);
        Assert.Equal(BobFace, reader.GetFace("7").Bytes);
        Assert.Equal("Bob", Assert.Single(client.Reports).Occupant.User!.Name);
        Assert.Empty(client.Occupied);
    }

    [Fact]
    public async Task A_matching_name_does_not_let_a_new_member_replace_an_unknown_reader_user()
    {
        var reader = new FakeReader();
        Assert.True(reader.CreateUser(Person("9", "Ravi", status: 1)).Ok);
        Assert.True(reader.InsertFace("9", BobFace).Ok);
        var client = new Server { Items = [Item(1, "9", "Ravi")] };
        using var worker = Worker(reader, "same-name");

        await Path(worker, reader, client).HandleAsync(Notice(1), CancellationToken.None);

        Assert.Equal(1, reader.GetUser("9").User!.UserStatus);
        Assert.Equal(BobFace, reader.GetFace("9").Bytes);
        Assert.Equal(["9"], client.Occupied);
        Assert.Empty(client.Reports);
    }

    [Fact]
    public async Task A_link_does_not_write_over_someone_other_than_the_person_staff_saw()
    {
        var reader = new FakeReader();
        Enrol(reader, "12", "Bob");
        var staffSaw = new ExpectedOccupant(true, "Door", null, 0, Text(ValidFrom), Text(ValidTo), "Customer", "");
        var client = new Server { Items = [Item(1, "12", "Priya", expected: staffSaw)] };
        using var worker = Worker(reader, "link-overtaken");

        await Path(worker, reader, client).HandleAsync(Notice(1), CancellationToken.None);

        Assert.Equal("Bob", reader.GetUser("12").User!.Name);
        Assert.Equal("Bob", Assert.Single(client.Reports).Occupant.User!.Name);
    }

    [Fact]
    public async Task A_link_writes_over_the_person_staff_saw()
    {
        var reader = new FakeReader();
        Assert.True(reader.CreateUser(Person("12", "Door")).Ok);
        var staffSaw = new ExpectedOccupant(true, "Door", null, 0, Text(ValidFrom), Text(ValidTo), "Customer", "");
        var client = new Server { Items = [Item(1, "12", "Priya", expected: staffSaw)] };
        using var worker = Worker(reader, "link");

        await Path(worker, reader, client).HandleAsync(Notice(1), CancellationToken.None);

        Assert.Equal("Priya", reader.GetUser("12").User!.Name);
        Assert.Equal(Face, reader.GetFace("12").Bytes);
        Assert.Equal(1, Assert.Single(client.Acks).Revision);
        Assert.Empty(client.Reports);
    }

    [Fact]
    public async Task Reconnect_does_not_overwrite_a_reader_side_freeze()
    {
        var reader = new FakeReader();
        var client = new Server { Items = [Item(1, "1", "Asha")] };
        using var worker = Worker(reader, "freeze");
        var path = Path(worker, reader, client);
        await path.HandleAsync(Notice(1), CancellationToken.None);

        Assert.True(reader.ReplaceUser(Person("1", "Asha", status: 1)).Ok);
        client.Items = [Item(2, "1", "Asha Menon", expected: Verified("Asha", Face))];

        await path.ReconnectAsync(CancellationToken.None);

        Assert.Equal(1, reader.GetUser("1").User!.UserStatus);
        Assert.Equal("Asha", reader.GetUser("1").User!.Name);
        var report = Assert.Single(client.Reports);
        Assert.Equal(1, report.Occupant.User!.UserStatus);
        Assert.Equal(1, worker.AppliedRevision);
    }

    [Fact]
    public async Task A_decision_made_on_what_staff_saw_writes_over_that_reader_edit()
    {
        var reader = new FakeReader();
        var client = new Server { Items = [Item(1, "1", "Asha")] };
        using var worker = Worker(reader, "accept-server");
        var path = Path(worker, reader, client);
        await path.HandleAsync(Notice(1), CancellationToken.None);
        Assert.True(reader.ReplaceUser(Person("1", "Asha At Desk", status: 1)).Ok);

        client.Items = [Item(2, "1", "Asha", expected: Verified("Asha At Desk", Face) with { UserStatus = 1 })];
        await path.HandleAsync(Notice(2), CancellationToken.None);

        Assert.Equal(("Asha", 0), (reader.GetUser("1").User!.Name, reader.GetUser("1").User!.UserStatus));
        Assert.Equal(2, worker.AppliedRevision);
        Assert.Empty(client.Reports);
    }

    [Fact]
    public async Task A_record_this_gateway_wrote_but_never_acknowledged_is_updated_in_place()
    {
        var reader = new FakeReader();
        var client = new Server { Items = [Item(1, "1", "Asha")], FailAcks = 1 };
        using var worker = Worker(reader, "unacked");
        var path = Path(worker, reader, client);
        await Assert.ThrowsAnyAsync<HttpRequestException>(() => path.HandleAsync(Notice(1), CancellationToken.None));

        client.Items = [Item(2, "1", "Asha Menon")];
        await path.HandleAsync(Notice(2), CancellationToken.None);

        var user = Assert.Single(reader.ListUsers().Users);
        Assert.Equal(("1", "Asha Menon"), (user.DeviceUserId, user.Name));
        Assert.Empty(client.Occupied);
        Assert.Empty(client.Reports);
        Assert.Equal(2, worker.AppliedRevision);
    }

    [Fact]
    public async Task A_removal_of_the_expected_record_removes_it()
    {
        var reader = new FakeReader();
        var client = new Server { Items = [Item(1, "1", "Asha")] };
        using var worker = Worker(reader, "removal");
        var path = Path(worker, reader, client);
        await path.HandleAsync(Notice(1), CancellationToken.None);
        Assert.True(reader.ReplaceUser(Person("1", "Asha At Desk")).Ok);

        client.Items = [Item(2, "1", "Asha", present: false, expected: Verified("Asha At Desk", Face))];
        await path.HandleAsync(Notice(2), CancellationToken.None);

        Assert.Equal(FakeReader.FailNoRecord, reader.GetUser("1").FailCode);
        Assert.Equal(2, worker.AppliedRevision);
    }

    [Fact]
    public async Task Clearing_the_photo_of_a_user_deleted_at_the_reader_is_reported_and_later_users_still_apply()
    {
        var reader = new FakeReader();
        var client = new Server { Items = [Item(1, "1", "Asha")] };
        using var worker = Worker(reader, "clear-deleted");
        var path = Path(worker, reader, client);
        await path.HandleAsync(Notice(1), CancellationToken.None);
        Assert.True(reader.RemoveUser("1").Ok);

        client.Items = [Item(2, "1", "Asha", facePresent: false, expected: Verified("Asha", Face)), Item(3, "2", "Ravi")];
        await path.HandleAsync(Notice(3), CancellationToken.None);

        Assert.False(Assert.Single(client.Reports).Occupant.Present);
        Assert.Equal(FakeReader.FailNoRecord, reader.GetUser("1").FailCode);
        Assert.Equal("Ravi", reader.GetUser("2").User!.Name);
        Assert.Null(worker.Retry);
    }

    [Fact]
    public async Task An_unacknowledged_write_whose_row_the_server_dropped_does_not_stall_the_reader()
    {
        var reader = new FakeReader();
        var client = new Server { Items = [Item(1, "1", "Asha")], FailAcks = 1 };
        using var worker = Worker(reader, "dropped");
        var path = Path(worker, reader, client);
        await Assert.ThrowsAnyAsync<HttpRequestException>(() => path.HandleAsync(Notice(1), CancellationToken.None));

        client.Items = [];
        client.Desired = 1;
        await path.HandleAsync(Notice(1), CancellationToken.None);

        Assert.Empty(worker.PendingAcks);
        Assert.Empty(client.Acks);
        Assert.Equal("Asha", reader.GetUser("1").User!.Name);
    }

    private ReaderWorker Worker(FakeReader reader, string name) =>
        new("r", reader, System.IO.Path.Combine(_directory, name + ".sqlite"), TimeSpan.Zero);

    private static DesiredRevisionPath Path(ReaderWorker worker, FakeReader reader, Server client) =>
        new("r", worker, reader, client);

    private static DesiredRevisionNotice Notice(long revision) => new("r", revision);

    private static string Text(DateTimeOffset value) => ReaderLocalTime.Format(value);

    private static ExpectedOccupant Verified(string name, byte[] face) =>
        new(true, name, null, 0, Text(ValidFrom), Text(ValidTo), "Customer", FaceHash.Sha256Hex(face));

    private static DesiredPullItem Item(long revision, string id, string name, bool present = true,
        bool facePresent = true, ExpectedOccupant? expected = null) => new(
        revision, id, name, null, 0, Text(ValidFrom), Text(ValidTo), "Customer", 1, 1,
        present && facePresent ? Face : [], present, facePresent, expected ?? ExpectedOccupant.Absent);

    private static ReaderUser Person(string id, string name, int status = 0) =>
        new(id, name, null, status, ValidFrom, ValidTo, "Customer", 1, 1);

    private static void Enrol(FakeReader reader, string id, string name)
    {
        Assert.True(reader.CreateUser(Person(id, name)).Ok);
        Assert.True(reader.InsertFace(id, BobFace).Ok);
    }

    private sealed record Report(long Revision, string DeviceUserId, ReaderOccupant Occupant);

    /// <summary>
    /// The server side of the pull. A report holds or releases the row, so it is not pulled again,
    /// as the backend does.
    /// </summary>
    private sealed class Server : IDesiredStateClient
    {
        public List<DesiredPullItem> Items { get; set; } = [];
        public List<string> Occupied { get; } = [];
        public List<Report> Reports { get; } = [];
        public List<DesiredAcknowledgement> Acks { get; } = [];
        public int FailAcks { get; set; }
        public long? Desired { get; set; }

        public Task<DesiredPull> PullAsync(string deviceId, long after, CancellationToken cancellationToken)
        {
            var items = Items.Where(item => item.Revision > after).ToList();
            var desired = Desired ?? (Items.Count == 0 ? 0 : Items.Max(item => item.Revision));
            return Task.FromResult(new DesiredPull(desired, after, items));
        }

        public Task AcknowledgeAsync(DesiredAcknowledgement ack, CancellationToken cancellationToken)
        {
            if (FailAcks > 0)
            {
                FailAcks--;
                throw new HttpRequestException("network down");
            }

            Acks.Add(ack);
            return Task.CompletedTask;
        }

        public Task ReportOccupiedAsync(string deviceId, long revision, string deviceUserId, CancellationToken cancellationToken)
        {
            Occupied.Add(deviceUserId);
            Items = Items.Where(item => item.Revision != revision).ToList();
            return Task.CompletedTask;
        }

        public Task<bool> ObservationProcessedAsync(string deviceId, IReadOnlyList<string> messageIds,
            CancellationToken cancellationToken) => Task.FromResult(true);

        public Task ReportFailedAsync(string deviceId, RetryState attempt, CancellationToken cancellationToken) =>
            Task.CompletedTask;

        public Task ReportOccupantAsync(string deviceId, long revision, string deviceUserId, ReaderOccupant occupant,
            CancellationToken cancellationToken)
        {
            Reports.Add(new Report(revision, deviceUserId, occupant));
            Items = Items.Where(item => item.Revision != revision).ToList();
            return Task.CompletedTask;
        }
    }
}
