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
    private readonly ReaderLabels _labels;
    private readonly TimeSpan _acceptanceTimeout;

    public ReaderObservationUpload(
        string gatewayId,
        BackendLink link,
        ILogger<ReaderObservationUpload> log,
        TimeSpan? acceptanceTimeout = null,
        ReaderLabels? labels = null)
    {
        _gatewayId = gatewayId;
        _link = link;
        _log = log;
        _labels = labels ?? new ReaderLabels();
        _acceptanceTimeout = acceptanceTimeout ?? DefaultAcceptanceTimeout;
    }

    public async Task<IReadOnlyList<string>> UploadAsync(
        string deviceId,
        ScanStamp scan,
        IReadOnlyList<ReaderUser> users,
        IReadOnlyDictionary<string, string> faceHashes,
        CancellationToken cancellationToken)
    {
        var reader = _labels.Of(deviceId);
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
                    "Reader {Reader}: person {User} could not be sent and will be tried again. Member sync stays paused. ({Message})",
                    reader, UserLabel(user), ex.Message);
            }

            if (sent.Count % 100 == 0 && sent.Count != users.Count)
            {
                _log.LogInformation(
                    "Reader {Reader}: sent {Done} of {Total} people to the server. Member sync stays paused until the server has saved this list.",
                    reader, sent.Count, users.Count);
            }
        }

        if (sent.Count > 0)
        {
            _log.LogInformation(
                "Reader {Reader}: sent all {Count} people to the server. Member sync stays paused until the server has saved them.",
                reader, sent.Count);
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
        var reader = _labels.Of(deviceId);
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
                "Reader {Reader}: roster with {Count} users is {Bytes} bytes; it was not uploaded",
                reader, people.Length, size);
            return RosterUpload.NotSent;
        }

        try
        {
            if (!awaitAcceptance)
            {
                await _link.SendAsync(envelope, cancellationToken).ConfigureAwait(false);
                _log.LogInformation(
                    "Reader {Reader}: sent the full list of {Count} people to the server without waiting for a reply. Member sync stays paused until the server has saved it.",
                    reader, people.Length);
                return new RosterUpload(false, envelope.MessageId);
            }

            var accepted = await _link.SendAndAwaitAckAsync(envelope, _acceptanceTimeout, cancellationToken)
                .ConfigureAwait(false);
            if (!accepted)
            {
                _log.LogInformation(
                    "Reader {Reader}: the server did not confirm the full list. People are sent one by one instead. Member sync stays paused until they are saved.",
                    reader);
            }
            else
            {
                _log.LogInformation(
                    "Reader {Reader}: the server saved the full list of {Count} people. Member sync can continue.",
                    reader, people.Length);
            }

            return new RosterUpload(accepted, envelope.MessageId);
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or WebSocketException)
        {
            _log.LogInformation(
                ex,
                "Reader {Reader}: roster queued; server unreachable ({Message})",
                reader, ex.Message);
            return new RosterUpload(false, envelope.MessageId);
        }
    }

    public async Task<IReadOnlyList<string>> UploadAbsencesAsync(
        string deviceId, ScanStamp scan, IReadOnlyList<string> deviceUserIds, CancellationToken cancellationToken)
    {
        var reader = _labels.Of(deviceId);
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
                    "Reader {Reader}: absence for user {UserId} queued; server unreachable ({Message})",
                    reader, deviceUserId, ex.Message);
            }
        }

        if (sent.Count > 0)
        {
            _log.LogInformation(
                "Reader {Reader}: queued {Count} absence(s) for the server: {Users}",
                reader, sent.Count, string.Join(", ", deviceUserIds.Where(id => !string.IsNullOrWhiteSpace(id)).Take(20)));
        }

        return sent;
    }

    private static string UserLabel(ReaderUser user)
    {
        var name = string.IsNullOrWhiteSpace(user.Name) ? null : user.Name.Trim();
        return name == null ? user.DeviceUserId : $"{name} ({user.DeviceUserId})";
    }

    private static string Summarize(IReadOnlyList<ReaderUser> users)
    {
        var parts = users.Select(UserLabel).Take(20).ToList();
        if (parts.Count == 0)
        {
            return "(none)";
        }

        return string.Join(", ", parts) + (users.Count > 20 ? ", …" : "");
    }
}
