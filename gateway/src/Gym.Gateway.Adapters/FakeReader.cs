namespace Gym.Gateway.Adapters;

/// <summary>
/// Hardware-independent reader used by tests. It stores and returns what a caller wrote, and it can
/// be scripted to answer with the failures measured on the reader. It does not choose a member and
/// it does not allocate a device user id.
/// This is not <see cref="IDeviceAdapter"/>. That interface is the existing command path: partial
/// user merges, face upsert, and create-overwrites. Those are not this reader's behavior.
/// </summary>
public sealed class FakeReader : IReaderAdapter
{
    public const int SdkErrorMissingRecord = unchecked((int)0x800004B5);
    public const string FailPhotoExist = "PHOTO_EXIST";
    public const string FailNoRecord = "NO_RECORD";
    public const string FailUnknown = "UNKNOWN";
    public const string FailOccupied = "OCCUPIED";

    private readonly object _gate = new();
    private readonly Dictionary<string, ReaderUser> _users = new(StringComparer.Ordinal);
    private readonly Dictionary<string, byte[]> _faces = new(StringComparer.Ordinal);
    private readonly List<ReaderPunch> _punches = [];
    private readonly Dictionary<FakeReaderOperation, Queue<string>> _failures = [];
    private readonly Queue<ReaderListResult> _listScripts = [];
    private readonly List<string> _writes = [];
    private readonly List<string> _calls = [];
    private byte[]? _scriptedFaceReadBack;
    private bool _scriptUnknownUser;
    private bool _keepUserOnRemove;
    private int? _reportedStatus;
    private bool _reportValidity;
    private DateTimeOffset? _reportedFrom;
    private DateTimeOffset? _reportedTo;

    public IReadOnlyList<string> Writes
    {
        get
        {
            lock (_gate)
            {
                return _writes.ToArray();
            }
        }
    }

    /// <summary>Every reader call, in order, including reads. Writes are the subset that stored a change.</summary>
    public IReadOnlyList<string> Calls
    {
        get
        {
            lock (_gate)
            {
                return _calls.ToArray();
            }
        }
    }

    /// <summary>
    /// The next face read of a user who already has a photo returns these bytes. Stored bytes stay
    /// as inserted, so a hash of the read-back can differ.
    /// </summary>
    /// <summary>Get-user reports this status instead of the stored one. The stored record is unchanged.</summary>
    public void ScriptReportedStatus(int? status)
    {
        lock (_gate)
        {
            _reportedStatus = status;
        }
    }

    /// <summary>Get-user reports these dates instead of the stored ones. The stored record is unchanged.</summary>
    public void ScriptReportedValidity(DateTimeOffset from, DateTimeOffset to)
    {
        lock (_gate)
        {
            _reportValidity = true;
            _reportedFrom = from;
            _reportedTo = to;
        }
    }

    public void ClearReportedValidity()
    {
        lock (_gate)
        {
            _reportValidity = false;
        }
    }

    /// <summary>Get-user answers UNKNOWN with the shared SDK error, whether or not the id is stored.</summary>
    public void ScriptUnknownUser() => _scriptUnknownUser = true;

    /// <summary>Remove reports success and leaves the stored user in place.</summary>
    public void ScriptRemoveKeepsUser() => _keepUserOnRemove = true;

    public void ScriptFaceReadBack(byte[] jpeg)
    {
        lock (_gate)
        {
            _scriptedFaceReadBack = Copy(jpeg);
        }
    }

    public void ScriptFailure(FakeReaderOperation operation, string error = "failed")
    {
        lock (_gate)
        {
            if (!_failures.TryGetValue(operation, out var queued))
            {
                queued = new Queue<string>();
                _failures[operation] = queued;
            }

            queued.Enqueue(error);
        }
    }

    /// <summary>
    /// The next list returns this page and announced total and does not change who is stored.
    /// A short or empty page is a bad read when the count differs from <paramref name="announcedTotal"/>.
    /// </summary>
    public void ScriptList(int announcedTotal, params ReaderUser[] users)
    {
        lock (_gate)
        {
            _listScripts.Enqueue(ReaderListResult.Page(announcedTotal, users));
        }
    }

    public void ScriptListFailure(string error = "failed")
    {
        lock (_gate)
        {
            _listScripts.Enqueue(ReaderListResult.Failed(error));
        }
    }

    public ReaderListResult ListUsers()
    {
        lock (_gate)
        {
            Note("ListUsers");
            if (TakeFailure(FakeReaderOperation.ListUsers, out var error))
            {
                return ReaderListResult.Failed(error);
            }

            if (_listScripts.Count > 0)
            {
                return _listScripts.Dequeue();
            }

            var users = _users.Values.OrderBy(u => u.DeviceUserId, StringComparer.Ordinal).ToArray();
            return ReaderListResult.Page(users.Length, users);
        }
    }

