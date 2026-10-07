using System.Security.Cryptography;
using Gym.Gateway.Adapters;

namespace Gym.Gateway.Execution;

/// <summary>
/// Pulls one reader's desired member, writes it through <see cref="ReaderWorker"/>, and posts the
/// acknowledgement only after the user and face read back. An occupied id is reported as that id.
/// </summary>
public sealed class DesiredRevisionPath
{
    private readonly string _deviceId;
    private readonly ReaderWorker _worker;
    private readonly IReaderAdapter _reader;
    private readonly IDesiredStateClient _client;

    public DesiredRevisionPath(string deviceId, ReaderWorker worker, IReaderAdapter reader, IDesiredStateClient client)
    {
        if (string.IsNullOrWhiteSpace(deviceId))
        {
            throw new ArgumentException("A device id is required.", nameof(deviceId));
        }

        _deviceId = deviceId;
        _worker = worker ?? throw new ArgumentNullException(nameof(worker));
        _reader = reader ?? throw new ArgumentNullException(nameof(reader));
        _client = client ?? throw new ArgumentNullException(nameof(client));
    }

    public Task HandleAsync(DesiredRevisionNotice notice, CancellationToken cancellationToken)
    {
        if (!string.Equals(notice.DeviceId, _deviceId, StringComparison.Ordinal))
        {
            return Task.CompletedTask;
        }

        return HandleAsync(cancellationToken);
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
                if (item.Revision <= _worker.AppliedRevision)
                {
                    continue;
                }

                var result = _worker.ApplyMember(item.ToMember());
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
                        pullAgain = true;
                        break;
                    case MemberApplyKind.Applied:
                    case MemberApplyKind.AlreadyApplied:
                        await DeliverPendingAcksAsync(cancellationToken).ConfigureAwait(false);
                        break;
                    default:
                        return;
                }

                if (pullAgain)
                {
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

    private async Task DeliverPendingAcksAsync(CancellationToken cancellationToken)
    {
        foreach (var pending in _worker.PendingAcks.ToArray())
        {
            var page = await _client.PullAsync(_deviceId, pending.Revision - 1, cancellationToken).ConfigureAwait(false);
            var item = page.Items.FirstOrDefault(candidate => candidate.Revision == pending.Revision);
            if (item == null)
            {
                throw new InvalidOperationException($"Pending acknowledgement {pending.Revision} is not in the desired projection");
            }

            await AcknowledgeFromReaderAsync(item, cancellationToken).ConfigureAwait(false);
            _worker.MarkAckDelivered(pending.Revision);
        }
    }

    private async Task AcknowledgeFromReaderAsync(DesiredPullItem item, CancellationToken cancellationToken)
    {
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
}
