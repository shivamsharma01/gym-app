using System.Globalization;
using System.Text.Json;
using Gym.Gateway.Adapters;
using Microsoft.Extensions.Logging;

namespace Gym.Gateway;

/// <summary>
/// Maps backend outbox commands onto <see cref="IDeviceAdapter"/>. After each successful write it
/// records what the device now holds in <see cref="RosterStateStore"/>, so the change watcher never
/// reports the gateway's own writes back to the server as device edits.
/// </summary>
public sealed class CommandDispatcher
{
    private static readonly HashSet<string> UserCommands = new(StringComparer.Ordinal)
    {
        "CREATE_USER", "UPDATE_USER", "UPDATE_ACCESS_POLICY", "DISABLE_USER", "ENABLE_USER", "UPDATE_VALIDITY"
    };

    private readonly IReadOnlyDictionary<string, IDeviceAdapter> _adapters;
    private readonly ILogger<CommandDispatcher> _log;
    private readonly IFaceTransfer? _faces;
    private readonly RosterStateStore _roster;
    private readonly DeviceLocks _locks;
    private readonly Func<string, string, Task<(bool Ok, string? Error)>>? _reportUser;
    private readonly ILocalMemberSync? _memberSync;

    public CommandDispatcher(
        IReadOnlyDictionary<string, IDeviceAdapter> adapters,
        ILogger<CommandDispatcher> log,
        IFaceTransfer? faces = null,
        RosterStateStore? roster = null,
        DeviceLocks? locks = null,
        Func<string, string, Task<(bool Ok, string? Error)>>? reportUser = null,
        ILocalMemberSync? memberSync = null)
    {
        _adapters = adapters;
        _log = log;
        _faces = faces;
        _roster = roster ?? new RosterStateStore(null);
        _locks = locks ?? new DeviceLocks();
        _reportUser = reportUser;
        _memberSync = memberSync;
    }

    public async Task<DispatchOutcome> DispatchAsync(GatewayEnvelope command)
    {
        if (string.IsNullOrWhiteSpace(command.DeviceId) || !_adapters.TryGetValue(command.DeviceId, out var adapter))
        {
            return DispatchOutcome.SyncFail("Unknown or unconfigured deviceId");
        }

        try
        {
            if (command.Type == "REPORT_DEVICE_USER")
            {
                var reportId = Text(command.Payload, "deviceUserId");
                if (string.IsNullOrWhiteSpace(reportId) || _reportUser == null)
                {
                    return DispatchOutcome.SyncFail("REPORT_DEVICE_USER requires deviceUserId and a change watcher");
                }

                var (ok, error) = await _reportUser(command.DeviceId, reportId).ConfigureAwait(false);
                return ok ? DispatchOutcome.SyncOk() : DispatchOutcome.SyncFail(error ?? "report failed");
            }

            byte[]? face = null;
            if (command.Type == "UPSERT_FACE")
            {
                var prepared = await DownloadFaceAsync(command.Payload).ConfigureAwait(false);
                if (prepared.Error != null)
                {
                    return DispatchOutcome.SyncFail(prepared.Error);
                }

                face = prepared.Bytes;
            }

            if (_memberSync != null)
            {
                // Timestamped member commands go through local state (latest change wins, all devices updated).
                var synced = await _memberSync.TryApplyAsync(command, face, CancellationToken.None).ConfigureAwait(false);
                if (synced != null)
                {
                    _log.LogDebug("{Type} corr={Corr} handled through local member state (latest change wins)",
                        command.Type, command.CorrelationId);
                    return synced;
                }
            }

            _log.LogDebug("{Type} corr={Corr} goes straight to the reader adapter", command.Type, command.CorrelationId);

            var gate = _locks.For(command.DeviceId);
            await gate.WaitAsync().ConfigureAwait(false);
            try
            {
                return await Task.Run(() =>
                {
                    var outcome = Dispatch(adapter, command, face);
                    if (outcome.Ok)
                    {
                        RecordEcho(adapter, command, face);
                    }

                    return outcome;
                }).ConfigureAwait(false);
            }
            finally
            {
                gate.Release();
            }
        }
        catch (Exception ex)
        {
            _log.LogError(ex, "Command {Type} failed", command.Type);
            return DispatchOutcome.SyncFail(ex.Message);
        }
    }

