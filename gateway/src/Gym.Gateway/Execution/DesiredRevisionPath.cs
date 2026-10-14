using System.Security.Cryptography;
using Gym.Gateway.Adapters;

namespace Gym.Gateway.Execution;

/// <summary>
/// Pulls one reader's desired member, writes it through <see cref="ReaderWorker"/>, and posts the
/// acknowledgement only after the user and face read back. An occupied id is reported as that id;
/// a record other than the expected one is reported as what the reader shows, and not written.
/// Reconnect reads the reader before that pull. A silent reader is not written.
/// A trusted list uploads reader-created ids and the whole list with its announced total. It does
/// not copy anyone to another reader.
/// </summary>
public sealed class DesiredRevisionPath
{
    private readonly string _deviceId;
    private readonly ReaderWorker _worker;
    private readonly IReaderAdapter _reader;
    private readonly IDesiredStateClient _client;
    private readonly IReaderObservationUpload? _observations;
    private readonly ReaderScanMemory _memory;

    public DesiredRevisionPath(
        string deviceId,
        ReaderWorker worker,
        IReaderAdapter reader,
        IDesiredStateClient client,
        IReaderObservationUpload? observations = null,
        ReaderScanMemory? memory = null)
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
        _memory = memory ?? ReaderScanMemory.InMemory();
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

