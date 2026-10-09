using System.Security.Cryptography;
using Gym.Gateway.Adapters;

namespace Gym.Gateway.Execution;

/// <summary>
/// Pulls one reader's desired member, writes it through <see cref="ReaderWorker"/>, and posts the
/// acknowledgement only after the user and face read back. An occupied id is reported as that id.
/// Reconnect reads the reader before that pull. A silent reader is not written.
/// A trusted list uploads reader-created ids. It does not copy them to another reader.
/// </summary>
public sealed class DesiredRevisionPath
{
    private readonly string _deviceId;
    private readonly ReaderWorker _worker;
    private readonly IReaderAdapter _reader;
    private readonly IDesiredStateClient _client;
    private readonly IReaderObservationUpload? _observations;

    public DesiredRevisionPath(
        string deviceId,
        ReaderWorker worker,
        IReaderAdapter reader,
        IDesiredStateClient client,
        IReaderObservationUpload? observations = null)
    {
        if (string.IsNullOrWhiteSpace(deviceId))
        {
            throw new ArgumentException("A device id is required.", nameof(deviceId));
        }

        _deviceId = deviceId;
        _worker = worker ?? throw new ArgumentNullException(nameof(worker));
        _reader = reader ?? throw new ArgumentNullException(nameof(reader));
        _client = client ?? throw new ArgumentNullException(nameof(client));
        _observations = observations;
    }

    public Task HandleAsync(DesiredRevisionNotice notice, CancellationToken cancellationToken)
    {
        if (!string.Equals(notice.DeviceId, _deviceId, StringComparison.Ordinal))
        {
            return Task.CompletedTask;
        }

        return HandleAsync(cancellationToken);
    }

    /// <summary>
    /// Reconnect order after the gateway is authenticated: the reader answers a read, then pull,
    /// apply, verify, and ack. A list that fails applies nothing.
    /// </summary>
    public async Task ReconnectAsync(CancellationToken cancellationToken)
    {
        var observed = _reader.ListUsers();
        if (!observed.Ok)
        {
            return;
        }

        if (observed.CountMatchesAnnouncedTotal && observed.Users.Count == 0)
        {
            await UploadTrustedEmptyAsync(observed, cancellationToken).ConfigureAwait(false);
        }
        else
        {
            await UploadNewPeopleAsync(observed, cancellationToken).ConfigureAwait(false);
        }

        await HandleAsync(cancellationToken).ConfigureAwait(false);
    }

    /// <summary>
    /// One trusted read, then the existing observation upload. A short list uploads nothing.
    /// An empty trusted list is reported empty. Nothing is copied onto another reader.
    /// </summary>
    public async Task<bool> BootstrapAsync(CancellationToken cancellationToken)
    {
        var observed = _reader.ListUsers();
        if (!observed.Ok || !observed.CountMatchesAnnouncedTotal)
        {
            return false;
        }

        if (observed.Users.Count == 0)
        {
            await UploadTrustedEmptyAsync(observed, cancellationToken).ConfigureAwait(false);
            await HandleAsync(cancellationToken).ConfigureAwait(false);
            return true;
        }

        await UploadNewPeopleAsync(observed, cancellationToken).ConfigureAwait(false);
        return true;
    }

    /// <summary>
    /// A trusted list uploads reader-created ids. It does not pull or write desired state.
    /// </summary>
    public async Task ObserveAsync(CancellationToken cancellationToken)
    {
        var observed = _reader.ListUsers();
        if (!observed.Ok)
        {
            return;
        }

        await UploadNewPeopleAsync(observed, cancellationToken).ConfigureAwait(false);
    }

    /// <summary>A complete empty list is uploaded. It is not treated as a removal.</summary>
    private async Task UploadTrustedEmptyAsync(ReaderListResult observed, CancellationToken cancellationToken)
    {
        if (_observations == null)
        {
            return;
        }

        await _observations.UploadTrustedRosterAsync(
                _deviceId,
                observed.AnnouncedTotal,
                [],
                new Dictionary<string, string>(),
                cancellationToken)
            .ConfigureAwait(false);
    }

