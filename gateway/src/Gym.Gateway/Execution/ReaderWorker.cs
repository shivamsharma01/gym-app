using System.Globalization;
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
    private const string ReaderParam = "$reader";
    private const string RevisionParam = "$revision";
    private const string UserParam = "$user";
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
                command.Parameters.AddWithValue(ReaderParam, _readerId);
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
                command.Parameters.AddWithValue(ReaderParam, _readerId);
                using var row = command.ExecuteReader();
                if (!row.Read())
                {
                    return null;
                }

                return new RetryState(
                    row.GetInt64(0),
                    row.GetInt32(1),
                    DateTimeOffset.Parse(row.GetString(2), CultureInfo.InvariantCulture),
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
                command.Parameters.AddWithValue(ReaderParam, _readerId);
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

    public ApplyOutcome Apply(long revision, Func<IReaderAdapter, Verification> verify, string? deviceUserId = null)
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

            CommitVerified(revision, deviceUserId);
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
        if (desired.ValidFrom is null || desired.ValidTo is null)
        {
            return new MemberApplyResult(MemberApplyKind.Failed, null);
        }

        if (!desired.FacePresent)
        {
            return ApplyFaceCleared(desired);
        }

        if (desired.Face is not { Length: > 0 })
        {
            throw new ArgumentException("The desired member has no face.", nameof(desired));
        }

        WriteStep? written = null;
        var outcome = Apply(desired.Revision, reader =>
        {
            var step = WriteAndReadBack(reader, desired);
            written = step;
            return step.Verification;
        }, desired.DeviceUserId);
        return ToMemberResult(outcome, written, desired.DeviceUserId);
    }

    /// <summary>
    /// Removes the face and leaves the user. A missing photo is already the desired result.
    /// The user is not created when the reader has no record, and the revision is journaled only
    /// after get-user still matches and get-face is the missing-photo result.
    /// </summary>
    private MemberApplyResult ApplyFaceCleared(DesiredMember desired)
    {
        WriteStep? written = null;
        var outcome = Apply(desired.Revision, reader =>
        {
            var step = ClearFace(reader, desired);
            written = step;
            return step.Verification;
        }, desired.DeviceUserId);
        return ToMemberResult(outcome, written, desired.DeviceUserId);
    }

    /// <summary>
    /// Removes the device user, then requires get-user to be NO_RECORD. A missing photo is not that
    /// result, and the shared SDK error without NO_RECORD is not that result.
    /// </summary>
    public MemberApplyResult ApplyRemoval(long revision, string deviceUserId)
    {
        var outcome = Apply(revision, reader =>
        {
            var removed = reader.RemoveUser(deviceUserId);
            if (!removed.Ok)
            {
                return new Verification(false, removed.Error ?? removed.FailCode ?? "remove failed");
            }

            var user = reader.GetUser(deviceUserId);
            if (user.FailCode == FakeReader.FailNoRecord)
            {
                return new Verification(true, null);
            }

            return new Verification(false, user.Ok ? "user still present" : user.FailCode ?? user.Error ?? "not NO_RECORD");
        });
        if (outcome == ApplyOutcome.Verified)
        {
            Forget(deviceUserId);
        }

        return ToMemberResult(outcome, null, "");
    }

    private MemberApplyResult ToMemberResult(ApplyOutcome outcome, WriteStep? written, string deviceUserId)
    {
        if (written is { Occupied: true })
        {
            ClearRetry();
            return new MemberApplyResult(MemberApplyKind.Occupied, deviceUserId);
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

    private WriteStep WriteAndReadBack(IReaderAdapter reader, DesiredMember desired)
    {
        var user = EnsureUser(reader, desired);
        if (user != null)
        {
            return user.Value;
        }

        var face = EnsureFace(reader, desired);
        if (face != null)
        {
            return face.Value;
        }

        return VerifyReadBack(reader, desired);
    }

    private WriteStep ClearFace(IReaderAdapter reader, DesiredMember desired)
    {
        var existing = reader.GetUser(desired.DeviceUserId);
        if (!existing.Ok || existing.User == null)
        {
            return WriteStep.Fail(existing.FailCode ?? existing.Error ?? "user missing");
        }

        if (!SameUser(existing.User, desired))
        {
            if (!ReplacesExisting(existing.User, desired))
            {
                return WriteStep.Fail("user does not match");
            }

            var replaced = reader.ReplaceUser(Record(desired));
            if (!replaced.Ok)
            {
                return WriteStep.Fail(replaced.Error ?? replaced.FailCode);
            }
        }

        var removed = reader.RemoveFace(desired.DeviceUserId);
        if (!removed.Ok)
        {
            return WriteStep.Fail(removed.Error ?? removed.FailCode);
        }

        return VerifyFaceCleared(reader, desired);
    }

    private static WriteStep VerifyFaceCleared(IReaderAdapter reader, DesiredMember desired)
    {
        var readUser = reader.GetUser(desired.DeviceUserId);
        var readFace = reader.GetFace(desired.DeviceUserId);
        if (!readUser.Ok || readUser.User == null || !SameUser(readUser.User, desired))
        {
            return WriteStep.Fail(readUser.FailCode ?? "user read-back mismatch");
        }

        if (readFace.Ok && readFace.Bytes is { Length: > 0 })
        {
            return WriteStep.Fail("face still present");
        }

        if (readFace.FailCode == FakeReader.FailNoRecord)
        {
            return WriteStep.Fail("user missing");
        }

        if (readFace.FailCode != FakeReader.FailUnknown)
        {
            return WriteStep.Fail(readFace.Error ?? readFace.FailCode ?? "face read-back failed");
        }

        return WriteStep.Done();
    }

    private WriteStep? EnsureUser(IReaderAdapter reader, DesiredMember desired)
    {
        var existing = reader.GetUser(desired.DeviceUserId);
        if (existing.Ok)
        {
            if (SameUser(existing.User!, desired))
            {
                return null;
            }

            if (ReplacesExisting(existing.User!, desired))
            {
                var replaced = reader.ReplaceUser(Record(desired));
                if (replaced.Ok)
                {
                    Remember(desired.DeviceUserId);
                    return null;
                }

                return WriteStep.Fail(replaced.Error ?? replaced.FailCode);
            }

            var occupied = reader.CreateUser(Record(desired));
            return occupied.FailCode == FakeReader.FailOccupied
                ? WriteStep.Collision()
                : WriteStep.Fail(occupied.Error ?? occupied.FailCode);
        }

        if (existing.FailCode == FakeReader.FailNoRecord)
        {
            var created = reader.CreateUser(Record(desired));
            if (created.Ok)
            {
                Remember(desired.DeviceUserId);
                return null;
            }

            return created.FailCode == FakeReader.FailOccupied
                ? WriteStep.Collision()
                : WriteStep.Fail(created.Error);
        }

        return WriteStep.Fail(existing.Error);
    }

    private static WriteStep? EnsureFace(IReaderAdapter reader, DesiredMember desired)
    {
        var face = reader.GetFace(desired.DeviceUserId);
        if (face.Ok && face.Bytes is { Length: > 0 })
        {
            return SameHash(face.Bytes, desired.Face) ? null : ReplaceFace(reader, desired);
        }

        if (!face.Ok && face.FailCode is not (FakeReader.FailUnknown or FakeReader.FailNoRecord))
        {
            return WriteStep.Fail(face.Error);
        }

        return InsertFace(reader, desired);
    }

    private static WriteStep? ReplaceFace(IReaderAdapter reader, DesiredMember desired)
    {
        var updated = reader.UpdateFace(desired.DeviceUserId, desired.Face);
        if (updated.Ok)
        {
            return null;
        }

        reader.RemoveFace(desired.DeviceUserId);
        return InsertFace(reader, desired);
    }

    private static WriteStep? InsertFace(IReaderAdapter reader, DesiredMember desired)
    {
        var inserted = reader.InsertFace(desired.DeviceUserId, desired.Face);
        if (inserted.Ok)
        {
            return null;
        }

        var error = inserted.FailCode == FakeReader.FailPhotoExist
            ? FakeReader.FailPhotoExist
            : inserted.Error ?? inserted.FailCode;
        return WriteStep.Fail(error);
    }

    private static WriteStep VerifyReadBack(IReaderAdapter reader, DesiredMember desired)
    {
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

    private bool ReplacesExisting(ReaderUser user, DesiredMember desired) =>
        SamePerson(user, desired) || Owns(desired.DeviceUserId) || desired.KeepDeviceUserId;

    private static bool SamePerson(ReaderUser user, DesiredMember desired)
    {
        return user.DeviceUserId == desired.DeviceUserId
            && user.Name == desired.Name
            && user.NameEx == desired.NameEx;
    }

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
            command.Parameters.AddWithValue(ReaderParam, _readerId);
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
            command.Parameters.AddWithValue(ReaderParam, _readerId);
            command.Parameters.AddWithValue(RevisionParam, revision);
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
            CREATE TABLE IF NOT EXISTS applied_user (
                reader_id TEXT NOT NULL,
                device_user_id TEXT NOT NULL,
                PRIMARY KEY (reader_id, device_user_id)
            );
            """;
        command.ExecuteNonQuery();
    }

    private long AppliedRevisionUnlocked()
    {
        using var command = _connection.CreateCommand();
        command.CommandText = "SELECT revision FROM applied_revision WHERE reader_id = $reader";
        command.Parameters.AddWithValue(ReaderParam, _readerId);
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
        command.Parameters.AddWithValue(ReaderParam, _readerId);
        using var row = command.ExecuteReader();
        if (!row.Read())
        {
            return null;
        }

        return new RetryState(
            row.GetInt64(0),
            row.GetInt32(1),
            DateTimeOffset.Parse(row.GetString(2), CultureInfo.InvariantCulture),
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
        command.Parameters.AddWithValue(ReaderParam, _readerId);
        command.Parameters.AddWithValue(RevisionParam, revision);
        command.Parameters.AddWithValue("$attempts", previousAttempts + 1);
        command.Parameters.AddWithValue("$next", next.ToString("o"));
        command.Parameters.AddWithValue("$error", error);
        command.ExecuteNonQuery();
    }

    public bool HasWritten(string deviceUserId)
    {
        lock (_gate)
        {
            return Owns(deviceUserId);
        }
    }

    public IReadOnlyList<string> AppliedUserIds()
    {
        lock (_gate)
        {
            using var command = _connection.CreateCommand();
            command.CommandText = """
                SELECT device_user_id FROM applied_user
                WHERE reader_id = $reader
                ORDER BY device_user_id
                """;
            command.Parameters.AddWithValue(ReaderParam, _readerId);
            using var row = command.ExecuteReader();
            var ids = new List<string>();
            while (row.Read())
            {
                ids.Add(row.GetString(0));
            }

            return ids;
        }
    }

    private bool Owns(string deviceUserId)
    {
        using var command = _connection.CreateCommand();
        command.CommandText = """
            SELECT 1 FROM applied_user
            WHERE reader_id = $reader AND device_user_id = $user
            """;
        command.Parameters.AddWithValue(ReaderParam, _readerId);
        command.Parameters.AddWithValue(UserParam, deviceUserId);
        return command.ExecuteScalar() != null;
    }

    private void Remember(string deviceUserId)
    {
        using var command = _connection.CreateCommand();
        command.CommandText = """
            INSERT INTO applied_user (reader_id, device_user_id) VALUES ($reader, $user)
            ON CONFLICT (reader_id, device_user_id) DO NOTHING
            """;
        command.Parameters.AddWithValue(ReaderParam, _readerId);
        command.Parameters.AddWithValue(UserParam, deviceUserId);
        command.ExecuteNonQuery();
    }

    private void Forget(string deviceUserId)
    {
        lock (_gate)
        {
            using var command = _connection.CreateCommand();
            command.CommandText = """
                DELETE FROM applied_user
                WHERE reader_id = $reader AND device_user_id = $user
                """;
            command.Parameters.AddWithValue(ReaderParam, _readerId);
            command.Parameters.AddWithValue(UserParam, deviceUserId);
            command.ExecuteNonQuery();
        }
    }

    private void CommitVerified(long revision, string? deviceUserId)
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
            applied.Parameters.AddWithValue(ReaderParam, _readerId);
            applied.Parameters.AddWithValue(RevisionParam, revision);
            applied.ExecuteNonQuery();
        }

        using (var ack = _connection.CreateCommand())
        {
            ack.Transaction = transaction;
            ack.CommandText = """
                INSERT INTO revision_ack (reader_id, revision) VALUES ($reader, $revision)
                ON CONFLICT (reader_id, revision) DO NOTHING
                """;
            ack.Parameters.AddWithValue(ReaderParam, _readerId);
            ack.Parameters.AddWithValue(RevisionParam, revision);
            ack.ExecuteNonQuery();
        }

        using (var retry = _connection.CreateCommand())
        {
            retry.Transaction = transaction;
            retry.CommandText = "DELETE FROM retry_state WHERE reader_id = $reader";
            retry.Parameters.AddWithValue(ReaderParam, _readerId);
            retry.ExecuteNonQuery();
        }

        if (!string.IsNullOrEmpty(deviceUserId))
        {
            using var owned = _connection.CreateCommand();
            owned.Transaction = transaction;
            owned.CommandText = """
                INSERT INTO applied_user (reader_id, device_user_id) VALUES ($reader, $user)
                ON CONFLICT (reader_id, device_user_id) DO NOTHING
                """;
            owned.Parameters.AddWithValue(ReaderParam, _readerId);
            owned.Parameters.AddWithValue(UserParam, deviceUserId);
            owned.ExecuteNonQuery();
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