    private async Task<(byte[]? Bytes, string? Error)> DownloadFaceAsync(JsonElement payload)
    {
        var memberId = Text(payload, "memberId");
        var version = Int(payload, "faceVersion");
        var expectedSha = Text(payload, "sha256");
        if (string.IsNullOrWhiteSpace(Text(payload, "deviceUserId")) || string.IsNullOrWhiteSpace(memberId)
            || version is null)
        {
            return (null, "UPSERT_FACE requires deviceUserId, memberId and faceVersion");
        }

        if (_faces == null)
        {
            return (null, "Face download is not configured on this gateway");
        }

        var download = await _faces.DownloadFaceAsync(memberId, version.Value, CancellationToken.None)
            .ConfigureAwait(false);
        if (!download.Ok || download.Bytes == null)
        {
            return (null, "Face download failed: " + (download.Error ?? "no data"));
        }

        if (!string.IsNullOrWhiteSpace(expectedSha)
            && !string.Equals(FaceHash.Sha256Hex(download.Bytes), expectedSha, StringComparison.OrdinalIgnoreCase))
        {
            return (null, "Face image sha256 mismatch (stale or corrupted download)");
        }

        _log.LogDebug("Downloaded face member={Member} version={Version} ({Bytes} bytes)",
            memberId, version, download.Bytes.Length);
        return (download.Bytes, null);
    }

    private DispatchOutcome Dispatch(IDeviceAdapter adapter, GatewayEnvelope command, byte[]? face)
    {
        var payload = command.Payload;
        var userId = Text(payload, "deviceUserId");
        var mutation = new DeviceUserMutation(
            userId ?? "",
            Text(payload, "name"),
            Bool(payload, "enabled"),
            Instant(payload, "validFrom"),
            Instant(payload, "validTo"),
            Text(payload, "authority"));

        switch (command.Type)
        {
            case "CREATE_USER":
                return RequireUser(userId, adapter.CreateUser(mutation));
            case "UPDATE_USER":
            case "UPDATE_ACCESS_POLICY":
                return RequireUser(userId, adapter.UpdateUser(mutation));
            case "DISABLE_USER" when mutation.ValidFrom.HasValue || mutation.ValidTo.HasValue:
                return RequireUser(userId, adapter.UpdateUser(mutation with { Enabled = false }));
            case "DISABLE_USER":
                return RequireUser(userId, adapter.DisableUser(userId!));
            case "ENABLE_USER" when mutation.ValidFrom.HasValue || mutation.ValidTo.HasValue:
                return RequireUser(userId, adapter.UpdateUser(mutation with { Enabled = true }));
            case "ENABLE_USER":
                return RequireUser(userId, adapter.EnableUser(userId!));
            case "REMOVE_USER":
                return RequireUser(userId, adapter.DeleteUser(userId!));
            case "UPDATE_VALIDITY":
                return RequireUser(userId, adapter.UpdateValidity(mutation));
            case "UPSERT_FACE":
            {
                var result = adapter.UpsertFace(userId!, face!);
                return result.Ok
                    ? DispatchOutcome.SyncOk(new { ok = true, faceVersion = Int(payload, "faceVersion") })
                    : DispatchOutcome.SyncFail(result.Error ?? "face write failed");
            }
            case "DELETE_FACE":
                return RequireUser(userId, adapter.DeleteFace(userId!));
            case "ENROLL_FACE":
                return DispatchOutcome.SyncFail("ENROLL_FACE is retired; the server sends UPSERT_FACE with the stored photo");
            case "SYNC_DEVICE_TIME":
                return From(adapter.SynchronizeTime(DateTimeOffset.UtcNow));
            case "OPEN_DOOR":
                return From(adapter.OpenDoor());
            case "CLOSE_DOOR":
                return From(adapter.CloseDoor());
            case "REFRESH_DEVICE_USERS":
            case "RECONCILE_DEVICE":
                var fromUtc = Instant(payload, "fromUtc");
                var toUtc = Instant(payload, "toUtc");
                var recon = adapter.Reconcile(fromUtc, toUtc);
                if (recon.Ok)
                {
                    _log.LogInformation(
                        "Reconcile read device={DeviceId}: {Users} user(s), {Events} attendance record(s) for {From}..{To}",
                        command.DeviceId, recon.Users.Count, recon.Events.Count, fromUtc, toUtc);
                    if (Bool(payload, "refreshFaces") == true)
                    {
                        _roster.RequestFaceRefresh(command.DeviceId!);
                        _log.LogInformation(
                            "Sync Now on {DeviceId}: every photo on this reader is read again in the background; only changed photos are sent",
                            command.DeviceId);
                    }

                    return DispatchOutcome.Reconciliation(recon);
                }

                _log.LogWarning("Reconcile read failed device={DeviceId}: {Error}", command.DeviceId, recon.Error);
                return DispatchOutcome.SyncFail(recon.Error ?? "reconcile failed");
            case "CLEAR_DEVICE_LOGS":
                return DispatchOutcome.SyncFail("CLEAR_DEVICE_LOGS has no verified SDK API in this adapter");
            default:
                return DispatchOutcome.SyncFail("unsupported command " + command.Type);
        }
    }

