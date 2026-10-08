using Gym.Gateway.Adapters;
using Gym.Gateway.Execution;
using Xunit;

namespace Gym.Gateway.Tests;

public class ReconnectRevisionTests : IDisposable
{
    private readonly string _directory = Path.Combine(Path.GetTempPath(), "gym-v6-" + Guid.NewGuid().ToString("N"));
    private static readonly DateTimeOffset ValidFrom = new(2026, 10, 8, 0, 0, 0, TimeSpan.FromHours(5.5));
    private static readonly DateTimeOffset ValidTo = new(2026, 10, 8, 23, 59, 59, TimeSpan.FromHours(5.5));
    private static readonly byte[] Face = [1, 2, 3, 4, 5];

    public ReconnectRevisionTests()
    {
        Directory.CreateDirectory(_directory);
    }

    [Fact]
    public async Task Offline_change_is_applied_only_after_the_reader_is_read()
    {
        var reader = new FakeReader();
        var journal = Path.Combine(_directory, "offline.sqlite");
        var client = new Pages();
        client.Items = [Item(1, "Asha")];

        using (var worker = new ReaderWorker("reader-1", reader, journal))
        {
            var path = new DesiredRevisionPath("reader-1", worker, reader, client);
            await path.ReconnectAsync(CancellationToken.None);

            Assert.Equal("ListUsers", reader.Calls[0]);
            Assert.Equal("Asha", reader.GetUser("1").User!.Name);
            Assert.Equal(new[] { "CreateUser 1", "InsertFace 1" }, reader.Writes);
            Assert.Single(client.Acknowledgements);

            client.Items = [Item(2, "Asha Edited")];
            var at = reader.Calls.Count;
            await path.ReconnectAsync(CancellationToken.None);

            Assert.Equal("ListUsers", reader.Calls[at]);
            Assert.Equal("Asha Edited", reader.GetUser("1").User!.Name);
            Assert.Contains("ReplaceUser 1", reader.Writes);
            Assert.Equal(2, client.Acknowledgements.Count);
        }
    }

    [Fact]
    public async Task An_older_revision_after_a_newer_one_does_not_write()
    {
        var reader = new FakeReader();
        var journal = Path.Combine(_directory, "stale.sqlite");
        var client = new Pages();
        client.Items = [Item(4, "Four")];

        using var worker = new ReaderWorker("reader-1", reader, journal);
        var path = new DesiredRevisionPath("reader-1", worker, reader, client);
        await path.ReconnectAsync(CancellationToken.None);
        Assert.Equal("Four", reader.GetUser("1").User!.Name);

        var writes = reader.Writes.ToArray();
        var at = reader.Calls.Count;
        client.IgnoreAfter = true;
        client.Items = [Item(3, "Three")];
        await path.ReconnectAsync(CancellationToken.None);

        Assert.Equal("ListUsers", reader.Calls[at]);
        Assert.Equal(writes, reader.Writes);
        Assert.Equal("Four", reader.GetUser("1").User!.Name);
        Assert.Equal(4, worker.AppliedRevision);
    }

    [Fact]
    public async Task Restart_reads_again_and_does_not_write_a_verified_revision()
    {
        var reader = new FakeReader();
        var journal = Path.Combine(_directory, "restart.sqlite");
        var client = new Pages();
        client.Items = [Item(1, "Asha")];

        using (var worker = new ReaderWorker("reader-1", reader, journal))
        {
            var path = new DesiredRevisionPath("reader-1", worker, reader, client);
            await path.ReconnectAsync(CancellationToken.None);
            Assert.Equal(new[] { "CreateUser 1", "InsertFace 1" }, reader.Writes);
        }

        var writes = reader.Writes.ToArray();
        var at = reader.Calls.Count;
        using var restarted = new ReaderWorker("reader-1", reader, journal);
        var again = new DesiredRevisionPath("reader-1", restarted, reader, client);
        await again.ReconnectAsync(CancellationToken.None);

        Assert.Equal("ListUsers", reader.Calls[at]);
        Assert.Equal(writes, reader.Writes);
        Assert.Equal("Asha", reader.GetUser("1").User!.Name);
        Assert.Equal(1, restarted.AppliedRevision);
    }

    [Fact]
    public async Task A_silent_reader_is_not_written()
    {
        var reader = new FakeReader();
        reader.ScriptListFailure("silent");
        var client = new Pages();
        client.Items = [Item(1, "Asha")];
        using var worker = new ReaderWorker("reader-1", reader, Path.Combine(_directory, "silent.sqlite"));
        var path = new DesiredRevisionPath("reader-1", worker, reader, client);

        await path.ReconnectAsync(CancellationToken.None);

        Assert.Equal("ListUsers", Assert.Single(reader.Calls));
        Assert.Empty(reader.Writes);
        Assert.Equal(0, client.Pulls);
        Assert.Empty(client.Acknowledgements);
        Assert.Equal(0, worker.AppliedRevision);
    }

    public void Dispose()
    {
        if (Directory.Exists(_directory))
        {
            Directory.Delete(_directory, recursive: true);
        }
    }

    private static DesiredPullItem Item(long revision, string name) => new(
        revision,
        "1",
        name,
        null,
        0,
        ReaderLocalTime.Format(ValidFrom),
        ReaderLocalTime.Format(ValidTo),
        "Customer",
        1,
        1,
        Face,
        true);

    private sealed class Pages : IDesiredStateClient
    {
        public List<DesiredPullItem> Items { get; set; } = [];

        public bool IgnoreAfter { get; set; }

        public int Pulls { get; private set; }

        public List<DesiredAcknowledgement> Acknowledgements { get; } = [];

        public Task<DesiredPull> PullAsync(string deviceId, long after, CancellationToken cancellationToken)
        {
            Pulls++;
            List<DesiredPullItem> items = IgnoreAfter
                ? Items
                : Items.Where(item => item.Revision > after).ToList();
            var desired = Items.Count == 0 ? 0 : Items.Max(item => item.Revision);
            return Task.FromResult(new DesiredPull(desired, after, items));
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
