using Gym.Gateway.Adapters;

namespace Gym.Gateway.Execution;

/// <summary>
/// Holds the reader's connection lock for each call and releases it when the call returns. The
/// member worker and the attendance poll share this lock, so a member write waits at most for one
/// attendance query, never for a whole poll (upload included).
/// </summary>
public sealed class LockedReaderAdapter : IReaderAdapter
{
    private readonly string _deviceId;
    private readonly IReaderAdapter _inner;
    private readonly DeviceLocks _locks;

    public LockedReaderAdapter(string deviceId, IReaderAdapter inner, DeviceLocks locks)
    {
        _deviceId = deviceId;
        _inner = inner ?? throw new ArgumentNullException(nameof(inner));
        _locks = locks ?? throw new ArgumentNullException(nameof(locks));
    }

    public ReaderListResult ListUsers() => Locked("reading the user list", _inner.ListUsers);

    public ReaderUserResult GetUser(string deviceUserId) =>
        Locked("member worker: reading user " + deviceUserId, () => _inner.GetUser(deviceUserId));

    public ReaderCallResult CreateUser(ReaderUser user) =>
        Locked("member worker: creating user " + user.DeviceUserId, () => _inner.CreateUser(user));

    public ReaderCallResult ReplaceUser(ReaderUser user) =>
        Locked("member worker: writing user " + user.DeviceUserId, () => _inner.ReplaceUser(user));

    public ReaderFaceResult GetFace(string deviceUserId) =>
        Locked("member worker: reading face " + deviceUserId, () => _inner.GetFace(deviceUserId));

    public FaceChecksumList ListFaceChecksums() => Locked("reading the photo checksums", _inner.ListFaceChecksums);

    public ReaderCallResult InsertFace(string deviceUserId, byte[]? jpeg) =>
        Locked("member worker: inserting face " + deviceUserId, () => _inner.InsertFace(deviceUserId, jpeg));

    public ReaderCallResult UpdateFace(string deviceUserId, byte[]? jpeg) =>
        Locked("member worker: updating face " + deviceUserId, () => _inner.UpdateFace(deviceUserId, jpeg));

    public ReaderCallResult RemoveFace(string deviceUserId) =>
        Locked("member worker: removing face " + deviceUserId, () => _inner.RemoveFace(deviceUserId));

    public ReaderCallResult RemoveUser(string deviceUserId) =>
        Locked("member worker: removing user " + deviceUserId, () => _inner.RemoveUser(deviceUserId));

    public ReaderPunchResult QueryAttendance(DateTimeOffset fromUtc, DateTimeOffset toUtc) =>
        Locked("reading attendance", () => _inner.QueryAttendance(fromUtc, toUtc));

    private T Locked<T>(string task, Func<T> call)
    {
        using (_locks.Acquire(_deviceId, task))
        {
            return call();
        }
    }
}
