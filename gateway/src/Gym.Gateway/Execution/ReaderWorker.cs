using System.Globalization;
using System.Security.Cryptography;
using Gym.Gateway.Adapters;
using Microsoft.Data.Sqlite;

namespace Gym.Gateway.Execution;

/// <summary>
/// Execution state for one reader. The SQLite file remembers the applied cursor, revisions verified
/// above it, the acks that still have to be sent, one attempt row per revision that has not
/// succeeded, and the record it last wrote at each id. It does not record heartbeats and it does
/// not decide which member wins. A write or removal goes ahead only while the reader shows the
/// record the server expects at the id, or the record this gateway last wrote there.
/// Device calls go through <see cref="IReaderAdapter"/>.
/// </summary>
/// <remarks>
/// The applied cursor is the highest revision at or below which every pulled revision was verified,
/// reported to the server, or dropped by the server. A revision that fails keeps the cursor below
/// it and is retried with backoff, while newer revisions of other ids still go ahead and are kept
/// in the verified set until the cursor passes them.
/// </remarks>
public sealed class ReaderWorker : IDisposable
{
    /// <summary>Failures of one revision before it is reported as exhausted.</summary>
    public const int ExhaustAfter = 6;

    /// <summary>Longest wait between two attempts of one revision, and the wait once exhausted.</summary>
    public static readonly TimeSpan MaxRetryDelay = TimeSpan.FromMinutes(10);

    private readonly IReaderAdapter _reader;
    private readonly string _readerId;
    private readonly Func<DateTimeOffset> _clock;
    private readonly TimeSpan _retryDelay;
    private readonly SqliteConnection _connection;
    private const string ReaderParam = "$reader";
    private const string RevisionParam = "$revision";
    private const string UserParam = "$user";
    private readonly object _gate = new();
    private readonly Func<bool> _online;
    private bool _wasOffline;
    private string? _health;