    public ReaderUserResult GetUser(string deviceUserId)
    {
        lock (_gate)
        {
            Note("GetUser");
            if (TakeFailure(FakeReaderOperation.GetUser, out var error))
            {
                return ReaderUserResult.Failed(error);
            }

            if (_scriptUnknownUser)
            {
                return ReaderUserResult.Unknown();
            }

            if (!_users.TryGetValue(deviceUserId, out var user))
            {
                return ReaderUserResult.NoRecord();
            }

            return ReaderUserResult.Found(Reported(user));
        }
    }

    public ReaderCallResult CreateUser(ReaderUser user)
    {
        lock (_gate)
        {
            Note("CreateUser");
            if (TakeFailure(FakeReaderOperation.CreateUser, out var error))
            {
                return ReaderCallResult.Failed(error);
            }

            if (string.IsNullOrWhiteSpace(user.DeviceUserId))
            {
                return ReaderCallResult.Failed("device user id is required");
            }

            if (_users.ContainsKey(user.DeviceUserId))
            {
                return ReaderCallResult.Occupied(user.DeviceUserId);
            }

            _users[user.DeviceUserId] = Stored(user);
            _writes.Add("CreateUser " + user.DeviceUserId);
            return ReaderCallResult.Success();
        }
    }

    public ReaderCallResult ReplaceUser(ReaderUser user)
    {
        lock (_gate)
        {
            Note("ReplaceUser");
            if (!_users.ContainsKey(user.DeviceUserId))
            {
                return ReaderCallResult.NoRecord();
            }

            _users[user.DeviceUserId] = Stored(user);
            _writes.Add("ReplaceUser " + user.DeviceUserId);
            return ReaderCallResult.Success();
        }
    }

    public ReaderFaceResult GetFace(string deviceUserId)
    {
        lock (_gate)
        {
            Note("GetFace");
            if (TakeFailure(FakeReaderOperation.GetFace, out var error))
            {
                return ReaderFaceResult.Failed(error);
            }

            if (!_users.ContainsKey(deviceUserId))
            {
                return ReaderFaceResult.NoRecord();
            }

            return _faces.TryGetValue(deviceUserId, out var photo)
                ? ReaderFaceResult.Found(Copy(_scriptedFaceReadBack ?? photo))
                : ReaderFaceResult.NoPhoto();
        }
    }

    public ReaderCallResult UpdateFace(string deviceUserId, byte[]? jpeg)
    {
        lock (_gate)
        {
            Note("UpdateFace");
            if (TakeFailure(FakeReaderOperation.UpdateFace, out var error))
            {
                return ReaderCallResult.Failed(error);
            }

            if (!_users.ContainsKey(deviceUserId))
            {
                return ReaderCallResult.NoRecord();
            }

            if (jpeg is not { Length: > 0 })
            {
                return ReaderCallResult.Success();
            }

            _faces[deviceUserId] = Copy(jpeg);
            _writes.Add("UpdateFace " + deviceUserId);
            return ReaderCallResult.Success();
        }
    }

    public ReaderCallResult InsertFace(string deviceUserId, byte[]? jpeg)
    {
        lock (_gate)
        {
            Note("InsertFace");
            if (TakeFailure(FakeReaderOperation.InsertFace, out var error))
            {
                return ReaderCallResult.Failed(error);
            }

            if (!_users.ContainsKey(deviceUserId))
            {
                return ReaderCallResult.NoRecord();
            }

            if (jpeg is not { Length: > 0 })
            {
                return ReaderCallResult.Failed("face image is required");
            }

            if (_faces.ContainsKey(deviceUserId))
            {
                return ReaderCallResult.PhotoExist();
            }

            _faces[deviceUserId] = Copy(jpeg);
            _writes.Add("InsertFace " + deviceUserId);
            return ReaderCallResult.Success();
        }
    }

    public ReaderCallResult RemoveUser(string deviceUserId)
    {
        lock (_gate)
        {
            Note("RemoveUser");
            if (TakeFailure(FakeReaderOperation.RemoveUser, out var error))
            {
                return ReaderCallResult.Failed(error);
            }

            if (!_keepUserOnRemove)
            {
                _users.Remove(deviceUserId);
                _faces.Remove(deviceUserId);
            }

            _writes.Add("RemoveUser " + deviceUserId);
            return ReaderCallResult.Success();
        }
    }

    public ReaderCallResult RemoveFace(string deviceUserId)
    {
        lock (_gate)
        {
            Note("RemoveFace");
            if (TakeFailure(FakeReaderOperation.RemoveFace, out var error))
            {
                return ReaderCallResult.Failed(error);
            }

            _faces.Remove(deviceUserId);
            return ReaderCallResult.Success();
        }
    }

    public void AddPunch(ReaderPunch punch)
    {
        lock (_gate)
        {
            _punches.Add(punch);
        }
    }

    public ReaderPunchResult QueryPunches(DateTimeOffset fromUtc, DateTimeOffset toUtc)
    {
        lock (_gate)
        {
            if (TakeFailure(FakeReaderOperation.QueryPunches, out var error))
            {
                return ReaderPunchResult.Failed(error);
            }

            var matched = _punches
                .Where(p => p.OccurredAtUtc >= fromUtc && p.OccurredAtUtc <= toUtc)
                .OrderBy(p => p.OccurredAtUtc)
                .ThenBy(p => p.RecordNumber)
                .ToArray();
            return ReaderPunchResult.Found(matched);
        }
    }

