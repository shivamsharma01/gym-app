using Gym.Gateway.Adapters;

namespace Gym.Gateway.Execution;

/// <summary>
/// Sends reader-created people to the server. It does not write a reader and it does not allocate an id.
/// </summary>
public interface IReaderObservationUpload
{
    Task UploadAsync(string deviceId, IReadOnlyList<ReaderUser> users, CancellationToken cancellationToken);

    /// <summary>A mapped id missing from a trusted list. This does not remove anyone.</summary>
    Task UploadAbsencesAsync(string deviceId, IReadOnlyList<string> deviceUserIds, CancellationToken cancellationToken);
}
