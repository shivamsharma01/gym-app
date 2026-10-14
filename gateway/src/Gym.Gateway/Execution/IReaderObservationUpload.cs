using Gym.Gateway.Adapters;

namespace Gym.Gateway.Execution;

/// <summary>
/// Sends reader-created people to the server. It does not write a reader and it does not allocate an id.
/// Each upload returns the message ids it handed to the outbound link, sent or queued, so desired
/// state can wait until the server has processed them.
/// </summary>
public interface IReaderObservationUpload
{
    Task<IReadOnlyList<string>> UploadAsync(
        string deviceId,
        IReadOnlyList<ReaderUser> users,
        IReadOnlyDictionary<string, string> faceHashes,
        CancellationToken cancellationToken);

    /// <summary>A mapped id missing from a trusted list. This does not remove anyone.</summary>
    Task<IReadOnlyList<string>> UploadAbsencesAsync(
        string deviceId, IReadOnlyList<string> deviceUserIds, CancellationToken cancellationToken);

    /// <summary>A trusted roster, empty or not, with its announced total. This does not copy users onto another reader.</summary>
    /// <returns>
    /// <see cref="RosterUpload.Accepted"/> only when <paramref name="awaitAcceptance"/> is set and the
    /// server acknowledged that it processed this list. A list that was not sent, was rejected, was
    /// replaced, or was not answered in time is not accepted. Acceptance says nothing about what a
    /// reader has applied. <see cref="RosterUpload.MessageId"/> is null when the list was not sent.
    /// </returns>
    Task<RosterUpload> UploadTrustedRosterAsync(
        string deviceId,
        int announcedTotal,
        IReadOnlyList<ReaderUser> users,
        IReadOnlyDictionary<string, string> faceHashes,
        bool awaitAcceptance,
        CancellationToken cancellationToken);
}

public sealed record RosterUpload(bool Accepted, string? MessageId)
{
    public static readonly RosterUpload NotSent = new(false, null);
}
