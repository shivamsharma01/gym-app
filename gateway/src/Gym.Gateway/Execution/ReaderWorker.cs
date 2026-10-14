using System.Globalization;
using System.Security.Cryptography;
using Gym.Gateway.Adapters;
using Microsoft.Data.Sqlite;

namespace Gym.Gateway.Execution;

/// <summary>
/// Execution state for one reader. The SQLite file remembers the verified revision, the ack that
/// still has to be sent, retry metadata, and the record it last wrote at each id. It does not record
/// heartbeats and it does not decide which member wins. A write or removal goes ahead only while the
/// reader shows the record the server expects at the id, or the record this gateway last wrote there.
/// Device calls go through <see cref="IReaderAdapter"/>.
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
    /// Creates or replaces the user and inserts the face, then reads both back. The revision is
    /// journaled only when the read-back matches. Someone unexpected at the id is reported and
    /// nothing is written over it.
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
    /// Removes the face and leaves the user. A missing photo is already the desired result. An
    /// empty id the server expects to be empty gets the user without a photo. The revision is
    /// journaled only after get-user matches and get-face is the missing-photo result.
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
    /// Removes the device user the server expects at the id, then requires get-user to be
    /// NO_RECORD. An id that is already empty is the desired result. Someone else at the id is
    /// reported and not removed. A missing photo is not NO_RECORD, and the shared SDK error without
    /// NO_RECORD is not that result.
    /// </summary>
    public MemberApplyResult ApplyRemoval(long revision, string deviceUserId, ExpectedOccupant? expected = null)
    {
        WriteStep? written = null;
        var outcome = Apply(revision, reader =>
        {
            var step = Remove(reader, deviceUserId, expected ?? ExpectedOccupant.Absent);
            written = step;
            return step.Verification;
        });
        if (outcome == ApplyOutcome.Verified)
        {
            Forget(deviceUserId);
        }

        return ToMemberResult(outcome, written, deviceUserId);
    }

    private WriteStep Remove(IReaderAdapter reader, string deviceUserId, ExpectedOccupant expected)
    {
        var (now, error) = ReadOccupant(reader, deviceUserId);
        if (now == null)
        {
            return WriteStep.Fail(error);
        }

        if (!now.Present)
        {
            return WriteStep.Done();
        }

        if (!expected.IsShownBy(now) && !WroteHere(deviceUserId, now))
        {
            return WriteStep.Unexpected(now);
        }

        var removed = reader.RemoveUser(deviceUserId);
        if (!removed.Ok)
        {
            return WriteStep.Fail(removed.Error ?? removed.FailCode ?? "remove failed");
        }

        var user = reader.GetUser(deviceUserId);
        if (user.FailCode == FakeReader.FailNoRecord)
        {
            return WriteStep.Done();
        }

        return WriteStep.Fail(user.Ok ? "user still present" : user.FailCode ?? user.Error ?? "not NO_RECORD");
    }

    private MemberApplyResult ToMemberResult(ApplyOutcome outcome, WriteStep? written, string deviceUserId)
    {
        if (written is { Occupied: true })
        {
            ClearRetry();
            return new MemberApplyResult(MemberApplyKind.Occupied, deviceUserId);
        }

        if (written is { Shown: { } occupant })
        {
            ClearRetry();
            return new MemberApplyResult(MemberApplyKind.OccupantChanged, deviceUserId, occupant);
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
        var (now, error) = ReadOccupant(reader, desired.DeviceUserId);
        if (now == null)
        {
            return WriteStep.Fail(error);
        }

        var faceHash = FaceHash.Sha256Hex(desired.Face);
        var refused = Precondition(now, desired, now.Present && SameUser(now.User!, desired) && now.FaceSha256 == faceHash);
        if (refused != null)
        {
            return refused.Value;
        }

        var user = EnsureUser(reader, desired, now);
        if (user != null)
        {
            return user.Value;
        }

        var face = EnsureFace(reader, desired);
        if (face != null)
        {
            return face.Value;
        }

        var verified = VerifyReadBack(reader, desired);
        if (verified.Verification.Verified)
        {
            Remember(desired, faceHash);
        }

        return verified;
    }

    private WriteStep ClearFace(IReaderAdapter reader, DesiredMember desired)
    {
        var (now, error) = ReadOccupant(reader, desired.DeviceUserId);
        if (now == null)
        {
            return WriteStep.Fail(error);
        }

        var refused = Precondition(now, desired, now.Present && SameUser(now.User!, desired) && now.FaceSha256.Length == 0);
        if (refused != null)
        {
            return refused.Value;
        }

        var user = EnsureUser(reader, desired, now);
        if (user != null)
        {
            return user.Value;
        }

        if (now.FaceSha256.Length > 0)
        {
            var removed = reader.RemoveFace(desired.DeviceUserId);
            if (!removed.Ok)
            {
                return WriteStep.Fail(removed.Error ?? removed.FailCode);
            }
        }

        var verified = VerifyFaceCleared(reader, desired);
        if (verified.Verification.Verified)
        {
            Remember(desired, "");
        }

        return verified;
    }

    /// <summary>
    /// What the id holds now. NO_RECORD is no one; a user without a photo has the empty hash.
    /// Any other failure is a read error and nothing is decided from it.
    /// </summary>
    private static (ReaderOccupant? Occupant, string? Error) ReadOccupant(IReaderAdapter reader, string deviceUserId)
    {
        var user = reader.GetUser(deviceUserId);
        if (user.FailCode == FakeReader.FailNoRecord)
        {
            return (ReaderOccupant.Nobody, null);
        }

        if (!user.Ok || user.User == null)
        {
            return (null, user.Error ?? user.FailCode ?? "user read failed");
        }

        var face = reader.GetFace(deviceUserId);
        if (face.Ok && face.Bytes is { Length: > 0 })
        {
            return (new ReaderOccupant(true, user.User, FaceHash.Sha256Hex(face.Bytes)), null);
        }

        if (face.Ok || face.FailCode is FakeReader.FailUnknown or FakeReader.FailNoRecord)
        {
            return (new ReaderOccupant(true, user.User, ""), null);
        }

        return (null, face.Error ?? face.FailCode ?? "face read failed");
    }

    /// <summary>
    /// Null when the write may go ahead: the reader already shows the desired record, or shows what
    /// the server expects, or what this gateway last wrote at the id. Someone at an id the server
    /// expects to be empty is an occupied id; anything else is reported as it is.
    /// </summary>
    private WriteStep? Precondition(ReaderOccupant now, DesiredMember desired, bool alreadyDesired)
    {
        if (alreadyDesired)
        {
            return null;
        }

        var expected = desired.Expected ?? ExpectedOccupant.Absent;
        if (!now.Present)
        {
            return expected.Present ? WriteStep.Unexpected(now) : null;
        }

        if (expected.IsShownBy(now) || WroteHere(desired.DeviceUserId, now))
        {
            return null;
        }

        return expected.Present ? WriteStep.Unexpected(now) : WriteStep.Collision();
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

    /// <summary>Call only after <see cref="Precondition"/> allowed the write.</summary>
    private WriteStep? EnsureUser(IReaderAdapter reader, DesiredMember desired, ReaderOccupant now)
    {
        if (now.Present)
        {
            if (SameUser(now.User!, desired))
            {
                return null;
            }

            var replaced = reader.ReplaceUser(Record(desired));
            if (!replaced.Ok)
            {
                return WriteStep.Fail(replaced.Error ?? replaced.FailCode);
            }

            Remember(desired, null);
            return null;
        }

        var created = reader.CreateUser(Record(desired));
        if (created.Ok)
        {
            Remember(desired, null);
            return null;
        }

        return created.FailCode == FakeReader.FailOccupied
            ? WriteStep.Collision()
            : WriteStep.Fail(created.Error ?? created.FailCode);
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

    private readonly record struct WriteStep(Verification Verification, bool Occupied, ReaderOccupant? Shown = null)
    {
        public static WriteStep Done() => new(new Verification(true, null), false);

        public static WriteStep Fail(string? error) => new(new Verification(false, error), false);

        public static WriteStep Collision() => new(new Verification(false, FakeReader.FailOccupied), true);

        public static WriteStep Unexpected(ReaderOccupant occupant) =>
            new(new Verification(false, "the reader shows a different record at this id"), false, occupant);
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
        AddWrittenRecordColumns();
    }

    /// <summary>A journal from before the written record was kept gets the columns, empty.</summary>
    private void AddWrittenRecordColumns()
    {
        var existing = new HashSet<string>(StringComparer.Ordinal);
        using (var info = _connection.CreateCommand())
        {
            info.CommandText = "PRAGMA table_info(applied_user)";
            using var row = info.ExecuteReader();
            while (row.Read())
            {
                existing.Add(row.GetString(1));
            }
        }

        foreach (var column in new[] { "name", "name_ex", "user_status", "valid_from", "valid_to", "authority", "face_sha256" })
        {
            if (existing.Contains(column))
            {
                continue;
            }

            using var alter = _connection.CreateCommand();
            alter.CommandText = $"ALTER TABLE applied_user ADD COLUMN {column} {(column == "user_status" ? "INTEGER" : "TEXT")}";
            alter.ExecuteNonQuery();
        }
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

    /// <summary>
    /// The reader shows exactly the record this gateway last wrote at the id. The photo counts once
    /// a read-back verified it. That a gateway once wrote the id is not enough.
    /// </summary>
    private bool WroteHere(string deviceUserId, ReaderOccupant now)
    {
        using var command = _connection.CreateCommand();
        command.CommandText = """
            SELECT name, name_ex, user_status, valid_from, valid_to, authority, face_sha256
            FROM applied_user WHERE reader_id = $reader AND device_user_id = $user
            """;
        command.Parameters.AddWithValue(ReaderParam, _readerId);
        command.Parameters.AddWithValue(UserParam, deviceUserId);
        using var row = command.ExecuteReader();
        if (!row.Read() || row.IsDBNull(0) || row.IsDBNull(2) || row.IsDBNull(3) || row.IsDBNull(4))
        {
            return false;
        }

        var written = new ExpectedOccupant(
            true,
            row.GetString(0),
            row.IsDBNull(1) ? null : row.GetString(1),
            row.GetInt32(2),
            row.GetString(3),
            row.GetString(4),
            row.IsDBNull(5) ? null : row.GetString(5),
            row.IsDBNull(6) ? null : row.GetString(6));
        return written.IsShownBy(now);
    }

    /// <summary>The record just written at the id; <paramref name="faceSha256"/> null until a read-back verified the photo.</summary>
    private void Remember(DesiredMember desired, string? faceSha256)
    {
        using var command = _connection.CreateCommand();
        command.CommandText = """
            INSERT INTO applied_user (reader_id, device_user_id, name, name_ex, user_status, valid_from, valid_to, authority, face_sha256)
            VALUES ($reader, $user, $name, $nameEx, $status, $from, $to, $authority, $face)
            ON CONFLICT (reader_id, device_user_id) DO UPDATE SET
                name = excluded.name,
                name_ex = excluded.name_ex,
                user_status = excluded.user_status,
                valid_from = excluded.valid_from,
                valid_to = excluded.valid_to,
                authority = excluded.authority,
                face_sha256 = excluded.face_sha256
            """;
        command.Parameters.AddWithValue(ReaderParam, _readerId);
        command.Parameters.AddWithValue(UserParam, desired.DeviceUserId);
        command.Parameters.AddWithValue("$name", desired.Name);
        command.Parameters.AddWithValue("$nameEx", (object?)desired.NameEx ?? DBNull.Value);
        command.Parameters.AddWithValue("$status", desired.UserStatus);
        command.Parameters.AddWithValue("$from", desired.ValidFrom is { } from ? ReaderLocalTime.Format(from) : (object)DBNull.Value);
        command.Parameters.AddWithValue("$to", desired.ValidTo is { } to ? ReaderLocalTime.Format(to) : (object)DBNull.Value);
        command.Parameters.AddWithValue("$authority", desired.Authority);
        command.Parameters.AddWithValue("$face", (object?)faceSha256 ?? DBNull.Value);
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