    private ReaderUser Reported(ReaderUser user)
    {
        if (_reportedStatus is int status)
        {
            user = user with { UserStatus = status };
        }

        if (_reportValidity)
        {
            user = user with { ValidFrom = _reportedFrom, ValidTo = _reportedTo };
        }

        return user;
    }

    /// <summary>A user write that omits either date stores neither, matching a partial insert on the reader.</summary>
    private static ReaderUser Stored(ReaderUser user) =>
        user.ValidFrom is null || user.ValidTo is null
            ? user with { ValidFrom = null, ValidTo = null }
            : user;

    private bool TakeFailure(FakeReaderOperation operation, out string error)
    {
        if (_failures.TryGetValue(operation, out var queued) && queued.Count > 0)
        {
            error = queued.Dequeue();
            return true;
        }

        error = "";
        return false;
    }

    private void Note(string call) => _calls.Add(call);

    private static byte[] Copy(byte[] jpeg) => jpeg.ToArray();
}

public enum FakeReaderOperation
{
    ListUsers,
    GetUser,
    CreateUser,
    GetFace,
    UpdateFace,
    InsertFace,
    RemoveFace,
    RemoveUser,
    QueryPunches
}

public sealed record ReaderUser(
    string DeviceUserId,
    string? Name,
    string? NameEx,
    int UserStatus,
    DateTimeOffset? ValidFrom,
    DateTimeOffset? ValidTo,
    string? Authority,
    int DoorNum,
    int TimeSectionNum);

public sealed record ReaderPunch(
    string? DeviceUserId,
    DateTimeOffset OccurredAtUtc,
    long RecordNumber,
    string Method,
    bool Granted,
    int? ErrorCode);

public sealed record ReaderCallResult(bool Ok, string? FailCode, int? SdkError, string? Error)
{
    public static ReaderCallResult Success() => new(true, null, null, null);

    public static ReaderCallResult Failed(string error) => new(false, null, null, error);

    public static ReaderCallResult NoRecord() =>
        new(false, FakeReader.FailNoRecord, FakeReader.SdkErrorMissingRecord, FakeReader.FailNoRecord);

    public static ReaderCallResult PhotoExist() =>
        new(false, FakeReader.FailPhotoExist, FakeReader.SdkErrorMissingRecord, FakeReader.FailPhotoExist);

    public static ReaderCallResult Occupied(string deviceUserId) =>
        new(false, FakeReader.FailOccupied, null, deviceUserId);
}

public sealed record ReaderUserResult(bool Ok, ReaderUser? User, string? FailCode, int? SdkError, string? Error)
{
    public static ReaderUserResult Found(ReaderUser user) => new(true, user, null, null, null);

    public static ReaderUserResult NoRecord() =>
        new(false, null, FakeReader.FailNoRecord, FakeReader.SdkErrorMissingRecord, FakeReader.FailNoRecord);

    /// <summary>The reader answered, and the fail code is not a missing user. The SDK error is not that result.</summary>
    public static ReaderUserResult Unknown() =>
        new(false, null, FakeReader.FailUnknown, FakeReader.SdkErrorMissingRecord, FakeReader.FailUnknown);

    public static ReaderUserResult Failed(string error) => new(false, null, null, null, error);
}

public sealed record ReaderFaceResult(bool Ok, byte[]? Bytes, string? FailCode, int? SdkError, string? Error)
{
    public static ReaderFaceResult Found(byte[] bytes) => new(true, bytes, null, null, null);

    public static ReaderFaceResult NoPhoto() =>
        new(false, null, FakeReader.FailUnknown, FakeReader.SdkErrorMissingRecord, FakeReader.FailUnknown);

    public static ReaderFaceResult NoRecord() =>
        new(false, null, FakeReader.FailNoRecord, FakeReader.SdkErrorMissingRecord, FakeReader.FailNoRecord);

    public static ReaderFaceResult Failed(string error) => new(false, null, null, null, error);
}

public sealed record ReaderListResult(bool Ok, int AnnouncedTotal, IReadOnlyList<ReaderUser> Users, string? Error)
{
    public bool CountMatchesAnnouncedTotal => Ok && Users.Count == AnnouncedTotal;

    public static ReaderListResult Page(int announcedTotal, IReadOnlyList<ReaderUser> users) =>
        new(true, announcedTotal, users, null);

    public static ReaderListResult Failed(string error) => new(false, 0, [], error);
}

public sealed record ReaderPunchResult(bool Ok, IReadOnlyList<ReaderPunch> Punches, string? Error)
{
    public static ReaderPunchResult Found(IReadOnlyList<ReaderPunch> punches) => new(true, punches, null);

    public static ReaderPunchResult Failed(string error) => new(false, [], error);
}
