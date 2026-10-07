using Gym.Gateway.Adapters;
using Microsoft.Data.Sqlite;

namespace Gym.Gateway.Execution;

/// <summary>
/// Execution state for one reader. The SQLite file remembers the verified revision, the ack that
/// still has to be sent, and retry metadata. It does not record heartbeats and it does not decide
/// which member wins. Device calls go through <see cref="FakeReader"/>.
/// </summary>
public sealed class ReaderWorker : IDisposable
{
    private readonly FakeReader _reader;
    private readonly string _readerId;
    private readonly Func<DateTimeOffset> _clock;
    private readonly TimeSpan _retryDelay;
    private readonly SqliteConnection _connection;
    private readonly object _gate = new();
    private string? _health;

    public ReaderWorker(
        string readerId,
        FakeReader reader,
        string journalPath,
        TimeSpan? retryDelay = null,
        Func<DateTimeOffset>? clock = null)
    {
        if (string.IsNullOrWhiteSpace(readerId))
        {
            throw new ArgumentException("A reader id is required.", nameof(readerId));
        }

        _readerId = readerId;
        _reader = reader ?? throw new ArgumentNullException(nameof(reader));
        _retryDelay = retryDelay ?? TimeSpan.FromSeconds(1);
        _clock = clock ?? (() => DateTimeOffset.UtcNow);
        var directory = Path.GetDirectoryName(Path.GetFullPath(journalPath));
        if (!string.IsNullOrEmpty(directory))
        {
            Directory.CreateDirectory(directory);
        }

        _connection = new SqliteConnection($"Data Source={journalPath}");
        _connection.Open();
        using (var pragma = _connection.CreateCommand())
        {
            pragma.CommandText = "PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL;";
            pragma.ExecuteNonQuery();
        }

        CreateSchema();
    }

    public string ReaderId => _readerId;

    public string? Health => _health;

    public long AppliedRevision
    {
        get
        {
            lock (_gate)
            {
                using var command = _connection.CreateCommand();
                command.CommandText = "SELECT revision FROM applied_revision WHERE reader_id = $reader";
                command.Parameters.AddWithValue("$reader", _readerId);
                var value = command.ExecuteScalar();
                return value is long revision ? revision : 0;
            }
        }
    }

    public RetryState? Retry
    {
        get
        {
            lock (_gate)
            {
                using var command = _connection.CreateCommand();
                command.CommandText = """
                    SELECT revision, attempt_count, next_attempt, last_error
                    FROM retry_state WHERE reader_id = $reader
                    """;
                command.Parameters.AddWithValue("$reader", _readerId);
                using var row = command.ExecuteReader();
                if (!row.Read())
                {
                    return null;
                }

                return new RetryState(
                    row.GetInt64(0),
                    row.GetInt32(1),
                    DateTimeOffset.Parse(row.GetString(2)),
                    row.GetString(3));
            }
        }
    }

    public IReadOnlyList<RevisionAck> PendingAcks
    {
        get
        {
            lock (_gate)
            {
                using var command = _connection.CreateCommand();
                command.CommandText = """
                    SELECT reader_id, revision FROM revision_ack
                    WHERE reader_id = $reader ORDER BY revision
                    """;
                command.Parameters.AddWithValue("$reader", _readerId);
                using var row = command.ExecuteReader();
                var acks = new List<RevisionAck>();
                while (row.Read())
                {
                    acks.Add(new RevisionAck(row.GetString(0), row.GetInt64(1)));
                }

                return acks;
            }
        }
    }

    /// <summary>Health stays in memory. It is not a journal row and it is not replayed.</summary>
    public void NoteHealth(string state)
    {
        _health = state;
    }

    public ApplyOutcome Apply(long revision, Func<FakeReader, Verification> verify)
    {
        if (revision <= 0)
        {
            throw new ArgumentOutOfRangeException(nameof(revision), "A revision is a positive number.");
        }

        ArgumentNullException.ThrowIfNull(verify);
        lock (_gate)
        {
            if (revision <= AppliedRevisionUnlocked())
            {
                return ApplyOutcome.AlreadyVerified;
            }

            var retry = RetryUnlocked();
            if (retry != null && retry.Revision != revision)
            {
                return ApplyOutcome.NotReady;
            }

            if (retry != null && _clock() < retry.NextAttempt)
            {
                return ApplyOutcome.WaitingToRetry;
            }

            Verification verification;
            try
            {
                verification = verify(_reader);
            }
            catch (Exception ex)
            {
                verification = new Verification(false, ex.Message);
            }

            if (!verification.Verified)
            {
                RecordFailure(revision, retry?.AttemptCount ?? 0, verification.Error ?? "not verified");
                return ApplyOutcome.Failed;
            }

            CommitVerified(revision);
            return ApplyOutcome.Verified;
        }
    }

