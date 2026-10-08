using Gym.Gateway;
using Gym.Gateway.Adapters;
using Gym.Gateway.Execution;
using Microsoft.Extensions.Logging;
using Xunit;

namespace Gym.Gateway.Tests;

public class SecondReaderTests : IDisposable
{
    private readonly string _directory = Path.Combine(Path.GetTempPath(), "gym-v7-" + Guid.NewGuid().ToString("N"));
    private static readonly DateTimeOffset ValidFrom = new(2026, 10, 8, 0, 0, 0, TimeSpan.FromHours(5.5));
    private static readonly DateTimeOffset ValidTo = new(2026, 10, 8, 23, 59, 59, TimeSpan.FromHours(5.5));
    private static readonly byte[] Face = [1, 2, 3, 4, 5];

    public SecondReaderTests()
    {
        Directory.CreateDirectory(_directory);
    }

    [Fact]
    public async Task The_other_reader_acks_while_the_failed_reader_stays_on_the_old_revision()
    {
        var readerA = new FakeReader();
        var readerB = new FakeReader();
        readerA.ScriptFailure(FakeReaderOperation.GetUser, "reader down");
        var client = new IndependentRevisions();
        client.Offer("reader-a", Item("Asha"));
        client.Offer("reader-b", Item("Bina"));
        var logs = LoggerFactory.Create(_ => { });
        var hub = new DesiredRevisionHub(logs.CreateLogger<DesiredRevisionHub>());

        using var workerA = new ReaderWorker(
            "reader-a", readerA, Path.Combine(_directory, "a.sqlite"), TimeSpan.Zero);
        using var workerB = new ReaderWorker("reader-b", readerB, Path.Combine(_directory, "b.sqlite"));
        var pathA = new DesiredRevisionPath("reader-a", workerA, readerA, client);
        var pathB = new DesiredRevisionPath("reader-b", workerB, readerB, client);
        hub.Attach("reader-a", pathA.HandleAsync, pathA.ReconnectAsync);
        hub.Attach("reader-b", pathB.HandleAsync, pathB.ReconnectAsync);

        await hub.ReconnectAsync(CancellationToken.None);

        Assert.Equal(0, workerA.AppliedRevision);
        Assert.Equal("reader down", workerA.Retry!.LastError);
        Assert.Empty(readerA.Writes);
        Assert.Equal(1, workerB.AppliedRevision);
        Assert.Null(workerB.Retry);
        Assert.Equal("Bina", readerB.GetUser("1").User!.Name);
        Assert.Equal(new[] { "CreateUser 1", "InsertFace 1" }, readerB.Writes);
        Assert.Equal("reader-b", Assert.Single(client.Acknowledgements).DeviceId);

        var writesOnB = readerB.Writes.ToArray();
        await hub.ReconnectAsync(CancellationToken.None);

        Assert.Equal(1, workerA.AppliedRevision);
        Assert.Null(workerA.Retry);
        Assert.Equal("Asha", readerA.GetUser("1").User!.Name);
        Assert.Equal(writesOnB, readerB.Writes);
        Assert.Equal("Bina", readerB.GetUser("1").User!.Name);
        Assert.Equal(1, workerB.AppliedRevision);
    }

    public void Dispose()
    {
        if (Directory.Exists(_directory))
        {
            Directory.Delete(_directory, recursive: true);
        }
    }

    private static DesiredPullItem Item(string name) => new(
        1,
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

    private sealed class IndependentRevisions : IDesiredStateClient
    {
        private readonly Dictionary<string, DesiredPullItem> _items = new(StringComparer.Ordinal);

        public List<DesiredAcknowledgement> Acknowledgements { get; } = [];

        public void Offer(string deviceId, DesiredPullItem item) => _items[deviceId] = item;

        public Task<DesiredPull> PullAsync(string deviceId, long after, CancellationToken cancellationToken)
        {
            if (!_items.TryGetValue(deviceId, out var item) || item.Revision <= after)
            {
                return Task.FromResult(new DesiredPull(item?.Revision ?? 0, after, []));
            }

            return Task.FromResult(new DesiredPull(item.Revision, after, [item]));
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