    /// <param name="online">
    /// False while the reader's session is down. An attempt is not made then, and a failure seen
    /// while it is down does not count towards <see cref="ExhaustAfter"/>.
    /// </param>
    public ReaderWorker(
        string readerId,
        IReaderAdapter reader,
        string journalPath,
        TimeSpan? retryDelay = null,
        Func<DateTimeOffset>? clock = null,
        Func<bool>? online = null)
    {
        if (string.IsNullOrWhiteSpace(readerId))
        {
            throw new ArgumentException("A reader id is required.", nameof(readerId));
        }

        _readerId = readerId;
        _reader = reader ?? throw new ArgumentNullException(nameof(reader));
        _retryDelay = retryDelay ?? TimeSpan.FromSeconds(1);
        _clock = clock ?? (() => DateTimeOffset.UtcNow);
        _online = online ?? (() => true);
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

    public DateTimeOffset Now => _clock();

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

    /// <summary>The lowest revision that has not succeeded, or null.</summary>
    public RetryState? Retry
    {
        get
        {
            lock (_gate)
            {
                return AttemptsUnlocked(null).FirstOrDefault();
            }
        }
    }

    /// <summary>Every revision that has not succeeded, lowest first.</summary>
    public IReadOnlyList<RetryState> Attempts
    {
        get
        {
            lock (_gate)
            {
                return AttemptsUnlocked(null);
            }
        }
    }

    public RetryState? Attempt(long revision)
    {
        lock (_gate)
        {
            return AttemptsUnlocked(revision).FirstOrDefault();
        }
    }

    /// <summary>Verified at or below the cursor, or above it while an older revision is unresolved.</summary>
    public bool IsVerified(long revision)
    {
        lock (_gate)
        {
            return IsVerifiedUnlocked(revision);
        }
    }

    /// <summary>A revision whose next attempt is due now. Nothing is due while the reader is offline.</summary>
    public bool HasDueAttempt()
    {
        if (!ReaderOnline)
        {
            return false;
        }

        var now = _clock();
        return Attempts.Any(attempt => attempt.NextAttempt <= now);
    }

    /// <summary>
    /// Whether the reader's session is up. When it comes back, every failed revision that is not
    /// rejected is due again at once, exhausted ones included.
    /// </summary>
    public bool ReaderOnline
    {
        get
        {
            lock (_gate)
            {
                return OnlineUnlocked();
            }
        }
    }

    private bool OnlineUnlocked()
    {
        if (!_online())
        {
            _wasOffline = true;
            return false;
        }

        if (_wasOffline)
        {
            _wasOffline = false;
            using var command = _connection.CreateCommand();
            command.CommandText = """
                UPDATE revision_attempt SET next_attempt = $now
                WHERE reader_id = $reader AND state IN ('Retry', 'Exhausted')
                """;
            command.Parameters.AddWithValue(ReaderParam, _readerId);
            command.Parameters.AddWithValue("$now", _clock().ToString("o", CultureInfo.InvariantCulture));
            command.ExecuteNonQuery();
        }

        return true;
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
            if (IsVerifiedUnlocked(revision))
            {
                return ApplyOutcome.AlreadyVerified;
            }

            var retry = AttemptsUnlocked(revision).FirstOrDefault();
            if (!OnlineUnlocked())
            {
                RecordOffline(revision, deviceUserId, retry, "the reader is offline");
                return ApplyOutcome.ReaderOffline;
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
                var error = verification.Error ?? "not verified";
                if (!OnlineUnlocked())
                {
                    RecordOffline(revision, deviceUserId, retry, "the reader went offline: " + error);
                    return ApplyOutcome.ReaderOffline;
                }

                RecordFailure(revision, deviceUserId, retry, error, false);
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
            return Rejected(desired.Revision, desired.DeviceUserId, "the desired member has no validity dates");
        }

        if (!desired.FacePresent)
        {
            return ApplyFaceCleared(desired);
        }

        if (desired.Face is not { Length: > 0 })
        {
            return Rejected(desired.Revision, desired.DeviceUserId, "the desired member has no face");
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
        }, deviceUserId);
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

    /// <summary>
    /// An occupied id or an unexpected occupant keeps its attempt until the caller has told the
    /// server and calls <see cref="Settle"/>; a report that fails is retried like any failure.
    /// </summary>
    private static MemberApplyResult ToMemberResult(ApplyOutcome outcome, WriteStep? written, string deviceUserId)
    {
        if (written is { Occupied: true })
        {
            return new MemberApplyResult(MemberApplyKind.Occupied, deviceUserId);
        }

        if (written is { Shown: { } occupant })
        {
            return new MemberApplyResult(MemberApplyKind.OccupantChanged, deviceUserId, occupant);
        }

        var kind = outcome switch
        {
            ApplyOutcome.Verified => MemberApplyKind.Applied,
            ApplyOutcome.AlreadyVerified => MemberApplyKind.AlreadyApplied,
            ApplyOutcome.WaitingToRetry or ApplyOutcome.ReaderOffline => MemberApplyKind.Waiting,
            _ => MemberApplyKind.Failed
        };
        return new MemberApplyResult(kind, null);
    }

    private MemberApplyResult Rejected(long revision, string deviceUserId, string error)
    {
        if (IsVerified(revision))
        {
            return new MemberApplyResult(MemberApplyKind.AlreadyApplied, null);
        }

        Reject(revision, deviceUserId, error);
        return new MemberApplyResult(MemberApplyKind.Failed, error);
    }

    /// <summary>
    /// The revision cannot be applied as sent. It is not retried until the server replaces it or
    /// the gateway restarts.
    /// </summary>
    public RetryState Reject(long revision, string? deviceUserId, string error)
    {
        lock (_gate)
        {
            return RecordFailure(revision, deviceUserId, AttemptsUnlocked(revision).FirstOrDefault(), error, true);
        }
    }

    /// <summary>
    /// The revision needs no further attempt: the server took the report, or dropped or replaced
    /// the revision. The cursor moves up to the next unresolved revision.
    /// </summary>
    public void Settle(long revision)
    {
        lock (_gate)
        {
            using var transaction = _connection.BeginTransaction();
            using (var delete = _connection.CreateCommand())
            {
                delete.Transaction = transaction;
                delete.CommandText = "DELETE FROM revision_attempt WHERE reader_id = $reader AND revision = $revision";
                delete.Parameters.AddWithValue(ReaderParam, _readerId);
                delete.Parameters.AddWithValue(RevisionParam, revision);
                delete.ExecuteNonQuery();
            }

            AdvanceUnlocked(transaction);
            transaction.Commit();
        }
    }

    /// <summary>The server has the exhausted or rejected state of this attempt.</summary>
    public void MarkReported(long revision)
    {
        lock (_gate)
        {
            using var command = _connection.CreateCommand();
            command.CommandText = "UPDATE revision_attempt SET reported = 1 WHERE reader_id = $reader AND revision = $revision";
            command.Parameters.AddWithValue(ReaderParam, _readerId);
            command.Parameters.AddWithValue(RevisionParam, revision);
            command.ExecuteNonQuery();
        }
    }

    /// <summary>
    /// The server refused the acknowledgement of a revision verified here. The revision is no longer
    /// treated as applied: the ack is dropped, the cursor goes back below it, and it is retried.
    /// </summary>
    public RetryState Unverify(long revision, string? deviceUserId, string error)
    {
        lock (_gate)
        {
            using (var transaction = _connection.BeginTransaction())
            {
                foreach (var sql in new[]
                         {
                             "DELETE FROM revision_ack WHERE reader_id = $reader AND revision = $revision",
                             "DELETE FROM verified_revision WHERE reader_id = $reader AND revision = $revision",
                             "UPDATE applied_revision SET revision = $revision - 1 WHERE reader_id = $reader AND revision >= $revision"
                         })
                {
                    using var command = _connection.CreateCommand();
                    command.Transaction = transaction;
                    command.CommandText = sql;
                    command.Parameters.AddWithValue(ReaderParam, _readerId);
                    command.Parameters.AddWithValue(RevisionParam, revision);
                    command.ExecuteNonQuery();
                }

                transaction.Commit();
            }

            return RecordFailure(revision, deviceUserId, AttemptsUnlocked(revision).FirstOrDefault(), error, false);
        }
    }

    private WriteStep WriteAndReadBack(IReaderAdapter reader, DesiredMember desired)
    {
        var (now, error) = ReadOccupant(reader, desired.DeviceUserId);
        if (now == null)
        {
            return WriteStep.Fail(error);
        }

        // The desired record without its photo is this write's own earlier attempt: it is finished here,
        // never moved to another id, which would leave a copy of the person behind.
        var refused = Precondition(now, desired, now.Present && SameUser(now.User!, desired));
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

        return VerifyReadBack(reader, desired);
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

    private WriteStep? EnsureFace(IReaderAdapter reader, DesiredMember desired)
    {
        var face = reader.GetFace(desired.DeviceUserId);
        if (face.Ok && face.Bytes is { Length: > 0 })
        {
            if (SameHash(face.Bytes, desired.Face))
            {
                return null;
            }

            var (now, _) = ReadOccupant(reader, desired.DeviceUserId);
            if (now is { Present: true, User: not null }
                && SameUser(now.User, desired)
                && IsJpeg(face.Bytes)
                && WroteHere(desired.DeviceUserId, now)
                && SameSentFace(desired))
            {
                return null;
            }

            return ReplaceFace(reader, desired);
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

    private WriteStep VerifyReadBack(IReaderAdapter reader, DesiredMember desired)
    {
        var readUser = reader.GetUser(desired.DeviceUserId);
        var readFace = reader.GetFace(desired.DeviceUserId);
        if (!readUser.Ok || readUser.User == null || !readFace.Ok || readFace.Bytes == null)
        {
            return WriteStep.Fail(readUser.Error ?? readFace.Error ?? "read-back failed");
        }

        var differs = Differences(readUser.User, desired);
        if (differs.Count > 0)
        {
            return WriteStep.Fail("verification failed (reason=field-mismatch: " + string.Join(", ", differs) + ")");
        }

        if (SameHash(readFace.Bytes, desired.Face))
        {
            Remember(desired, FaceHash.Sha256Hex(desired.Face));
            return WriteStep.Done();
        }

        if (readFace.Bytes.Length == 0)
        {
            return WriteStep.Fail("verification failed (reason=photo-missing-after-write)");
        }

        if (!IsJpeg(readFace.Bytes))
        {
            return WriteStep.Fail(
                $"verification failed (reason=photo-not-readable-jpeg; bytesBack={readFace.Bytes.Length}; bytesSent={desired.Face?.Length ?? 0})");
        }

        Remember(desired, FaceHash.Sha256Hex(readFace.Bytes));
        return WriteStep.Done();
    }

    internal static bool IsJpeg(byte[] bytes) =>
        bytes.Length >= 3 && bytes[0] == 0xFF && bytes[1] == 0xD8 && bytes[2] == 0xFF;

    /// <summary>True when the reader user matches desired the same way apply/verify does.</summary>
    internal bool UserMatchesDesired(ReaderUser user, DesiredMember desired) =>
        SameUser(user, desired);

    private static List<string> Differences(ReaderUser user, DesiredMember desired)
    {
        var differs = new List<string>();
        if (!NameMatches(user.Name, desired.Name, desired.NameEx))
        {
            differs.Add($"name '{user.Name}' != '{desired.Name}'/'{desired.NameEx}'");
        }

        if (!NameExMatches(user.NameEx, desired.NameEx, user.Name))
        {
            differs.Add($"nameEx '{user.NameEx}' != '{desired.NameEx}'");
        }

        Compare(differs, "userStatus", user.UserStatus, desired.UserStatus);
        CompareDay(differs, "validFrom", user.ValidFrom, desired.ValidFrom);
        CompareDay(differs, "validTo", user.ValidTo, desired.ValidTo);
        Compare(differs, "authority", user.Authority, desired.Authority);
        Compare(differs, "doorNum", user.DoorNum, desired.DoorNum);
        Compare(differs, "timeSectionNum", user.TimeSectionNum, desired.TimeSectionNum);
        return differs;
    }

    /// <summary>
    /// TrueFace may return the long name in {@code name}; accept short desired name or nameEx.
    /// </summary>
    private static bool NameMatches(string? reported, string desiredName, string? desiredNameEx) =>
        string.Equals(reported, desiredName, StringComparison.Ordinal)
        || (!string.IsNullOrWhiteSpace(desiredNameEx)
            && string.Equals(reported, desiredNameEx, StringComparison.Ordinal));

    private static bool NameExMatches(string? reported, string? desiredNameEx, string? reportedName)
    {
        var left = string.IsNullOrWhiteSpace(reported) ? null : reported;
        var right = string.IsNullOrWhiteSpace(desiredNameEx) ? null : desiredNameEx;
        if (string.Equals(left, right, StringComparison.Ordinal))
        {
            return true;
        }

        return left is null && right is not null && string.Equals(right, reportedName, StringComparison.Ordinal);
    }

    /// <summary>
    /// Validity is a day on the reader clock: the adapter writes the end as 23:59:59 and a reader may
    /// keep only the day, so the time of day is not part of the record.
    /// </summary>
    private static void CompareDay(List<string> differs, string field, DateTimeOffset? read, DateTimeOffset? sent)
    {
        var readDay = read.HasValue ? ReaderLocalClock.Wall(read.Value).Date : (DateTime?)null;
        var sentDay = sent.HasValue ? ReaderLocalClock.Wall(sent.Value).Date : (DateTime?)null;
        if (readDay != sentDay)
        {
            differs.Add($"{field} '{read:yyyy-MM-dd HH:mm:ss}' != '{sent:yyyy-MM-dd HH:mm:ss}'");
        }
    }

    private static void Compare<T>(List<string> differs, string field, T read, T sent)
    {
        if (!EqualityComparer<T>.Default.Equals(read, sent))
        {
            differs.Add($"{field} '{read}' != '{sent}'");
        }
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

    private static bool SameUser(ReaderUser user, DesiredMember desired) =>
        user.DeviceUserId == desired.DeviceUserId && Differences(user, desired).Count == 0;

    private static bool SameHash(byte[] readBack, byte[] sent)
    {
        return CryptographicOperations.FixedTimeEquals(SHA256.HashData(readBack), SHA256.HashData(sent));
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

    /// <summary>
    /// The newest scan of this reader: the journal it belongs to and its number. A read-back or an
    /// occupant read taken now comes after every scan up to this one.
    /// </summary>
    public ScanStamp CurrentScan
    {
        get
        {
            lock (_gate)
            {
                using var command = _connection.CreateCommand();
                command.CommandText = "SELECT epoch, seq FROM observation_scan WHERE reader_id = $reader";
                command.Parameters.AddWithValue(ReaderParam, _readerId);
                using var row = command.ExecuteReader();
                row.Read();
                return new ScanStamp(row.GetString(0), row.GetInt64(1));
            }
        }
    }

    /// <summary>
    /// True when this journal had no scan numbering for the reader when it was opened: it is new,
    /// or it predates numbering. Observations queued before it cannot be placed in its order.
    /// </summary>
    public bool IsNewJournal { get; private set; }

    /// <summary>
    /// Numbers the next scan, durably, before the reader is read for it. Call only while no write
    /// or read-back of this reader runs, so a higher number is always a later look at the reader.
    /// </summary>
    public ScanStamp BeginScan()
    {
        lock (_gate)
        {
            using var command = _connection.CreateCommand();
            command.CommandText = "UPDATE observation_scan SET seq = seq + 1 WHERE reader_id = $reader";
            command.Parameters.AddWithValue(ReaderParam, _readerId);
            command.ExecuteNonQuery();
        }

        return CurrentScan;
    }

    private void StartScanJournal()
    {
        using var command = _connection.CreateCommand();
        command.CommandText = "INSERT OR IGNORE INTO observation_scan (reader_id, epoch, seq) VALUES ($reader, $epoch, 0)";
        command.Parameters.AddWithValue(ReaderParam, _readerId);
        command.Parameters.AddWithValue("$epoch", Guid.NewGuid().ToString("N"));
        IsNewJournal = command.ExecuteNonQuery() == 1;
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
            CREATE TABLE IF NOT EXISTS revision_attempt (
                reader_id TEXT NOT NULL,
                revision INTEGER NOT NULL,
                device_user_id TEXT,
                attempt_count INTEGER NOT NULL,
                next_attempt TEXT NOT NULL,
                last_error TEXT NOT NULL,
                state TEXT NOT NULL,
                reported INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (reader_id, revision)
            );
            CREATE TABLE IF NOT EXISTS verified_revision (
                reader_id TEXT NOT NULL,
                revision INTEGER NOT NULL,
                PRIMARY KEY (reader_id, revision)
            );
            CREATE TABLE IF NOT EXISTS applied_user (
                reader_id TEXT NOT NULL,
                device_user_id TEXT NOT NULL,
                PRIMARY KEY (reader_id, device_user_id)
            );
            CREATE TABLE IF NOT EXISTS observation_scan (
                reader_id TEXT PRIMARY KEY,
                epoch TEXT NOT NULL,
                seq INTEGER NOT NULL
            );
            """;
        command.ExecuteNonQuery();
        StartScanJournal();
        AddWrittenRecordColumns();
        MoveSingleRetry();
        RetryStoppedOnStart();
    }

    /// <summary>A journal from before per-revision attempts keeps its one retry as an attempt.</summary>
    private void MoveSingleRetry()
    {
        using (var exists = _connection.CreateCommand())
        {
            exists.CommandText = "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'retry_state'";
            if (Convert.ToInt64(exists.ExecuteScalar(), CultureInfo.InvariantCulture) == 0)
            {
                return;
            }
        }

        using var transaction = _connection.BeginTransaction();
        using (var copy = _connection.CreateCommand())
        {
            copy.Transaction = transaction;
            copy.CommandText = """
                INSERT INTO revision_attempt (reader_id, revision, device_user_id, attempt_count, next_attempt, last_error, state, reported)
                SELECT reader_id, revision, NULL, attempt_count, next_attempt, last_error, 'Retry', 0 FROM retry_state
                WHERE true
                ON CONFLICT (reader_id, revision) DO NOTHING
                """;
            copy.ExecuteNonQuery();
        }

        using (var drop = _connection.CreateCommand())
        {
            drop.Transaction = transaction;
            drop.CommandText = "DROP TABLE retry_state";
            drop.ExecuteNonQuery();
        }

        transaction.Commit();
    }

    /// <summary>
    /// A restart gives every exhausted or rejected revision one more attempt now. Another failure
    /// puts it straight back, without a new report.
    /// </summary>
    private void RetryStoppedOnStart()
    {
        using var command = _connection.CreateCommand();
        command.CommandText = """
            UPDATE revision_attempt SET next_attempt = $now
            WHERE reader_id = $reader AND state IN ('Exhausted', 'Rejected')
            """;
        command.Parameters.AddWithValue(ReaderParam, _readerId);
        command.Parameters.AddWithValue("$now", _clock().ToString("o", CultureInfo.InvariantCulture));
        command.ExecuteNonQuery();
    }

    private static readonly (string Column, string Statement)[] AppliedUserColumns =
    [
        ("name", "ALTER TABLE applied_user ADD COLUMN name TEXT"),
        ("name_ex", "ALTER TABLE applied_user ADD COLUMN name_ex TEXT"),
        ("user_status", "ALTER TABLE applied_user ADD COLUMN user_status INTEGER"),
        ("valid_from", "ALTER TABLE applied_user ADD COLUMN valid_from TEXT"),
        ("valid_to", "ALTER TABLE applied_user ADD COLUMN valid_to TEXT"),
        ("authority", "ALTER TABLE applied_user ADD COLUMN authority TEXT"),
        ("face_sha256", "ALTER TABLE applied_user ADD COLUMN face_sha256 TEXT"),
        ("sent_face_sha256", "ALTER TABLE applied_user ADD COLUMN sent_face_sha256 TEXT")
    ];

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

        foreach (var (column, statement) in AppliedUserColumns)
        {
            if (existing.Contains(column))
            {
                continue;
            }

            using var alter = _connection.CreateCommand();
            alter.CommandText = statement;
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

    private bool IsVerifiedUnlocked(long revision)
    {
        if (revision <= AppliedRevisionUnlocked())
        {
            return true;
        }

        using var command = _connection.CreateCommand();
        command.CommandText = "SELECT COUNT(*) FROM verified_revision WHERE reader_id = $reader AND revision = $revision";
        command.Parameters.AddWithValue(ReaderParam, _readerId);
        command.Parameters.AddWithValue(RevisionParam, revision);
        return Convert.ToInt64(command.ExecuteScalar(), CultureInfo.InvariantCulture) > 0;
    }

    private List<RetryState> AttemptsUnlocked(long? revision)
    {
        using var command = _connection.CreateCommand();
        command.CommandText = """
            SELECT revision, attempt_count, next_attempt, last_error, device_user_id, state, reported
            FROM revision_attempt WHERE reader_id = $reader AND ($revision IS NULL OR revision = $revision)
            ORDER BY revision
            """;
        command.Parameters.AddWithValue(ReaderParam, _readerId);
        command.Parameters.AddWithValue(RevisionParam, (object?)revision ?? DBNull.Value);
        using var row = command.ExecuteReader();
        var attempts = new List<RetryState>();
        while (row.Read())
        {
            attempts.Add(new RetryState(
                row.GetInt64(0),
                row.GetInt32(1),
                DateTimeOffset.Parse(row.GetString(2), CultureInfo.InvariantCulture),
                row.GetString(3),
                row.IsDBNull(4) ? null : row.GetString(4),
                Enum.Parse<AttemptState>(row.GetString(5)),
                row.GetInt64(6) != 0));
        }

        return attempts;
    }

    /// <summary>
    /// The wait doubles from the retry delay up to <see cref="MaxRetryDelay"/>. After
    /// <see cref="ExhaustAfter"/> failures the revision is exhausted and still tried at that longest
    /// wait. A permanent failure is rejected and not tried again on its own. A change of state needs
    /// a new report.
    /// </summary>
    /// <summary>
    /// The reader was offline, so the operation was not tried or not finished. The count, state and
    /// report stay as they were; the revision is due as soon as the reader is back. A rejected
    /// revision stays rejected.
    /// </summary>
    private void RecordOffline(long revision, string? deviceUserId, RetryState? previous, string error)
    {
        if (previous is { State: AttemptState.Rejected })
        {
            return;
        }

        using var command = _connection.CreateCommand();
        command.CommandText = """
            INSERT INTO revision_attempt (reader_id, revision, device_user_id, attempt_count, next_attempt, last_error, state, reported)
            VALUES ($reader, $revision, $user, $attempts, $next, $error, $state, $reported)
            ON CONFLICT (reader_id, revision) DO UPDATE SET
                device_user_id = excluded.device_user_id,
                next_attempt = excluded.next_attempt,
                last_error = excluded.last_error
            """;
        command.Parameters.AddWithValue(ReaderParam, _readerId);
        command.Parameters.AddWithValue(RevisionParam, revision);
        command.Parameters.AddWithValue(UserParam, (object?)(deviceUserId ?? previous?.DeviceUserId) ?? DBNull.Value);
        command.Parameters.AddWithValue("$attempts", previous?.AttemptCount ?? 0);
        command.Parameters.AddWithValue("$next", _clock().ToString("o", CultureInfo.InvariantCulture));
        command.Parameters.AddWithValue("$error", error);
        command.Parameters.AddWithValue("$state", (previous?.State ?? AttemptState.Retry).ToString());
        command.Parameters.AddWithValue("$reported", previous is { Reported: true } ? 1 : 0);
        command.ExecuteNonQuery();
    }

    private RetryState RecordFailure(long revision, string? deviceUserId, RetryState? previous, string error, bool permanent)
    {
        var attempts = (previous?.AttemptCount ?? 0) + 1;
        var state = AttemptState.Retry;
        if (permanent)
        {
            state = AttemptState.Rejected;
        }
        else if (attempts >= ExhaustAfter)
        {
            state = AttemptState.Exhausted;
        }

        var next = state switch
        {
            AttemptState.Rejected => DateTimeOffset.MaxValue,
            AttemptState.Exhausted => _clock().Add(MaxRetryDelay),
            _ => _clock().Add(Backoff(attempts))
        };
        var reported = previous != null && previous.Reported && previous.State == state;
        var recorded = new RetryState(revision, attempts, next, error, deviceUserId ?? previous?.DeviceUserId, state, reported);
        using var command = _connection.CreateCommand();
        command.CommandText = """
            INSERT INTO revision_attempt (reader_id, revision, device_user_id, attempt_count, next_attempt, last_error, state, reported)
            VALUES ($reader, $revision, $user, $attempts, $next, $error, $state, $reported)
            ON CONFLICT (reader_id, revision) DO UPDATE SET
                device_user_id = excluded.device_user_id,
                attempt_count = excluded.attempt_count,
                next_attempt = excluded.next_attempt,
                last_error = excluded.last_error,
                state = excluded.state,
                reported = excluded.reported
            """;
        command.Parameters.AddWithValue(ReaderParam, _readerId);
        command.Parameters.AddWithValue(RevisionParam, revision);
        command.Parameters.AddWithValue(UserParam, (object?)recorded.DeviceUserId ?? DBNull.Value);
        command.Parameters.AddWithValue("$attempts", attempts);
        command.Parameters.AddWithValue("$next", next.ToString("o", CultureInfo.InvariantCulture));
        command.Parameters.AddWithValue("$error", error);
        command.Parameters.AddWithValue("$state", state.ToString());
        command.Parameters.AddWithValue("$reported", reported ? 1 : 0);
        command.ExecuteNonQuery();
        return recorded;
    }

    private TimeSpan Backoff(int attempts)
    {
        var factor = Math.Pow(2, Math.Min(attempts - 1, 30));
        var ticks = Math.Min(_retryDelay.Ticks * factor, MaxRetryDelay.Ticks);
        return TimeSpan.FromTicks((long)ticks);
    }

    /// <summary>
    /// The cursor moves to the highest verified revision below the lowest unresolved one. Verified
    /// revisions at or below the cursor are no longer kept apart.
    /// </summary>
    private void AdvanceUnlocked(SqliteTransaction transaction)
    {
        long? Scalar(string sql)
        {
            using var command = _connection.CreateCommand();
            command.Transaction = transaction;
            command.CommandText = sql;
            command.Parameters.AddWithValue(ReaderParam, _readerId);
            var value = command.ExecuteScalar();
            return value is long number ? number : null;
        }

        var verified = Scalar("SELECT MAX(revision) FROM verified_revision WHERE reader_id = $reader");
        if (verified == null)
        {
            return;
        }

        var unresolved = Scalar("SELECT MIN(revision) FROM revision_attempt WHERE reader_id = $reader");
        var target = unresolved == null ? verified.Value : Math.Min(verified.Value, unresolved.Value - 1);
        foreach (var sql in new[]
                 {
                     """
                     INSERT INTO applied_revision (reader_id, revision) VALUES ($reader, $revision)
                     ON CONFLICT (reader_id) DO UPDATE SET revision = excluded.revision
                     WHERE excluded.revision > applied_revision.revision
                     """,
                     "DELETE FROM verified_revision WHERE reader_id = $reader AND revision <= (SELECT revision FROM applied_revision WHERE reader_id = $reader)"
                 })
        {
            using var command = _connection.CreateCommand();
            command.Transaction = transaction;
            command.CommandText = sql;
            command.Parameters.AddWithValue(ReaderParam, _readerId);
            command.Parameters.AddWithValue(RevisionParam, target);
            command.ExecuteNonQuery();
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
        string? sentFace = null;
        if (faceSha256 != null
            && desired.FacePresent
            && desired.Face is { Length: > 0 }
            && faceSha256.Length > 0)
        {
            sentFace = FaceHash.Sha256Hex(desired.Face);
        }

        using var command = _connection.CreateCommand();
        command.CommandText = """
            INSERT INTO applied_user (
                reader_id, device_user_id, name, name_ex, user_status, valid_from, valid_to, authority,
                face_sha256, sent_face_sha256)
            VALUES ($reader, $user, $name, $nameEx, $status, $from, $to, $authority, $face, $sent)
            ON CONFLICT (reader_id, device_user_id) DO UPDATE SET
                name = excluded.name,
                name_ex = excluded.name_ex,
                user_status = excluded.user_status,
                valid_from = excluded.valid_from,
                valid_to = excluded.valid_to,
                authority = excluded.authority,
                face_sha256 = excluded.face_sha256,
                sent_face_sha256 = excluded.sent_face_sha256
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
        command.Parameters.AddWithValue("$sent", (object?)sentFace ?? DBNull.Value);
        command.ExecuteNonQuery();
    }

    private bool SameSentFace(DesiredMember desired)
    {
        if (!desired.FacePresent || desired.Face is not { Length: > 0 })
        {
            return false;
        }

        using var command = _connection.CreateCommand();
        command.CommandText = """
            SELECT sent_face_sha256 FROM applied_user
            WHERE reader_id = $reader AND device_user_id = $user
            """;
        command.Parameters.AddWithValue(ReaderParam, _readerId);
        command.Parameters.AddWithValue(UserParam, desired.DeviceUserId);
        var value = command.ExecuteScalar();
        return value is string sent
            && sent.Length > 0
            && string.Equals(sent, FaceHash.Sha256Hex(desired.Face), StringComparison.OrdinalIgnoreCase);
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
        using (var verified = _connection.CreateCommand())
        {
            verified.Transaction = transaction;
            verified.CommandText = """
                INSERT INTO verified_revision (reader_id, revision) VALUES ($reader, $revision)
                ON CONFLICT (reader_id, revision) DO NOTHING
                """;
            verified.Parameters.AddWithValue(ReaderParam, _readerId);
            verified.Parameters.AddWithValue(RevisionParam, revision);
            verified.ExecuteNonQuery();
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
            retry.CommandText = "DELETE FROM revision_attempt WHERE reader_id = $reader AND revision = $revision";
            retry.Parameters.AddWithValue(ReaderParam, _readerId);
            retry.Parameters.AddWithValue(RevisionParam, revision);
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

        AdvanceUnlocked(transaction);
        transaction.Commit();
    }
}

public enum ApplyOutcome
{
    Verified,
    AlreadyVerified,
    Failed,
    WaitingToRetry,

    /// <summary>The reader's session is down; nothing was counted against the revision.</summary>
    ReaderOffline
}

public sealed record Verification(bool Verified, string? Error);

/// <summary>
/// <see cref="Retry"/> waits with backoff; <see cref="Exhausted"/> failed <see cref="ReaderWorker.ExhaustAfter"/>
/// times and waits the longest delay; <see cref="Rejected"/> cannot be applied as sent.
/// </summary>
public enum AttemptState
{
    Retry,
    Exhausted,
    Rejected
}

/// <summary>A revision that has not succeeded. <see cref="Reported"/>: the server has this state.</summary>
public sealed record RetryState(
    long Revision,
    int AttemptCount,
    DateTimeOffset NextAttempt,
    string LastError,
    string? DeviceUserId = null,
    AttemptState State = AttemptState.Retry,
    bool Reported = false);

public sealed record RevisionAck(string ReaderId, long Revision);

/// <summary>
/// One scan of a reader: the gateway journal that numbered it and its number there. Numbers from
/// different journals are not comparable.
/// </summary>
public sealed record ScanStamp(string Epoch, long Seq);
