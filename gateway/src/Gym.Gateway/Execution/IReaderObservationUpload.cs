using Gym.Gateway.Adapters;

namespace Gym.Gateway.Execution;

/// <summary>
/// Sends reader-created people to the server. It does not write a reader and it does not allocate an id.
/// </summary>
public interface IReaderObservationUpload
{
    Task UploadAsync(
        string deviceId,
        IReadOnlyList<ReaderUser> users,
        IReadOnlyDictionary<string, string> faceHashes,
        CancellationToken cancellationToken);

    /// <summary>A mapped id missing from a trusted list. This does not remove anyone.</summary>
    Task UploadAbsencesAsync(string deviceId, IReadOnlyList<string> deviceUserIds, CancellationToken cancellationToken);

    /// <summary>A trusted roster, empty or not, with its announced total. This does not copy users onto another reader.</summary>
    /// <returns>
    /// True only when <paramref name="awaitAcceptance"/> is set and the server acknowledged that it
    /// processed this list. A list that was not sent, was rejected, was replaced, or was not
    /// answered in time returns false. Acceptance says nothing about what a reader has applied.
    /// </returns>
    Task<bool> UploadTrustedRosterAsync(
        string deviceId,
        int announcedTotal,
        IReadOnlyList<ReaderUser> users,
        IReadOnlyDictionary<string, string> faceHashes,
        bool awaitAcceptance,
        CancellationToken cancellationToken);
}
