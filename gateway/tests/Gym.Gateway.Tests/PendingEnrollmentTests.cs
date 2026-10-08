using Gym.Gateway.Adapters;
using Gym.Gateway.Execution;
using Xunit;

namespace Gym.Gateway.Tests;

public class PendingEnrollmentTests : IDisposable
{
    private readonly string _directory = Path.Combine(Path.GetTempPath(), "gym-v8-" + Guid.NewGuid().ToString("N"));
    private static readonly DateTimeOffset ValidFrom = new(2026, 10, 8, 0, 0, 0, TimeSpan.FromHours(5.5));
    private static readonly DateTimeOffset ValidTo = new(2026, 10, 8, 23, 59, 59, TimeSpan.FromHours(5.5));
    private static readonly byte[] Face = [1, 2, 3, 4, 5];

    public PendingEnrollmentTests()
    {
        Directory.CreateDirectory(_directory);
    }

    [Fact]
    public async Task A_new_reader_id_is_uploaded_and_the_sibling_receives_no_write()
    {
        var reader = new FakeReader();
        var sibling = new FakeReader();
        reader.CreateUser(Person("7", "Walk In"));
        var writes = reader.Writes.Count;
        var sink = new RecordingUpload();
        using var worker = new ReaderWorker("reader-a", reader, Path.Combine(_directory, "a.sqlite"));
        var path = new DesiredRevisionPath("reader-a", worker, reader, new EmptyPull(), sink);

        await path.ReconnectAsync(CancellationToken.None);
        await path.ReconnectAsync(CancellationToken.None);

        Assert.Equal(new[] { "7", "7" }, sink.Ids);
        Assert.Equal(writes, reader.Writes.Count);
        Assert.Empty(sibling.Writes);
        Assert.Empty(sibling.Calls);
    }

    [Fact]
    public async Task A_trusted_scan_uploads_without_pulling_desired_state()
    {
        var reader = new FakeReader();
        var sibling = new FakeReader();
        reader.CreateUser(Person("7", "Walk In"));
        var writes = reader.Writes.Count;
        var sink = new RecordingUpload();
        var client = new EmptyPull();
        using var worker = new ReaderWorker("reader-a", reader, Path.Combine(_directory, "scan.sqlite"));
        var path = new DesiredRevisionPath("reader-a", worker, reader, client, sink);

        await path.ObserveAsync(CancellationToken.None);
        await path.ObserveAsync(CancellationToken.None);

        Assert.Equal(new[] { "7", "7" }, sink.Ids);
        Assert.Equal(0, client.Pulls);
        Assert.Equal(writes, reader.Writes.Count);
        Assert.Empty(sibling.Writes);
        Assert.Empty(sibling.Calls);
    }

    [Fact]
    public async Task A_renamed_mapped_user_is_uploaded_and_not_written_back()
    {
        var reader = new FakeReader();
        var sibling = new FakeReader();
        using var worker = new ReaderWorker("reader-a", reader, Path.Combine(_directory, "rename.sqlite"));
        Assert.Equal(MemberApplyKind.Applied, worker.ApplyMember(new DesiredMember(
            1, "1", "Asha Shah", null, 0, ValidFrom, ValidTo, "Customer", 1, 1, Face)).Kind);
        var writes = reader.Writes.ToArray();
        var renamed = Person("1", "Asha Reader", "ADMIN");
        reader.ScriptList(1, renamed);
        reader.ScriptList(1, renamed);
        var sink = new RecordingUpload();
        var path = new DesiredRevisionPath("reader-a", worker, reader, new EmptyPull(), sink);

        await path.ObserveAsync(CancellationToken.None);
        await path.ObserveAsync(CancellationToken.None);

        Assert.Equal(new[] { "1", "1" }, sink.Ids);
        Assert.Equal(writes, reader.Writes);
        Assert.Empty(sibling.Writes);
        Assert.Empty(sibling.Calls);
    }

    [Fact]
    public async Task A_short_list_uploads_nothing()
    {
        var reader = new FakeReader();
        var sibling = new FakeReader();
        reader.ScriptList(2, Person("7", "Walk In"));
        var sink = new RecordingUpload();
        using var worker = new ReaderWorker("reader-a", reader, Path.Combine(_directory, "short.sqlite"));
        var path = new DesiredRevisionPath("reader-a", worker, reader, new EmptyPull(), sink);

        await path.ReconnectAsync(CancellationToken.None);

        Assert.Empty(sink.Ids);
        Assert.Empty(reader.Writes);
        Assert.Empty(sibling.Writes);
    }

    [Fact]
    public async Task An_unreachable_server_does_not_write_the_sibling()
    {
        var reader = new FakeReader();
        var sibling = new FakeReader();
        reader.CreateUser(Person("7", "Walk In"));
        var writes = reader.Writes.Count;
        var sink = new RecordingUpload { Offline = true };
        using var worker = new ReaderWorker("reader-a", reader, Path.Combine(_directory, "offline.sqlite"));
        var path = new DesiredRevisionPath("reader-a", worker, reader, new EmptyPull(), sink);

        await path.ReconnectAsync(CancellationToken.None);

        Assert.Empty(sink.Ids);
        Assert.Equal(writes, reader.Writes.Count);
        Assert.Empty(sibling.Writes);
        Assert.Empty(sibling.Calls);
    }

    public void Dispose()
    {
        if (Directory.Exists(_directory))
        {
            Directory.Delete(_directory, recursive: true);
        }
    }

    private static ReaderUser Person(string id, string name) => Person(id, name, "Customer");

    private static ReaderUser Person(string id, string name, string authority) =>
        new(id, name, null, 0, null, null, authority, 1, 1);

    private sealed class RecordingUpload : IReaderObservationUpload
    {
        public List<string> Ids { get; } = [];

        public bool Offline { get; set; }

        public Task UploadAsync(string deviceId, IReadOnlyList<ReaderUser> users, CancellationToken cancellationToken)
        {
            if (Offline)
            {
                throw new HttpRequestException("unreachable");
            }

            Ids.AddRange(users.Select(user => user.DeviceUserId));
            return Task.CompletedTask;
        }
    }

    private sealed class EmptyPull : IDesiredStateClient
    {
        public int Pulls { get; private set; }

        public Task<DesiredPull> PullAsync(string deviceId, long after, CancellationToken cancellationToken)
        {
            Pulls++;
            return Task.FromResult(new DesiredPull(0, after, []));
        }

        public Task AcknowledgeAsync(DesiredAcknowledgement ack, CancellationToken cancellationToken) =>
            Task.CompletedTask;

        public Task ReportOccupiedAsync(
            string deviceId, long revision, string deviceUserId, CancellationToken cancellationToken) =>
            Task.CompletedTask;
    }
}