    public void MarkAckDelivered(long revision)
    {
        lock (_gate)
        {
            using var command = _connection.CreateCommand();
            command.CommandText = "DELETE FROM revision_ack WHERE reader_id = $reader AND revision = $revision";
            command.Parameters.AddWithValue("$reader", _readerId);
            command.Parameters.AddWithValue("$revision", revision);
            command.ExecuteNonQuery();
        }
    }

    public void Dispose()
    {
        _connection.Dispose();
    }

    private void CreateSchema()
    {
        using var command = _connection.CreateCommand();
        command.CommandText = """
            CREATE TABLE IF NOT EXISTS applied_revision (
                reader_id TEXT PRIMARY KEY,
                revision INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS revision_ack (
                reader_id TEXT NOT NULL,
                revision INTEGER NOT NULL,
                PRIMARY KEY (reader_id, revision)
            );
            CREATE TABLE IF NOT EXISTS retry_state (
                reader_id TEXT PRIMARY KEY,
                revision INTEGER NOT NULL,
                attempt_count INTEGER NOT NULL,
                next_attempt TEXT NOT NULL,
                last_error TEXT NOT NULL
            );
            """;
        command.ExecuteNonQuery();
    }

    private long AppliedRevisionUnlocked()
    {
        using var command = _connection.CreateCommand();
        command.CommandText = "SELECT revision FROM applied_revision WHERE reader_id = $reader";
        command.Parameters.AddWithValue("$reader", _readerId);
        var value = command.ExecuteScalar();
        return value is long revision ? revision : 0;
    }

    private RetryState? RetryUnlocked()
    {
        using var command = _connection.CreateCommand();
        command.CommandText = """
            SELECT revision, attempt_count, next_attempt, last_error
            FROM retry_state WHERE reader_id = $reader
            """;
        command.Parameters.AddWithValue("$reader", _readerId);
        using var row = command.ExecuteReader();
        if (!row.Read())
        {
            return null;
        }

        return new RetryState(
            row.GetInt64(0),
            row.GetInt32(1),
            DateTimeOffset.Parse(row.GetString(2)),
            row.GetString(3));
    }

    private void RecordFailure(long revision, int previousAttempts, string error)
    {
        var next = _clock().Add(_retryDelay);
        using var command = _connection.CreateCommand();
        command.CommandText = """
            INSERT INTO retry_state (reader_id, revision, attempt_count, next_attempt, last_error)
            VALUES ($reader, $revision, $attempts, $next, $error)
            ON CONFLICT (reader_id) DO UPDATE SET
                revision = excluded.revision,
                attempt_count = excluded.attempt_count,
                next_attempt = excluded.next_attempt,
                last_error = excluded.last_error
            """;
        command.Parameters.AddWithValue("$reader", _readerId);
        command.Parameters.AddWithValue("$revision", revision);
        command.Parameters.AddWithValue("$attempts", previousAttempts + 1);
        command.Parameters.AddWithValue("$next", next.ToString("o"));
        command.Parameters.AddWithValue("$error", error);
        command.ExecuteNonQuery();
    }

    private void CommitVerified(long revision)
    {
        using var transaction = _connection.BeginTransaction();
        using (var applied = _connection.CreateCommand())
        {
            applied.Transaction = transaction;
            applied.CommandText = """
                INSERT INTO applied_revision (reader_id, revision) VALUES ($reader, $revision)
                ON CONFLICT (reader_id) DO UPDATE SET revision = excluded.revision
                WHERE excluded.revision > applied_revision.revision
                """;
            applied.Parameters.AddWithValue("$reader", _readerId);
            applied.Parameters.AddWithValue("$revision", revision);
            applied.ExecuteNonQuery();
        }

        using (var ack = _connection.CreateCommand())
        {
            ack.Transaction = transaction;
            ack.CommandText = """
                INSERT INTO revision_ack (reader_id, revision) VALUES ($reader, $revision)
                ON CONFLICT (reader_id, revision) DO NOTHING
                """;
            ack.Parameters.AddWithValue("$reader", _readerId);
            ack.Parameters.AddWithValue("$revision", revision);
            ack.ExecuteNonQuery();
        }

        using (var retry = _connection.CreateCommand())
        {
            retry.Transaction = transaction;
            retry.CommandText = "DELETE FROM retry_state WHERE reader_id = $reader";
            retry.Parameters.AddWithValue("$reader", _readerId);
            retry.ExecuteNonQuery();
        }

        transaction.Commit();
    }
}

public enum ApplyOutcome
{
    Verified,
    AlreadyVerified,
    Failed,
    WaitingToRetry,
    NotReady
}

public sealed record Verification(bool Verified, string? Error);

public sealed record RetryState(long Revision, int AttemptCount, DateTimeOffset NextAttempt, string LastError);

public sealed record RevisionAck(string ReaderId, long Revision);
