using System.Text.Json;

namespace Gym.Gateway;

public readonly record struct DesiredRevisionNotice(string DeviceId, long Revision);

/// <summary>
/// A desired-revision notice is not an outbox command. Routing it here keeps it out of
/// <see cref="CommandDispatcher"/>.
/// </summary>
public static class InboundDispatch
{
    public const string DesiredRevisionType = "DESIRED_REVISION";

    public static bool TryReadDesiredRevision(string json, out DesiredRevisionNotice notice)
    {
        notice = default;
        try
        {
            using var document = JsonDocument.Parse(json);
            var root = document.RootElement;
            if (!root.TryGetProperty("type", out var type) || type.ValueKind != JsonValueKind.String)
            {
                return false;
            }

            if (type.GetString() != DesiredRevisionType)
            {
                return false;
            }

            if (!root.TryGetProperty("deviceId", out var device) || device.ValueKind != JsonValueKind.String)
            {
                return false;
            }

            var deviceId = device.GetString();
            if (string.IsNullOrWhiteSpace(deviceId))
            {
                return false;
            }

            if (!root.TryGetProperty("revision", out var revision) || !revision.TryGetInt64(out var value) || value <= 0)
            {
                return false;
            }

            notice = new DesiredRevisionNotice(deviceId, value);
            return true;
        }
        catch (JsonException)
        {
            return false;
        }
    }

    /// <summary>
    /// Gives a desired-revision frame to <paramref name="onDesired"/> and does not call
    /// <paramref name="onCommand"/>. Returns false when the frame is some other message.
    /// </summary>
    public static async Task<bool> RouteAsync(
        string json,
        Func<GatewayEnvelope, Task> onCommand,
        Func<DesiredRevisionNotice, CancellationToken, Task> onDesired,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(onCommand);
        ArgumentNullException.ThrowIfNull(onDesired);
        if (!TryReadDesiredRevision(json, out var notice))
        {
            return false;
        }

        await onDesired(notice, cancellationToken).ConfigureAwait(false);
        return true;
    }
}