    /// <summary>Remember what the device holds after our write (echo suppression).</summary>
    private void RecordEcho(IDeviceAdapter adapter, GatewayEnvelope command, byte[]? face)
    {
        var deviceId = command.DeviceId!;
        var userId = Text(command.Payload, "deviceUserId");
        if (string.IsNullOrWhiteSpace(userId))
        {
            return;
        }

        try
        {
            if (UserCommands.Contains(command.Type))
            {
                var snapshot = adapter.GetUser(userId);
                if (snapshot != null)
                {
                    _roster.RecordProfile(deviceId, snapshot);
                }
            }
            else if (command.Type == "REMOVE_USER")
            {
                _roster.Remove(deviceId, userId);
            }
            else if (command.Type == "UPSERT_FACE" && face != null)
            {
                // The device may re-encode the image; remember the bytes it returns, not ours.
                var read = adapter.GetFace(userId);
                var sha = read is { Ok: true, Photo: not null } ? FaceHash.Sha256Hex(read.Photo) : FaceHash.Sha256Hex(face);
                _roster.RecordFace(deviceId, userId, sha);
            }
            else if (command.Type == "DELETE_FACE")
            {
                _roster.RecordFace(deviceId, userId, null);
            }
        }
        catch (Exception ex)
        {
            _log.LogWarning(ex, "Could not record post-write state for {Type} user={User}", command.Type, userId);
        }
    }

    private static DispatchOutcome RequireUser(string? userId, DeviceCommandResult result) =>
        string.IsNullOrWhiteSpace(userId) ? DispatchOutcome.SyncFail("deviceUserId required") : From(result);

    private static DispatchOutcome From(DeviceCommandResult result) =>
        result.Ok ? DispatchOutcome.SyncOk() : DispatchOutcome.SyncFail(result.Error ?? "command failed");

    internal static string? Text(JsonElement payload, string name)
    {
        if (payload.ValueKind != JsonValueKind.Object || !payload.TryGetProperty(name, out var v))
        {
            return null;
        }

        return v.ValueKind switch
        {
            JsonValueKind.String => v.GetString(),
            JsonValueKind.Null => null,
            _ => v.ToString()
        };
    }

    internal static int? Int(JsonElement payload, string name)
    {
        if (payload.ValueKind != JsonValueKind.Object || !payload.TryGetProperty(name, out var v))
        {
            return null;
        }

        if (v.ValueKind == JsonValueKind.Number && v.TryGetInt32(out var n))
        {
            return n;
        }

        return v.ValueKind == JsonValueKind.String && int.TryParse(v.GetString(), out var parsed) ? parsed : null;
    }

    internal static bool? Bool(JsonElement payload, string name)
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

    internal static DateTimeOffset? Instant(JsonElement payload, string name)
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
    IReadOnlyList<DeviceAttendanceRecord>? Events = null,
    bool Ok = false)
{
    public static DispatchOutcome SyncOk() => new(ProtocolTypes.SyncResult, new { ok = true }, null, true);

    public static DispatchOutcome SyncOk(object payload) => new(ProtocolTypes.SyncResult, payload, null, true);

    public static DispatchOutcome SyncFail(string error) =>
        new(ProtocolTypes.SyncResult, new { ok = false, error });

    public static DispatchOutcome Reconciliation(DeviceReconciliationResult result) =>
        new(
            ProtocolTypes.ReconciliationResult,
            new
            {
                ok = true,
                deviceUserIds = result.Users.Select(u => u.DeviceUserId).ToArray(),
                deviceUsers = result.Users.Select(u => new
                {
                    deviceUserId = u.DeviceUserId,
                    name = u.Name,
                    frozen = u.Frozen,
                    validFrom = u.ValidFrom?.UtcDateTime.ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'"),
                    validTo = u.ValidTo?.UtcDateTime.ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'")
                }),
                events = result.Events.Select(e => new
                {
                    deviceUserId = e.DeviceUserId,
                    occurredAt = e.OccurredAt.UtcDateTime.ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'"),
                    method = e.Method,
                    granted = e.Granted,
                    recNo = e.RecNo,
                    errorCode = e.ErrorCode,
                    denyReason = MapDenyReason(e.ErrorCode, e.Granted)
                })
            },
            result.Events,
            true);

    private static string? MapDenyReason(int? errorCode, bool granted)
    {
        if (granted || errorCode is null or 0)
        {
            return null;
        }

        return errorCode.Value switch
        {
            0x10 => "UNAUTHORIZED",
            0x14 => "VALIDITY_PERIOD",
            0x20 or 0x21 => "PERIOD_ERROR",
            0x23 => "OVERDUE",
            _ => "ERR_0x" + errorCode.Value.ToString("X")
        };
    }
}
