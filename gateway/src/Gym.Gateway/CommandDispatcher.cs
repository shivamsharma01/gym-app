using System.Text.Json;
using Gym.Gateway.Adapters;
using Microsoft.Extensions.Logging;

namespace Gym.Gateway;

/// <summary>
/// Runs the staff commands for one reader: open or close the door, or scan it now. Member state
/// reaches readers only as desired revisions, and attendance only through the attendance poll.
/// </summary>
public sealed class CommandDispatcher
{
    private readonly IReadOnlyDictionary<string, IDeviceAdapter> _adapters;
    private readonly ILogger<CommandDispatcher> _log;
    private readonly DeviceLocks _locks;
    private readonly Func<string, CancellationToken, Task<bool?>>? _scanNow;
    private static readonly TimeSpan FinishedKept = TimeSpan.FromMinutes(30);
    private readonly object _seenGate = new();
    private readonly Dictionary<string, Task<DispatchOutcome>> _running = new(StringComparer.Ordinal);
    private readonly Dictionary<string, (DispatchOutcome Outcome, DateTimeOffset At)> _finished = new(StringComparer.Ordinal);

    public CommandDispatcher(
        IReadOnlyDictionary<string, IDeviceAdapter> adapters,
        ILogger<CommandDispatcher> log,
        DeviceLocks? locks = null,
        Func<string, CancellationToken, Task<bool?>>? scanNow = null)
    {
        _adapters = adapters;
        _log = log;
        _locks = locks ?? new DeviceLocks();
        _scanNow = scanNow;
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

        if (command.Type == "SCAN_READER")
        {
            return await ScanNowAsync(command.DeviceId).ConfigureAwait(false);
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

    /// <summary>
    /// The scan takes the reader lock for each reader call itself, so it runs outside the command's
    /// lock; holding that lock here would make the scan wait for itself.
    /// </summary>
    private async Task<DispatchOutcome> ScanNowAsync(string deviceId)
    {
        if (_scanNow == null)
        {
            return DispatchOutcome.SyncFail("this gateway cannot scan readers");
        }

        try
        {
            _log.LogInformation("Reader {DeviceId}: scan requested from the app", deviceId);
            return await _scanNow(deviceId, CancellationToken.None).ConfigureAwait(false) switch
            {
                true => DispatchOutcome.SyncOk(),
                false => DispatchOutcome.SyncFail("the reader's user list failed or did not match its announced total"),
                null => DispatchOutcome.SyncFail("this reader has no worker on the gateway")
            };
        }
        catch (Exception ex)
        {
            _log.LogWarning(ex, "Reader {DeviceId}: requested scan failed", deviceId);
            return DispatchOutcome.SyncFail(ex.Message);
        }
    }

    private static DispatchOutcome Dispatch(IDeviceAdapter adapter, GatewayEnvelope command) =>
        command.Type switch
        {
            "OPEN_DOOR" => From(adapter.OpenDoor()),
            "CLOSE_DOOR" => From(adapter.CloseDoor()),
            _ => DispatchOutcome.SyncFail("unsupported command " + command.Type)
        };

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
}

public sealed record DispatchOutcome(
    string ResultType,
    object Payload,
    bool Ok = false)
{
    public static DispatchOutcome SyncOk() => new(ProtocolTypes.SyncResult, new { ok = true }, true);

    public static DispatchOutcome SyncOk(object payload) => new(ProtocolTypes.SyncResult, payload, true);

    public static DispatchOutcome SyncFail(string error) =>
        new(ProtocolTypes.SyncResult, new { ok = false, error });
}
