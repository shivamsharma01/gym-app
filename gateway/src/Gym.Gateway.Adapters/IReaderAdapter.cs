namespace Gym.Gateway.Adapters;

/// <summary>
/// V1 reader operations. Tests supply <see cref="FakeReader"/>. The service supplies the connected
/// device adapter behind the same calls.
/// </summary>
public interface IReaderAdapter
{
    ReaderUserResult GetUser(string deviceUserId);

    ReaderCallResult CreateUser(ReaderUser user);

    /// <summary>
    /// Replaces the full user record for an id this member already owns. The face is left as it is.
    /// </summary>
    ReaderCallResult ReplaceUser(ReaderUser user);

    ReaderFaceResult GetFace(string deviceUserId);

    /// <summary>First photo for a user. A second insert over a stored photo is PHOTO_EXIST.</summary>
    ReaderCallResult InsertFace(string deviceUserId, byte[]? jpeg);

    /// <summary>
    /// Replaces a stored photo. An empty image is not a delete and leaves the previous bytes.
    /// </summary>
    ReaderCallResult UpdateFace(string deviceUserId, byte[]? jpeg);

    ReaderCallResult RemoveFace(string deviceUserId);
}
