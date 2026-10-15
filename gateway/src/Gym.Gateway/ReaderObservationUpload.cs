using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using Gym.Gateway.Adapters;
using Gym.Gateway.Execution;
using Microsoft.Extensions.Logging;

namespace Gym.Gateway;

/// <summary>
/// Uploads a trusted list of reader-created people. A failed send stays in the outbound journal.
/// This path does not write any reader. Every message carries the scan that read it, which the
/// caller numbered before it read the reader.
/// </summary>
public sealed class ReaderObservationUpload : IReaderObservationUpload
{
    /// <summary>
    /// The server closes a gateway socket on a text message over 1 MiB. A list larger than this is
    /// not queued, because the outbound journal would replay it on every reconnect.
    /// </summary>
    internal const int MaxRosterBytes = 1_000_000;

    /// <summary>A 1,200-user list took about 8 s on the test database.</summary>
    internal static readonly TimeSpan DefaultAcceptanceTimeout = TimeSpan.FromSeconds(60);

    private readonly string _gatewayId;
    private readonly BackendLink _link;
    private readonly ILogger<ReaderObservationUpload> _log;
    private readonly TimeSpan _acceptanceTimeout;

    public ReaderObservationUpload(
        string gatewayId,
        BackendLink link,
        ILogger<ReaderObservationUpload> log,
        TimeSpan? acceptanceTimeout = null)
    {
        _gatewayId = gatewayId;
        _link = link;
        _log = log;
        _acceptanceTimeout = acceptanceTimeout ?? DefaultAcceptanceTimeout;
    }

    public async Task<IReadOnlyList<string>> UploadAsync(
        string deviceId,
        ScanStamp scan,
        IReadOnlyList<ReaderUser> users,
        IReadOnlyDictionary<string, string> faceHashes,
        CancellationToken cancellationToken)
    {
        var sent = new List<string>();
        foreach (var user in users)
        {
            var envelope = GatewayEnvelope.Create(_gatewayId, ProtocolTypes.DeviceUserChanged, new
            {
                scanEpoch = scan.Epoch,
                scanSeq = scan.Seq,
                deviceUserId = user.DeviceUserId,
                name = user.Name,
                nameEx = user.NameEx,
                userStatus = user.UserStatus,
                validFrom = user.ValidFrom?.ToString("o"),
                validTo = user.ValidTo?.ToString("o"),
                authority = user.Authority,
                doorNum = user.DoorNum,
                timeSectionNum = user.TimeSectionNum,
                faceSha256 = faceHashes.TryGetValue(user.DeviceUserId, out var hash) ? hash : null,
                isNew = true,
                deleted = false
            }, deviceId);
            sent.Add(envelope.MessageId);
            try
            {
                await _link.SendAsync(envelope, cancellationToken).ConfigureAwait(false);
            }
            catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or WebSocketException)
            {
                _log.LogInformation(
                    ex,
                    "Observation for {DeviceId} user {UserId} queued; server unreachable ({Message})",
                    deviceId, user.DeviceUserId, ex.Message);
            }
        }

        return sent;
    }

    public Task<IReadOnlyList<string>> WantedFacesAsync(string deviceId, CancellationToken cancellationToken) =>
        _link.WantedFacesAsync(deviceId, cancellationToken);

    public async Task<bool> UploadFaceAsync(byte[] jpegBytes, CancellationToken cancellationToken) =>
        (await _link.UploadFaceAsync(jpegBytes, cancellationToken).ConfigureAwait(false)).UploadId != null;

    public async Task<RosterUpload> UploadTrustedRosterAsync(
        string deviceId,
        ScanStamp scan,
        int announcedTotal,
        IReadOnlyList<ReaderUser> users,
        IReadOnlyDictionary<string, string> faceHashes,
        bool awaitAcceptance,
        CancellationToken cancellationToken)
    {
        var people = users.Select(user => new
        {
            deviceUserId = user.DeviceUserId,
            name = user.Name,
            nameEx = user.NameEx,
            userStatus = user.UserStatus,
            validFrom = user.ValidFrom?.ToString("o"),
            validTo = user.ValidTo?.ToString("o"),
            authority = user.Authority,
            doorNum = user.DoorNum,
            timeSectionNum = user.TimeSectionNum,
            faceSha256 = faceHashes.TryGetValue(user.DeviceUserId, out var hash) ? hash : null,
            isNew = true,
            deleted = false
        }).ToArray();
        var envelope = GatewayEnvelope.Create(_gatewayId, ProtocolTypes.DeviceUserChanged, new
        {
            scanEpoch = scan.Epoch,
            scanSeq = scan.Seq,
            announcedTotal,
            users = people
        }, deviceId);
        var size = Encoding.UTF8.GetByteCount(JsonSerializer.Serialize(envelope, JsonOptions.Outbound));
        if (size > MaxRosterBytes)
        {
            _log.LogWarning(
                "Roster for {DeviceId} with {Count} users is {Bytes} bytes; it was not uploaded",
                deviceId, people.Length, size);
            return RosterUpload.NotSent;
        }

        try
        {
            if (!awaitAcceptance)
            {
                await _link.SendAsync(envelope, cancellationToken).ConfigureAwait(false);
                return new RosterUpload(false, envelope.MessageId);
            }

            var accepted = await _link.SendAndAwaitAckAsync(envelope, _acceptanceTimeout, cancellationToken)
                .ConfigureAwait(false);
            if (!accepted)
            {
                _log.LogInformation(
                    "Roster for {DeviceId} was not acknowledged; its users are sent one by one", deviceId);
            }

            return new RosterUpload(accepted, envelope.MessageId);
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or WebSocketException)
        {
            _log.LogInformation(
                ex,
                "Roster for {DeviceId} queued; server unreachable ({Message})",
                deviceId, ex.Message);
            return new RosterUpload(false, envelope.MessageId);
        }
    }

    public async Task<IReadOnlyList<string>> UploadAbsencesAsync(
        string deviceId, ScanStamp scan, IReadOnlyList<string> deviceUserIds, CancellationToken cancellationToken)
    {
        var sent = new List<string>();
        foreach (var deviceUserId in deviceUserIds)
        {
            if (string.IsNullOrWhiteSpace(deviceUserId))
            {
                continue;
            }

            var envelope = GatewayEnvelope.Create(_gatewayId, ProtocolTypes.DeviceUserChanged, new
            {
                scanEpoch = scan.Epoch,
                scanSeq = scan.Seq,
                deviceUserId,
                isNew = false,
                deleted = true
            }, deviceId);
            sent.Add(envelope.MessageId);
            try
            {
                await _link.SendAsync(envelope, cancellationToken).ConfigureAwait(false);
            }
            catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or WebSocketException)
            {
                _log.LogInformation(
                    ex,
                    "Absence for {DeviceId} user {UserId} queued; server unreachable ({Message})",
                    deviceId, deviceUserId, ex.Message);
            }
        }

        return sent;
    }
}
