using System.Globalization;
using System.Text.Json;
using Gym.Gateway.Adapters;
using Microsoft.Extensions.Logging;

namespace Gym.Gateway;

/// <summary>
/// Maps backend outbox commands onto <see cref="IDeviceAdapter"/>. Never marks face enrolment as success.
/// </summary>
public sealed class CommandDispatcher
{
    private readonly IReadOnlyDictionary<string, IDeviceAdapter> _adapters;
    private readonly ILogger<CommandDispatcher> _log;

    public CommandDispatcher(IReadOnlyDictionary<string, IDeviceAdapter> adapters, ILogger<CommandDispatcher> log)
    {
        _adapters = adapters;
        _log = log;
    }

    public async Task<DispatchOutcome> DispatchAsync(GatewayEnvelope command)
    {
        if (string.IsNullOrWhiteSpace(command.DeviceId) || !_adapters.TryGetValue(command.DeviceId, out var adapter))
        {
            return DispatchOutcome.SyncFail("Unknown or unconfigured deviceId");
        }

        try
        {
            return await Task.Run(() => Dispatch(adapter, command)).ConfigureAwait(false);
        }
        catch (Exception ex)
        {
            _log.LogError(ex, "Command {Type} failed", command.Type);
            return DispatchOutcome.SyncFail(ex.Message);
        }
    }

    private static DispatchOutcome Dispatch(IDeviceAdapter adapter, GatewayEnvelope command)
    {
        var payload = command.Payload;
        var userId = Text(payload, "deviceUserId");
        var mutation = new DeviceUserMutation(
            userId ?? "",
            Text(payload, "name"),
            Bool(payload, "enabled"),
            Instant(payload, "validFrom"),
            Instant(payload, "validTo"));

        switch (command.Type)
        {
            case "CREATE_USER":
                return RequireUser(userId, adapter.CreateUser(mutation));
            case "UPDATE_USER":
            case "UPDATE_ACCESS_POLICY":
                return RequireUser(userId, adapter.UpdateUser(mutation));
            case "DISABLE_USER":
                return RequireUser(userId, adapter.DisableUser(userId!));
            case "ENABLE_USER":
                return RequireUser(userId, adapter.EnableUser(userId!));
            case "REMOVE_USER":
                return RequireUser(userId, adapter.DeleteUser(userId!));
            case "UPDATE_VALIDITY":
                return RequireUser(userId, adapter.UpdateValidity(mutation));
            case "ENROLL_FACE":
                if (string.IsNullOrWhiteSpace(userId))
                {
                    return DispatchOutcome.SyncFail("deviceUserId required");
                }

                var enroll = adapter.StartFaceEnrollment(userId);
                return DispatchOutcome.Enrollment(enroll.Status, enroll.Error, userId);
            case "DELETE_FACE":
                return RequireUser(userId, adapter.DeleteFace(userId!));
            case "SYNC_DEVICE_TIME":
                return From(adapter.SynchronizeTime(DateTimeOffset.UtcNow));
            case "OPEN_DOOR":
                return From(adapter.OpenDoor());
            case "CLOSE_DOOR":
                return From(adapter.CloseDoor());
            case "REFRESH_DEVICE_USERS":
            case "RECONCILE_DEVICE":
                var recon = adapter.Reconcile();
                return recon.Ok
                    ? DispatchOutcome.Reconciliation(recon)
                    : DispatchOutcome.SyncFail(recon.Error ?? "reconcile failed");
            case "CLEAR_DEVICE_LOGS":
                return DispatchOutcome.SyncFail("CLEAR_DEVICE_LOGS has no verified SDK API in this adapter");
            default:
                return DispatchOutcome.SyncFail("unsupported command " + command.Type);
        }
    }

    private static DispatchOutcome RequireUser(string? userId, DeviceCommandResult result) =>
        string.IsNullOrWhiteSpace(userId) ? DispatchOutcome.SyncFail("deviceUserId required") : From(result);

    private static DispatchOutcome From(DeviceCommandResult result) =>
        result.Ok ? DispatchOutcome.SyncOk() : DispatchOutcome.SyncFail(result.Error ?? "command failed");

    private static string? Text(JsonElement payload, string name)
    {
        if (payload.ValueKind != JsonValueKind.Object || !payload.TryGetProperty(name, out var v))
        {
            return null;
        }

        return v.ValueKind == JsonValueKind.String ? v.GetString() : v.ToString();
    }

    private static bool? Bool(JsonElement payload, string name)
    {
        if (payload.ValueKind != JsonValueKind.Object || !payload.TryGetProperty(name, out var v))
        {
            return null;
        }

        return v.ValueKind switch
        {
            JsonValueKind.True => true,
            JsonValueKind.False => false,
            _ => null
        };
    }

    private static DateTimeOffset? Instant(JsonElement payload, string name)
    {
        var text = Text(payload, name);
        if (string.IsNullOrWhiteSpace(text))
        {
            return null;
        }

        if (DateTimeOffset.TryParse(text, CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal, out var dto))
        {
            return dto.ToUniversalTime();
        }

        if (DateTime.TryParse(text, CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal, out var date))
        {
            return new DateTimeOffset(DateTime.SpecifyKind(date, DateTimeKind.Utc));
        }

        return null;
    }
}

public sealed record DispatchOutcome(
    string ResultType,
    object Payload,
    IReadOnlyList<DeviceAttendanceRecord>? Events = null)
{
    public static DispatchOutcome SyncOk() => new(ProtocolTypes.SyncResult, new { ok = true });

    public static DispatchOutcome SyncFail(string error) =>
        new(ProtocolTypes.SyncResult, new { ok = false, error });

    public static DispatchOutcome Enrollment(string status, string? error, string deviceUserId) =>
        new(ProtocolTypes.EnrollmentResult, new { status, error, deviceUserId, ok = false });

    public static DispatchOutcome Reconciliation(DeviceReconciliationResult result) =>
        new(
            ProtocolTypes.ReconciliationResult,
            new
            {
                ok = true,
                deviceUserIds = result.DeviceUserIds,
                events = result.Events.Select(e => new
                {
                    deviceUserId = e.DeviceUserId,
                    occurredAt = e.OccurredAt.UtcDateTime.ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'"),
                    method = e.Method,
                    granted = e.Granted,
                    recNo = e.RecNo
                })
            },
            result.Events);
}
