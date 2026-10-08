using System.Net.WebSockets;
using Gym.Gateway.Adapters;
using Gym.Gateway.Execution;
using Microsoft.Extensions.Logging;

namespace Gym.Gateway;

/// <summary>
/// Uploads a trusted list of reader-created people. A failed send stays in the outbound journal.
/// This path does not write any reader.
/// </summary>
public sealed class ReaderObservationUpload : IReaderObservationUpload
{
    private readonly string _gatewayId;
    private readonly BackendLink _link;
    private readonly ILogger<ReaderObservationUpload> _log;

    public ReaderObservationUpload(string gatewayId, BackendLink link, ILogger<ReaderObservationUpload> log)
    {
        _gatewayId = gatewayId;
        _link = link;
        _log = log;
    }

    public async Task UploadAsync(string deviceId, IReadOnlyList<ReaderUser> users, CancellationToken cancellationToken)
    {
        foreach (var user in users)
        {
            var envelope = GatewayEnvelope.Create(_gatewayId, ProtocolTypes.DeviceUserChanged, new
            {
                deviceUserId = user.DeviceUserId,
                name = user.Name,
                nameEx = user.NameEx,
                userStatus = user.UserStatus,
                validFrom = user.ValidFrom?.ToString("o"),
                validTo = user.ValidTo?.ToString("o"),
                authority = user.Authority,
                isNew = true,
                deleted = false
            }, deviceId);
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
    }

    public async Task UploadAbsencesAsync(
        string deviceId, IReadOnlyList<string> deviceUserIds, CancellationToken cancellationToken)
    {
        foreach (var deviceUserId in deviceUserIds)
        {
            if (string.IsNullOrWhiteSpace(deviceUserId))
            {
                continue;
            }

            var envelope = GatewayEnvelope.Create(_gatewayId, ProtocolTypes.DeviceUserChanged, new
            {
                deviceUserId,
                isNew = false,
                deleted = true
            }, deviceId);
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
    }
}
