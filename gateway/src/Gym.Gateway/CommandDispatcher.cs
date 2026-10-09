using System.Globalization;
using System.Text.Json;
using Gym.Gateway.Adapters;
using Microsoft.Extensions.Logging;

namespace Gym.Gateway;

/// <summary>
/// Maps door, clock, and attendance commands onto <see cref="IDeviceAdapter"/>. Member state is
/// written only by the desired-state worker.
/// </summary>
public sealed class CommandDispatcher
{
    /// <summary>Member writes the desired-state worker owns. Door, clock, and attendance stay.</summary>
    private static readonly HashSet<string> MemberWrites = new(StringComparer.Ordinal)
    {
        "CREATE_USER", "UPDATE_USER", "UPDATE_ACCESS_POLICY", "DISABLE_USER", "ENABLE_USER",
        "UPDATE_VALIDITY", "REMOVE_USER", "ENROLL_FACE", "UPSERT_FACE", "DELETE_FACE",
        "REPORT_DEVICE_USER"
    };

    private readonly IReadOnlyDictionary<string, IDeviceAdapter> _adapters;
    private readonly ILogger<CommandDispatcher> _log;
    private readonly DeviceLocks _locks;
    private static readonly TimeSpan FinishedKept = TimeSpan.FromMinutes(30);
    private readonly object _seenGate = new();
    private readonly Dictionary<string, Task<DispatchOutcome>> _running = new(StringComparer.Ordinal);
    private readonly Dictionary<string, (DispatchOutcome Outcome, DateTimeOffset At)> _finished = new(StringComparer.Ordinal);

    public CommandDispatcher(
        IReadOnlyDictionary<string, IDeviceAdapter> adapters,
        ILogger<CommandDispatcher> log,
        DeviceLocks? locks = null)
    {
        _adapters = adapters;
        _log = log;
        _locks = locks ?? new DeviceLocks();
    }

    /// <summary>
    /// The server can deliver one command twice (it re-sends when a result was lost, and two of its
    /// dispatchers once sent the same batch). A copy that arrives while the first still runs waits for
    /// that run; a copy of a command that already succeeded gets the same result without touching the
    /// readers again. Failures are not remembered: the server's retry must run again.
    /// </summary>
    public Task<DispatchOutcome> DispatchAsync(GatewayEnvelope command)
    {
        var corr = command.CorrelationId;
        if (string.IsNullOrWhiteSpace(corr))
        {
            return DispatchOnceAsync(command);
        }

        lock (_seenGate)
        {
            PruneFinished();
            if (_finished.TryGetValue(corr, out var done))
            {
                _log.LogInformation("Command {Type} corr={Corr} was already applied at {At:HH:mm:ss}; sending the same result again",
                    command.Type, corr, done.At.ToLocalTime());
                return Task.FromResult(done.Outcome);
            }

            if (_running.TryGetValue(corr, out var running))
            {
                _log.LogInformation("Command {Type} corr={Corr} received again while it is still running; waiting for that run",
                    command.Type, corr);
                return running;
            }

            var task = RunAndRememberAsync(command, corr);
            _running[corr] = task;
            return task;
        }
    }

    private async Task<DispatchOutcome> RunAndRememberAsync(GatewayEnvelope command, string corr)
    {
        await Task.Yield();
        try
        {
            var outcome = await DispatchOnceAsync(command).ConfigureAwait(false);
            if (outcome.Ok && outcome.ResultType == ProtocolTypes.SyncResult)
            {
                lock (_seenGate)
                {
                    _finished[corr] = (outcome, DateTimeOffset.UtcNow);
                }
            }

            return outcome;
        }
        finally
        {
            lock (_seenGate)
            {
                _running.Remove(corr);
            }
        }
    }

    private void PruneFinished()
    {
        var cutoff = DateTimeOffset.UtcNow - FinishedKept;
        foreach (var key in _finished.Where(e => e.Value.At < cutoff).Select(e => e.Key).ToList())
        {
            _finished.Remove(key);
        }
    }

    private async Task<DispatchOutcome> DispatchOnceAsync(GatewayEnvelope command)
    {
        if (MemberWrites.Contains(command.Type ?? ""))
        {
            return DispatchOutcome.SyncFail("desired revisions write this reader");
        }

        if (string.IsNullOrWhiteSpace(command.DeviceId) || !_adapters.TryGetValue(command.DeviceId, out var adapter))
        {
            return DispatchOutcome.SyncFail("Unknown or unconfigured deviceId");
        }

        var health = adapter.GetHealth();
        if (health.ConnectionState == TimedDeviceAdapter.DegradedState)
        {
            // Each call would wait out its timeout and keep the broken session busy; the server retries later.
            return DispatchOutcome.SyncFail($"Reader connection is broken and reconnecting ({health.Detail}); retry later");
        }

        try
        {
            _log.LogDebug("{Type} corr={Corr} goes straight to the reader adapter", command.Type, command.CorrelationId);

            using (await _locks.AcquireAsync(command.DeviceId, $"server command {command.Type}").ConfigureAwait(false))
            {
                return await Task.Run(() => Dispatch(adapter, command)).ConfigureAwait(false);
            }
        }
        catch (Exception ex)
        {
            _log.LogError(ex, "Command {Type} failed", command.Type);
            return DispatchOutcome.SyncFail(ex.Message);
        }
    }

    private DispatchOutcome Dispatch(IDeviceAdapter adapter, GatewayEnvelope command)
    {
        if (MemberWrites.Contains(command.Type ?? ""))
        {
            return DispatchOutcome.SyncFail("desired revisions write this reader");
        }

        var payload = command.Payload;
        switch (command.Type)
        {
            case "SYNC_DEVICE_TIME":
                return From(adapter.SynchronizeTime(ReaderLocalClock.Now(DateTimeOffset.UtcNow)));
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
                    var digest = RosterDigest.Compute(recon.Users);
                    var unchanged = string.Equals(digest, Text(payload, "knownDigest"), StringComparison.Ordinal);
                    if (unchanged)
                    {
                        _log.LogInformation("Reconcile device={DeviceId}: user list unchanged since the server's last full comparison; not sent",
                            command.DeviceId);
                    }

                    return DispatchOutcome.Reconciliation(recon, rosterDigest: digest, usersUnchanged: unchanged);
                }

                _log.LogWarning("Reconcile read failed device={DeviceId}: {Error}", command.DeviceId, recon.Error);
                return DispatchOutcome.SyncFail(recon.Error ?? "reconcile failed");
            case "CLEAR_DEVICE_LOGS":
                return DispatchOutcome.SyncFail("CLEAR_DEVICE_LOGS has no verified SDK API in this adapter");
            default:
                return DispatchOutcome.SyncFail("unsupported command " + command.Type);
        }
    }

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

    /// <param name="usersUnchanged">The list matches the server's stored checksum, so it is left out.</param>
    public static DispatchOutcome Reconciliation(DeviceReconciliationResult result, bool usersComplete = true,
        string? rosterDigest = null, bool usersUnchanged = false)
    {
        var users = usersUnchanged ? [] : result.Users;
        return new(
            ProtocolTypes.ReconciliationResult,
            new
            {
                ok = true,
                usersComplete,
                usersUnchanged,
                rosterDigest,
                deviceUserIds = users.Select(u => u.DeviceUserId).ToArray(),
                deviceUsers = users.Select(u => new
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
    }

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
