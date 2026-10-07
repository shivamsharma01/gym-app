namespace Gym.Gateway.Adapters;

/// <summary>
/// V1 reader operations. Tests supply <see cref="FakeReader"/>. The service supplies the connected
/// device adapter behind the same calls.
/// </summary>
public interface IReaderAdapter
{
    ReaderUserResult GetUser(string deviceUserId);

    ReaderCallResult CreateUser(ReaderUser user);

    ReaderFaceResult GetFace(string deviceUserId);

    ReaderCallResult InsertFace(string deviceUserId, byte[]? jpeg);
}