        await UploadTrustedListAsync(observed, false, false, cancellationToken).ConfigureAwait(false);
        await HandleAsync(cancellationToken).ConfigureAwait(false);
    }

    /// <summary>
    /// One trusted read, then the whole list. The per-user observations follow only when the server
    /// did not acknowledge that list. A short list uploads nothing. Nothing is copied onto another reader.
    /// </summary>
    public Task<bool> BootstrapAsync(CancellationToken cancellationToken) => ScanAsync(false, cancellationToken);

    /// <summary>
    /// A scan staff asked for: the list is sent even when the server already acknowledged the same
    /// one, so the server runs its list handling again. False when the reader gave no trusted list.
    /// </summary>
    public Task<bool> ScanNowAsync(CancellationToken cancellationToken) => ScanAsync(true, cancellationToken);

    private async Task<bool> ScanAsync(bool force, CancellationToken cancellationToken)
    {
        var observed = _reader.ListUsers();
        if (!observed.Ok || !observed.CountMatchesAnnouncedTotal)
        {
            return false;
        }

        await UploadTrustedListAsync(observed, true, force, cancellationToken).ConfigureAwait(false);
        if (observed.Users.Count == 0)
        {
            await HandleAsync(cancellationToken).ConfigureAwait(false);
        }

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

    /// <summary>
    /// The absences and the whole list with its announced total, empty or not. The server runs the
    /// same per-user observation for every entry of a list it processes, so the per-user messages
    /// are sent only when that is not confirmed: always on reconnect, before the list, because the
    /// server's ACK cannot be read until the reconnect finishes; on a scan, after the list, when the
    /// list was not acknowledged, and then only users whose entry changed since they were last sent.
    /// A scan that finds exactly what the server last acknowledged sends nothing until the refresh
    /// period passes. An empty list is not treated as a removal. A short or mismatched list uploads
    /// nothing.
    /// </summary>
    private async Task UploadTrustedListAsync(
        ReaderListResult observed, bool awaitAcceptance, bool force, CancellationToken cancellationToken)
    {
        if (_observations == null || !observed.CountMatchesAnnouncedTotal)
        {
            return;
        }

        var uploads = _memory.Uploads;
        var generation = uploads.Generation;
        var listed = Listed(observed);
        var hashes = FaceHashes(listed);
        var missing = listed.Count > 0 ? Missing(listed) : [];
        var entries = listed.ToDictionary(
            user => user.DeviceUserId, user => ScanUploadState.Entry(user, hashes), StringComparer.Ordinal);
        var list = ScanUploadState.List(observed.AnnouncedTotal, entries, missing);
        if (awaitAcceptance && !force && uploads.AlreadyAccepted(list))
        {
            return;
        }

        try
        {
            if (!awaitAcceptance)
            {
                uploads.Reconnecting();
            }

            if (!awaitAcceptance && listed.Count > 0)
            {
                await _observations.UploadAsync(_deviceId, listed, hashes, cancellationToken).ConfigureAwait(false);
                uploads.Sent(entries.Keys.ToHashSet(StringComparer.Ordinal), entries);
            }

            if (missing.Count > 0)
            {
                await _observations.UploadAbsencesAsync(_deviceId, missing, cancellationToken).ConfigureAwait(false);
            }

            var accepted = await UploadListAsync(observed.AnnouncedTotal, listed, hashes, awaitAcceptance, cancellationToken)
                .ConfigureAwait(false);
            if (!awaitAcceptance)
            {
                return;
            }

            if (accepted)
            {
                uploads.Accepted(generation, list, entries);
                return;
            }

            var unsent = uploads.Unsent(entries);
            if (unsent.Count > 0)
            {
                await _observations.UploadAsync(
                        _deviceId, listed.Where(user => unsent.Contains(user.DeviceUserId)).ToList(), hashes, cancellationToken)
                    .ConfigureAwait(false);
                uploads.Sent(unsent, entries);
            }
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch (Exception)
        {
            // The people stay on this reader. Nothing is copied to another reader.
        }
    }

    /// <summary>Any failure of the list counts as not accepted, so the per-user messages still go.</summary>
    private async Task<bool> UploadListAsync(
        int announcedTotal,
        IReadOnlyList<ReaderUser> listed,
        IReadOnlyDictionary<string, string> hashes,
        bool awaitAcceptance,
        CancellationToken cancellationToken)
    {
        try
        {
            return await _observations!.UploadTrustedRosterAsync(
                    _deviceId, announcedTotal, listed, hashes, awaitAcceptance, cancellationToken)
                .ConfigureAwait(false);
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            return false;
        }
    }

    private async Task UploadNewPeopleAsync(ReaderListResult observed, CancellationToken cancellationToken)
    {
        if (_observations == null || !observed.CountMatchesAnnouncedTotal)
        {
            return;
        }

        var listed = Listed(observed);
        if (listed.Count == 0)
        {
            return;
        }

        try
        {
            await _observations.UploadAsync(_deviceId, listed, FaceHashes(listed), cancellationToken)
                .ConfigureAwait(false);
            var missing = Missing(listed);
            if (missing.Count > 0)
            {
                await _observations.UploadAbsencesAsync(_deviceId, missing, cancellationToken).ConfigureAwait(false);
            }
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

    private static List<ReaderUser> Listed(ReaderListResult observed) =>
        observed.Users.Where(user => !string.IsNullOrWhiteSpace(user.DeviceUserId)).ToList();

    /// <summary>
    /// One hash per id. Equal hashes stay separate observations. With the reader's photo checksum
    /// list, a user it does not list has no photo, and only a photo whose checksum is not cached is
    /// downloaded. Without that list every photo is downloaded.
    /// </summary>
    private Dictionary<string, string> FaceHashes(IReadOnlyList<ReaderUser> listed)
    {
        var checksums = _reader.ListFaceChecksums();
        if (!checksums.Ok)
        {
            return DownloadedFaceHashes(listed);
        }

        var hashes = new Dictionary<string, string>(StringComparer.Ordinal);
        var known = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (var deviceUserId in listed.Select(user => user.DeviceUserId))
        {
            if (!checksums.ByUserId.TryGetValue(deviceUserId, out var checksum))
            {
                continue;
            }

            if (checksum.Length == 0 || !_memory.Faces.TryGet(checksum, out var hash))
            {
                var face = _reader.GetFace(deviceUserId);
                if (!face.Ok || face.Bytes is not { Length: > 0 })
                {
                    continue;
                }

                hash = FaceHash.Sha256Hex(face.Bytes);
            }

            hashes[deviceUserId] = hash;
            if (checksum.Length > 0)
            {
                known[checksum] = hash;
            }
        }

        _memory.Faces.Replace(known);
        return hashes;
    }

    private Dictionary<string, string> DownloadedFaceHashes(IReadOnlyList<ReaderUser> listed)
    {
        var hashes = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (var deviceUserId in listed.Select(user => user.DeviceUserId))
        {
            var face = _reader.GetFace(deviceUserId);
            if (face.Ok && face.Bytes is { Length: > 0 })
            {
                hashes[deviceUserId] = FaceHash.Sha256Hex(face.Bytes);
            }
        }

        return hashes;
    }

    /// <summary>
    /// Mapped ids this gateway wrote that a trusted, non-empty list no longer contains.
    /// An empty list is not evidence that those people were removed.
    /// </summary>
    private List<string> Missing(IReadOnlyList<ReaderUser> listed)
    {
        var present = listed.Select(user => user.DeviceUserId).ToHashSet(StringComparer.Ordinal);
        return _worker.AppliedUserIds().Where(id => !present.Contains(id)).ToList();
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
            : _worker.ApplyRemoval(item.Revision, item.DeviceUserId, item.Expected);
        switch (result.Kind)
        {
            case MemberApplyKind.OccupantChanged:
                await _client.ReportOccupantAsync(
                        _deviceId, item.Revision, item.DeviceUserId, result.Occupant ?? ReaderOccupant.Nobody, cancellationToken)
                    .ConfigureAwait(false);
                return ItemStep.PullAgain;
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
                // The server replaced the row, or dropped it after finding someone else at the id.
                if (page.DesiredRevision >= revision)
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

        if (!item.FacePresent)
        {
            await AcknowledgeFaceClearedAsync(item, cancellationToken).ConfigureAwait(false);
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

    private async Task AcknowledgeFaceClearedAsync(DesiredPullItem item, CancellationToken cancellationToken)
    {
        var user = _reader.GetUser(item.DeviceUserId);
        var face = _reader.GetFace(item.DeviceUserId);
        if (!user.Ok || user.User == null)
        {
            throw new InvalidOperationException("User read-back is missing; acknowledgement was not sent");
        }

        if (face.Ok && face.Bytes is { Length: > 0 })
        {
            throw new InvalidOperationException("Face is still present; acknowledgement was not sent");
        }

        if (face.FailCode == FakeReader.FailNoRecord)
        {
            throw new InvalidOperationException("User is NO_RECORD; acknowledgement was not sent");
        }

        if (face.FailCode != FakeReader.FailUnknown)
        {
            throw new InvalidOperationException("Face read-back is not the missing-photo result; acknowledgement was not sent");
        }

        var read = user.User;
        if (read.ValidFrom == null || read.ValidTo == null || string.IsNullOrWhiteSpace(read.Name))
        {
            throw new InvalidOperationException("Read-back user is incomplete; acknowledgement was not sent");
        }

        await _client.AcknowledgeAsync(new DesiredAcknowledgement(
            _deviceId,
            item.Revision,
            read.DeviceUserId,
            read.Name,
            read.NameEx,
            read.UserStatus,
            ReaderLocalTime.Format(read.ValidFrom.Value),
            ReaderLocalTime.Format(read.ValidTo.Value),
            "",
            true,
            FakeReader.FailUnknown), cancellationToken).ConfigureAwait(false);
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
