using System.Security.Cryptography;
using Gym.Gateway.Adapters;
using Microsoft.Data.Sqlite;

namespace Gym.Gateway.Execution;

/// <summary>
/// Execution state for one reader. The SQLite file remembers the verified revision, the ack that
/// still has to be sent, and retry metadata. It does not record heartbeats and it does not decide
/// which member wins. Device calls go through <see cref="IReaderAdapter"/>.
/// </summary>
public sealed class ReaderWorker : IDisposable
{
    private readonly IReaderAdapter _reader;
    private readonly string _readerId;
    private readonly Func<DateTimeOffset> _clock;
    private readonly TimeSpan _retryDelay;
    private readonly SqliteConnection _connection;
    private readonly object _gate = new();
    private string? _health;

    public ReaderWorker(
        string readerId,
        IReaderAdapter reader,
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

    public ApplyOutcome Apply(long revision, Func<IReaderAdapter, Verification> verify)
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

    /// <summary>
    /// Creates the user and inserts the face, then reads both back. The revision is journaled only
    /// when the read-back matches. An occupied id is reported and nothing is written over it.
    /// </summary>
    public MemberApplyResult ApplyMember(DesiredMember desired)
    {
        ArgumentNullException.ThrowIfNull(desired);
        if (desired.Face is not { Length: > 0 })
        {
            throw new ArgumentException("The desired member has no face.", nameof(desired));
        }

        string? occupiedId = null;
        var outcome = Apply(desired.Revision, reader =>
        {
            var step = WriteAndReadBack(reader, desired);
            if (step.Occupied)
            {
                occupiedId = desired.DeviceUserId;
            }

            return step.Verification;
        });
        if (occupiedId != null)
        {
            ClearRetry();
            return new MemberApplyResult(MemberApplyKind.Occupied, occupiedId);
        }

        var kind = outcome switch
        {
            ApplyOutcome.Verified => MemberApplyKind.Applied,
            ApplyOutcome.AlreadyVerified => MemberApplyKind.AlreadyApplied,
            ApplyOutcome.WaitingToRetry => MemberApplyKind.Waiting,
            ApplyOutcome.NotReady => MemberApplyKind.NotReady,
            _ => MemberApplyKind.Failed
        };
        return new MemberApplyResult(kind, null);
    }

    private static WriteStep WriteAndReadBack(IReaderAdapter reader, DesiredMember desired)
    {
        var existing = reader.GetUser(desired.DeviceUserId);
        if (existing.Ok)
        {
            if (!SameUser(existing.User!, desired))
            {
                var occupied = reader.CreateUser(Record(desired));
                return occupied.FailCode == FakeReader.FailOccupied
                    ? WriteStep.Collision()
                    : WriteStep.Fail(occupied.Error ?? occupied.FailCode);
            }
        }
        else if (existing.FailCode == FakeReader.FailNoRecord)
        {
            var created = reader.CreateUser(Record(desired));
            if (!created.Ok)
            {
                return created.FailCode == FakeReader.FailOccupied
                    ? WriteStep.Collision()
                    : WriteStep.Fail(created.Error);
            }
        }
        else
        {
            return WriteStep.Fail(existing.Error);
        }

        var face = reader.GetFace(desired.DeviceUserId);
        if (!face.Ok)
        {
            if (face.FailCode is not (FakeReader.FailUnknown or FakeReader.FailNoRecord))
            {
                return WriteStep.Fail(face.Error);
            }

            var inserted = reader.InsertFace(desired.DeviceUserId, desired.Face);
            if (!inserted.Ok)
            {
                return WriteStep.Fail(inserted.Error ?? inserted.FailCode);
            }
        }

        var readUser = reader.GetUser(desired.DeviceUserId);
        var readFace = reader.GetFace(desired.DeviceUserId);
        if (!readUser.Ok || readUser.User == null || !readFace.Ok || readFace.Bytes == null)
        {
            return WriteStep.Fail(readUser.Error ?? readFace.Error ?? "read-back failed");
        }

        if (!SameUser(readUser.User, desired) || !SameHash(readFace.Bytes, desired.Face))
        {
            return WriteStep.Fail("read-back mismatch");
        }

        return WriteStep.Done();
    }

    private static ReaderUser Record(DesiredMember desired) => new(
        desired.DeviceUserId,
        desired.Name,
        desired.NameEx,
        desired.UserStatus,
        desired.ValidFrom,
        desired.ValidTo,
        desired.Authority,
        desired.DoorNum,
        desired.TimeSectionNum);

    private static bool SameUser(ReaderUser user, DesiredMember desired)
    {
        return user.DeviceUserId == desired.DeviceUserId
            && user.Name == desired.Name
            && user.NameEx == desired.NameEx
            && user.UserStatus == desired.UserStatus
            && user.ValidFrom == desired.ValidFrom
            && user.ValidTo == desired.ValidTo
            && user.Authority == desired.Authority
            && user.DoorNum == desired.DoorNum
            && user.TimeSectionNum == desired.TimeSectionNum;
    }

    private static bool SameHash(byte[] readBack, byte[] sent)
    {
        return CryptographicOperations.FixedTimeEquals(SHA256.HashData(readBack), SHA256.HashData(sent));
    }

    private void ClearRetry()
    {
        lock (_gate)
        {
            using var command = _connection.CreateCommand();
            command.CommandText = "DELETE FROM retry_state WHERE reader_id = $reader";
            command.Parameters.AddWithValue("$reader", _readerId);
            command.ExecuteNonQuery();
        }
    }

    private readonly record struct WriteStep(Verification Verification, bool Occupied)
    {
        public static WriteStep Done() => new(new Verification(true, null), false);

        public static WriteStep Fail(string? error) => new(new Verification(false, error), false);

        public static WriteStep Collision() => new(new Verification(false, FakeReader.FailOccupied), true);
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
