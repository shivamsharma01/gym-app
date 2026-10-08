using Gym.Gateway.Adapters;

namespace Gym.Gateway.Execution;

/// <summary>
/// Sends reader-created people to the server. It does not write a reader and it does not allocate an id.
/// </summary>
public interface IReaderObservationUpload
{
    Task UploadAsync(string deviceId, IReadOnlyList<ReaderUser> users, CancellationToken cancellationToken);
}