    private async Task UploadNewPeopleAsync(ReaderListResult observed, CancellationToken cancellationToken)
    {
        if (_observations == null || !observed.CountMatchesAnnouncedTotal)
        {
            return;
        }

        var listed = observed.Users
            .Where(user => !string.IsNullOrWhiteSpace(user.DeviceUserId))
            .ToList();
        if (listed.Count == 0)
        {
            return;
        }

        try
        {
            await _observations.UploadAsync(_deviceId, listed, FaceHashes(listed), cancellationToken)
                .ConfigureAwait(false);
            await UploadAbsencesAsync(listed, cancellationToken).ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch (Exception)
        {
            // The person stays on this reader. Nothing is copied to another reader.
        }
    }

    /// <summary>One hash per id. Equal hashes stay separate observations.</summary>
    private Dictionary<string, string> FaceHashes(IReadOnlyList<ReaderUser> listed)
    {
        var hashes = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (var user in listed)
        {
            var face = _reader.GetFace(user.DeviceUserId);
            if (face.Ok && face.Bytes is { Length: > 0 })
            {
                hashes[user.DeviceUserId] = FaceHash.Sha256Hex(face.Bytes);
            }
        }

        return hashes;
    }

    /// <summary>
    /// Mapped ids this gateway wrote that a trusted, non-empty list no longer contains.
    /// An empty list is not evidence that those people were removed.
    /// </summary>
    private async Task UploadAbsencesAsync(IReadOnlyList<ReaderUser> listed, CancellationToken cancellationToken)
    {
        var present = listed.Select(user => user.DeviceUserId).ToHashSet(StringComparer.Ordinal);
        var missing = _worker.AppliedUserIds().Where(id => !present.Contains(id)).ToList();
        if (missing.Count == 0)
        {
            return;
        }

        await _observations!.UploadAbsencesAsync(_deviceId, missing, cancellationToken).ConfigureAwait(false);
    }

    private enum ItemStep
    {
        Continue,
        PullAgain,
        Stop
    }

    private async Task HandleAsync(CancellationToken cancellationToken)
    {
        for (var pass = 0; pass < 5; pass++)
        {
            await DeliverPendingAcksAsync(cancellationToken).ConfigureAwait(false);
            var page = await _client.PullAsync(_deviceId, _worker.AppliedRevision, cancellationToken).ConfigureAwait(false);
            if (page.Items.Count == 0)
            {
                return;
            }

            var pullAgain = false;
            foreach (var item in page.Items)
            {
                var step = await ApplyItemAsync(item, cancellationToken).ConfigureAwait(false);
                if (step == ItemStep.Stop)
                {
                    return;
                }

                if (step == ItemStep.PullAgain)
                {
                    pullAgain = true;
                    break;
                }
            }

            if (!pullAgain && page.Items.All(item => item.Revision <= _worker.AppliedRevision))
            {
                return;
            }
        }

        throw new InvalidOperationException("Desired revision did not settle");
    }

    private async Task<ItemStep> ApplyItemAsync(DesiredPullItem item, CancellationToken cancellationToken)
    {
        if (item.Revision <= _worker.AppliedRevision)
        {
            return ItemStep.Continue;
        }

        var result = item.Present
            ? _worker.ApplyMember(item.ToMember())
            : _worker.ApplyRemoval(item.Revision, item.DeviceUserId);
        switch (result.Kind)
        {
            case MemberApplyKind.Occupied:
                var occupiedId = result.Detail;
                if (string.IsNullOrWhiteSpace(occupiedId))
                {
                    throw new InvalidOperationException("Occupied result has no device user id");
                }

                await _client.ReportOccupiedAsync(_deviceId, item.Revision, occupiedId, cancellationToken)
                    .ConfigureAwait(false);
                return ItemStep.PullAgain;
            case MemberApplyKind.Applied:
            case MemberApplyKind.AlreadyApplied:
                await DeliverPendingAcksAsync(cancellationToken).ConfigureAwait(false);
                return ItemStep.Continue;
            default:
                return ItemStep.Stop;
        }
    }

    private async Task DeliverPendingAcksAsync(CancellationToken cancellationToken)
    {
        foreach (var revision in _worker.PendingAcks.Select(pending => pending.Revision).ToArray())
        {
            var page = await _client.PullAsync(_deviceId, revision - 1, cancellationToken).ConfigureAwait(false);
            var item = page.Items.FirstOrDefault(candidate => candidate.Revision == revision);
            if (item == null)
            {
                if (page.DesiredRevision > revision)
                {
                    _worker.MarkAckDelivered(revision);
                    continue;
                }

                throw new InvalidOperationException($"Pending acknowledgement {revision} is not in the desired projection");
            }

            await AcknowledgeFromReaderAsync(item, cancellationToken).ConfigureAwait(false);
            _worker.MarkAckDelivered(revision);
        }
    }

    private async Task AcknowledgeFromReaderAsync(DesiredPullItem item, CancellationToken cancellationToken)
    {
        if (!item.Present)
        {
            await AcknowledgeAbsenceAsync(item, cancellationToken).ConfigureAwait(false);
            return;
        }

        var user = _reader.GetUser(item.DeviceUserId);
        var face = _reader.GetFace(item.DeviceUserId);
        if (!user.Ok || user.User == null || !face.Ok || face.Bytes is not { Length: > 0 })
        {
            throw new InvalidOperationException("Read-back is incomplete; acknowledgement was not sent");
        }

        var read = user.User;
        if (read.ValidFrom == null || read.ValidTo == null || string.IsNullOrWhiteSpace(read.Name))
        {
            throw new InvalidOperationException("Read-back user is incomplete; acknowledgement was not sent");
        }

        var hash = Convert.ToHexString(SHA256.HashData(face.Bytes)).ToLowerInvariant();
        await _client.AcknowledgeAsync(new DesiredAcknowledgement(
            _deviceId,
            item.Revision,
            read.DeviceUserId,
            read.Name,
            read.NameEx,
            read.UserStatus,
            ReaderLocalTime.Format(read.ValidFrom.Value),
            ReaderLocalTime.Format(read.ValidTo.Value),
            hash), cancellationToken).ConfigureAwait(false);
    }

    private async Task AcknowledgeAbsenceAsync(DesiredPullItem item, CancellationToken cancellationToken)
    {
        var user = _reader.GetUser(item.DeviceUserId);
        if (user.FailCode != FakeReader.FailNoRecord)
        {
            throw new InvalidOperationException("User is not NO_RECORD; acknowledgement was not sent");
        }

        await _client.AcknowledgeAsync(new DesiredAcknowledgement(
            _deviceId,
            item.Revision,
            item.DeviceUserId,
            "",
            null,
            0,
            "",
            "",
            "",
            false,
            FakeReader.FailNoRecord), cancellationToken).ConfigureAwait(false);
    }
}
