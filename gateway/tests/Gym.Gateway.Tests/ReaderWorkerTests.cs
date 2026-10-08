using Gym.Gateway.Adapters;
using Gym.Gateway.Execution;
using Microsoft.Data.Sqlite;
using Xunit;

namespace Gym.Gateway.Tests;

public class ReaderWorkerTests : IDisposable
{
    private readonly string _directory = Path.Combine(Path.GetTempPath(), "gym-f3-" + Guid.NewGuid().ToString("N"));
    private readonly DateTimeOffset _start = new(2026, 10, 7, 0, 0, 0, TimeSpan.Zero);
    private DateTimeOffset _now;

    public ReaderWorkerTests()
    {
        _now = _start;
        Directory.CreateDirectory(_directory);
    }

    [Fact]
    public void Restart_does_not_apply_a_verified_revision_again_and_keeps_one_ack()
    {
        var journal = Path.Combine(_directory, "gateway.sqlite");
        var reader = new FakeReader();
        var calls = 0;

        using (var worker = Start(journal, reader))
        {
            var outcome = worker.Apply(1, device =>
            {
                calls++;
                var created = device.CreateUser(User("1210"));
                return new Verification(created.Ok, created.Error);
            });

            Assert.Equal(ApplyOutcome.Verified, outcome);
            Assert.Equal(1, worker.AppliedRevision);
            Assert.Equal(new[] { 1L }, worker.PendingAcks.Select(a => a.Revision).ToArray());
        }

        using var restarted = Start(journal, reader);
        var again = restarted.Apply(1, _ =>
        {
            calls++;
            return new Verification(true, null);
        });

        Assert.Equal(ApplyOutcome.AlreadyVerified, again);
        Assert.Equal(1, calls);
        Assert.Equal(1, restarted.AppliedRevision);
        Assert.Single(restarted.PendingAcks);
        Assert.Equal(new[] { "1210" }, reader.ListUsers().Users.Select(u => u.DeviceUserId).ToArray());

        restarted.MarkAckDelivered(1);
        Assert.Empty(restarted.PendingAcks);
    }

    [Fact]
    public void A_delivered_ack_is_not_written_again_on_restart()
    {
        var journal = Path.Combine(_directory, "gateway.sqlite");
        var calls = 0;
        using (var worker = Start(journal, new FakeReader()))
        {
            worker.Apply(4, _ =>
            {
                calls++;
                return new Verification(true, null);
            });
            worker.MarkAckDelivered(4);
        }

        using var restarted = Start(journal, new FakeReader());
        restarted.Apply(4, _ =>
        {
            calls++;
            return new Verification(true, null);
        });

        Assert.Equal(1, calls);
        Assert.Empty(restarted.PendingAcks);
        Assert.Equal(4, restarted.AppliedRevision);
    }

    [Fact]
    public void A_failed_device_call_records_retry_and_does_not_ack()
    {
        var journal = Path.Combine(_directory, "gateway.sqlite");
        var reader = new FakeReader();
        reader.ScriptFailure(FakeReaderOperation.CreateUser, "create failed");
        var calls = 0;

        using var worker = Start(journal, reader, TimeSpan.FromMinutes(1));
        var failed = worker.Apply(1, device =>
        {
            calls++;
            var created = device.CreateUser(User("1210"));
            return new Verification(created.Ok, created.Error);
        });

        Assert.Equal(ApplyOutcome.Failed, failed);
        Assert.Equal(0, worker.AppliedRevision);
        Assert.Empty(worker.PendingAcks);
        Assert.Empty(reader.ListUsers().Users);
        var retry = worker.Retry;
        Assert.NotNull(retry);
        Assert.Equal(1, retry.Revision);
        Assert.Equal(1, retry.AttemptCount);
        Assert.Equal(_start.AddMinutes(1), retry.NextAttempt);
        Assert.Equal("create failed", retry.LastError);

        Assert.Equal(ApplyOutcome.WaitingToRetry, worker.Apply(1, _ =>
        {
            calls++;
            return new Verification(true, null);
        }));
        Assert.Equal(ApplyOutcome.NotReady, worker.Apply(2, _ =>
        {
            calls++;
            return new Verification(true, null);
        }));
        Assert.Equal(1, calls);

        _now = _start.AddMinutes(1);
        var verified = worker.Apply(1, device =>
        {
            calls++;
            var created = device.CreateUser(User("1210"));
            return new Verification(created.Ok, created.Error);
        });

        Assert.Equal(ApplyOutcome.Verified, verified);
        Assert.Equal(2, calls);
        Assert.Equal(1, worker.AppliedRevision);
        Assert.Null(worker.Retry);
        Assert.Single(worker.PendingAcks);
    }

    [Fact]
    public void An_older_revision_does_not_run_again()
    {
        var journal = Path.Combine(_directory, "gateway.sqlite");
        var calls = 0;
        using var worker = Start(journal, new FakeReader());
        worker.Apply(2, _ =>
        {
            calls++;
            return new Verification(true, null);
        });

        var older = worker.Apply(1, _ =>
        {
            calls++;
            return new Verification(true, null);
        });

        Assert.Equal(ApplyOutcome.AlreadyVerified, older);
        Assert.Equal(1, calls);
        Assert.Equal(2, worker.AppliedRevision);
    }

    [Fact]
    public void Health_is_not_stored_in_the_journal()
    {
        var journal = Path.Combine(_directory, "gateway.sqlite");
        using (var worker = Start(journal, new FakeReader()))
        {
            worker.NoteHealth("ONLINE");
            worker.Apply(1, _ => new Verification(true, null));
        }

        long applied;
        int pending;
        using (var restarted = Start(journal, new FakeReader()))
        {
            Assert.Null(restarted.Health);
            applied = restarted.AppliedRevision;
            pending = restarted.PendingAcks.Count;
        }

        Assert.Equal(1, applied);
        Assert.Equal(1, pending);
        Assert.Equal(
            new[] { "applied_revision", "applied_user", "retry_state", "revision_ack" },
            TableNames(journal));
        Assert.Equal(0, CountRows(journal, "retry_state"));
    }

    public void Dispose()
    {
        try
        {
            Directory.Delete(_directory, recursive: true);
        }
        catch (IOException)
        {
            // The SQLite handle may still be releasing the file on some hosts.
        }
    }

    private ReaderWorker Start(string journal, FakeReader reader, TimeSpan? retryDelay = null) =>
        new("reader-1", reader, journal, retryDelay, () => _now);

    private static ReaderUser User(string id) =>
        new(id, "Asha", "Asha", 0,
            new DateTimeOffset(2026, 10, 6, 0, 0, 0, TimeSpan.Zero),
            new DateTimeOffset(2026, 10, 7, 23, 59, 59, TimeSpan.Zero),
            "Customer", 1, 1);

    private static IReadOnlyList<string> TableNames(string journal)
    {
        using var connection = new SqliteConnection($"Data Source={journal}");
        connection.Open();
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name";
        using var row = command.ExecuteReader();
        var names = new List<string>();
        while (row.Read())
        {
            names.Add(row.GetString(0));
        }

        return names;
    }

    private static int CountRows(string journal, string table)
    {
        using var connection = new SqliteConnection($"Data Source={journal}");
        connection.Open();
        using var command = connection.CreateCommand();
        command.CommandText = $"SELECT COUNT(*) FROM {table}";
        return Convert.ToInt32(command.ExecuteScalar());
    }
}
