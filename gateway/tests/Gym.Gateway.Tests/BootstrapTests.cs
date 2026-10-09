using Gym.Gateway.Adapters;
using Gym.Gateway.Execution;
using Xunit;

namespace Gym.Gateway.Tests;

public class BootstrapTests : IDisposable
{
    private readonly string _directory = Path.Combine(Path.GetTempPath(), "gym-v15-" + Guid.NewGuid().ToString("N"));

    public BootstrapTests()
    {
        Directory.CreateDirectory(_directory);
    }

    [Fact]
    public async Task A_short_list_uploads_nothing_and_writes_no_reader()
    {
        var reader = new FakeReader();
        var sibling = new FakeReader();
        reader.CreateUser(new ReaderUser("7", "Walk In", null, 0, null, null, "Customer", 1, 1));
        reader.ScriptList(4, new ReaderUser("7", "Walk In", null, 0, null, null, "Customer", 1, 1));
        var sink = new PendingEnrollmentTests.RecordingUpload();
        using var worker = new ReaderWorker("reader-a", reader, Path.Combine(_directory, "short.sqlite"));
        var client = new PendingEnrollmentTests.EmptyPull();
        var path = new DesiredRevisionPath("reader-a", worker, reader, client, sink);

        var trusted = await path.BootstrapAsync(CancellationToken.None);

        Assert.False(trusted);
        Assert.Empty(sink.Ids);
        Assert.Empty(sink.TrustedRosterTotals);
        Assert.Equal(0, client.Pulls);
        Assert.Equal(new[] { "CreateUser 7" }, reader.Writes);
        Assert.Empty(sibling.Writes);
        Assert.Empty(sibling.Calls);
        Assert.Equal(1, reader.Calls.Count(call => call == "ListUsers"));
    }

    [Fact]
    public async Task A_trusted_read_uses_the_observation_upload_and_does_not_copy()
    {
        var reader = new FakeReader();
        var sibling = new FakeReader();
        reader.CreateUser(new ReaderUser("7", "Walk In", null, 0, null, null, "Customer", 1, 1));
        var sink = new PendingEnrollmentTests.RecordingUpload();
        using var worker = new ReaderWorker("reader-a", reader, Path.Combine(_directory, "trusted.sqlite"));
        var client = new PendingEnrollmentTests.EmptyPull();
        var path = new DesiredRevisionPath("reader-a", worker, reader, client, sink);

        var trusted = await path.BootstrapAsync(CancellationToken.None);

        Assert.True(trusted);
        Assert.Equal(new[] { "7" }, sink.Ids);
        Assert.Empty(sink.TrustedRosterTotals);
        Assert.Equal(0, client.Pulls);
        Assert.Equal(new[] { "CreateUser 7" }, reader.Writes);
        Assert.Empty(sibling.Writes);
        Assert.Empty(sibling.Calls);
    }

    [Fact]
    public async Task An_empty_trusted_read_reports_the_empty_roster_and_copies_nothing()
    {
        var reader = new FakeReader();
        var sibling = new FakeReader();
        sibling.CreateUser(new ReaderUser("3", "Full", null, 0, null, null, "Customer", 1, 1));
        reader.ScriptList(0);
        var sink = new PendingEnrollmentTests.RecordingUpload();
        using var worker = new ReaderWorker("reader-a", reader, Path.Combine(_directory, "empty.sqlite"));
        var path = new DesiredRevisionPath("reader-a", worker, reader, new PendingEnrollmentTests.EmptyPull(), sink);

        var trusted = await path.BootstrapAsync(CancellationToken.None);

        Assert.True(trusted);
        Assert.Equal(new[] { 0 }, sink.TrustedRosterTotals);
        Assert.Empty(sink.Ids);
        Assert.Empty(reader.Writes);
        Assert.Equal(new[] { "CreateUser 3" }, sibling.Writes);
    }

    public void Dispose()
    {
        if (Directory.Exists(_directory))
        {
            Directory.Delete(_directory, recursive: true);
        }
    }
}
