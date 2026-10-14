namespace Gym.Gateway.Adapters;

/// <summary>
/// The reader operations the desired-revision path uses. Tests supply <see cref="FakeReader"/>. The service supplies the connected
/// device adapter behind the same calls.
/// </summary>
public interface IReaderAdapter
{
    /// <summary>
    /// Reads who is on the reader. A failed list means the reader did not answer, and nothing is written.
    /// </summary>
    ReaderListResult ListUsers();

    ReaderUserResult GetUser(string deviceUserId);

    ReaderCallResult CreateUser(ReaderUser user);

    /// <summary>
    /// Replaces the full user record for an id this member already owns. The face is left as it is.
    /// </summary>
    ReaderCallResult ReplaceUser(ReaderUser user);

    ReaderFaceResult GetFace(string deviceUserId);

    /// <summary>
    /// Every stored photo's MD5 without transferring photos. When it is not Ok, callers read each
    /// photo instead.
    /// </summary>
    FaceChecksumList ListFaceChecksums() => FaceChecksumList.Unavailable("photo checksum list is not supported");

    /// <summary>First photo for a user. A second insert over a stored photo is PHOTO_EXIST.</summary>
    ReaderCallResult InsertFace(string deviceUserId, byte[]? jpeg);

    /// <summary>
    /// Replaces a stored photo. An empty image is not a delete and leaves the previous bytes.
    /// </summary>
    ReaderCallResult UpdateFace(string deviceUserId, byte[]? jpeg);

    ReaderCallResult RemoveFace(string deviceUserId);

    /// <summary>Removes the device user. Removing an id that is already gone succeeds.</summary>
    ReaderCallResult RemoveUser(string deviceUserId);

    /// <summary>
    /// Punches in the reader log between the two instants, inclusive. The reader's punch time is
    /// taken as UTC. The log is only read: nothing is cleared and no record number is a cursor.
    /// </summary>
    ReaderPunchResult QueryAttendance(DateTimeOffset fromUtc, DateTimeOffset toUtc);
}
