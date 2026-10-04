using System.Diagnostics;
using Gym.Gateway.Adapters;
using Microsoft.Extensions.Logging;

namespace Gym.Gateway;

/// <summary>
/// Wraps every reader call. Logs how long each call takes (Debug; Information when slower than
/// <see cref="SlowCall"/>) and watches for a broken SDK session: after several connection errors in a row
/// the reader reports <see cref="Degraded"/> so the gateway stops sending it work, and
/// <see cref="Reconnect"/> logs in again with a growing pause between attempts.
/// </summary>
public sealed class TimedDeviceAdapter : IDeviceAdapter
{
    public const string OnlineState = "ONLINE";
    public const string DegradedState = "DEGRADED";

    private static readonly TimeSpan SlowCall = TimeSpan.FromSeconds(3);
    private const int FailuresBeforeDegraded = 4;

    private static readonly TimeSpan[] ReconnectPauses =
    [
        TimeSpan.FromSeconds(10), TimeSpan.FromSeconds(30), TimeSpan.FromMinutes(1),
        TimeSpan.FromMinutes(2), TimeSpan.FromMinutes(5)
    ];

    private static readonly string[] ConnectionErrors =
    [
        "timeout", "time out", "failed to send", "encrypt data fail", "protocol error", "not logged in",
        "network", "no handle"
    ];

    private readonly IDeviceAdapter _inner;
    private readonly ILogger _log;
    private readonly object _gate = new();
    private DeviceConnectionConfig? _config;
    private int _failuresInARow;
    private int _reconnectAttempts;
    private DateTimeOffset? _degradedSince;
    private DateTimeOffset _nextReconnect;
    private string? _lastError;

    public TimedDeviceAdapter(IDeviceAdapter inner, ILogger log)
    {
        _inner = inner;
        _log = log;
    }

    public string DeviceId => _inner.DeviceId;

    /// <summary>The session looks broken: calls keep failing with connection errors until a reconnect succeeds.</summary>
    public bool Degraded
    {
        get
        {
            lock (_gate)
            {
                return _degradedSince != null;
            }
        }
    }

    public bool ReconnectDue
    {
        get
        {
            lock (_gate)
            {
                return _degradedSince != null && DateTimeOffset.UtcNow >= _nextReconnect;
            }
        }
    }

    /// <summary>Human-readable health for status logs.</summary>
    public string HealthDetail
    {
        get
        {
            lock (_gate)
            {
                if (_degradedSince == null)
                {
                    return _failuresInARow == 0
                        ? "healthy"
                        : $"healthy, {_failuresInARow} connection error(s) in a row (last: {_lastError})";
                }

                var wait = Math.Max(0, (long)(_nextReconnect - DateTimeOffset.UtcNow).TotalSeconds);
                return $"connection broken for {(long)(DateTimeOffset.UtcNow - _degradedSince.Value).TotalSeconds} s, "
                       + $"reconnect attempt {_reconnectAttempts + 1} in {wait} s (last error: {_lastError})";
            }
        }
    }

    public DeviceConnectionStatus Connect(DeviceConnectionConfig config)
    {
        _config = config;
        return Time(nameof(Connect), null, () => _inner.Connect(config));
    }

    /// <summary>Logs out and in again. Clears <see cref="Degraded"/> on success, otherwise waits longer before the next try.</summary>
    public DeviceConnectionStatus Reconnect()
    {
        if (_config == null)
        {
            return DeviceConnectionStatus.Failed("Reader was never connected");
        }

        _inner.Disconnect();
        var status = Connect(_config);
        lock (_gate)
        {
            if (status.Ok)
            {
                _log.LogInformation("Reader {DeviceId}: reconnected after {Attempts} attempt(s); sending it work again",
                    DeviceId, _reconnectAttempts + 1);
                _degradedSince = null;
                _failuresInARow = 0;
                _reconnectAttempts = 0;
                return status;
            }

            var pause = ReconnectPauses[Math.Min(_reconnectAttempts, ReconnectPauses.Length - 1)];
            _reconnectAttempts++;
            _nextReconnect = DateTimeOffset.UtcNow + pause;
            _lastError = status.Error;
            _log.LogWarning("Reader {DeviceId}: reconnect attempt {Attempt} failed ({Error}); next try in {Seconds} s",
                DeviceId, _reconnectAttempts, status.Error, (long)pause.TotalSeconds);
            return status;
        }
    }

    public void Disconnect() => _inner.Disconnect();

    public DeviceInfoSnapshot GetDeviceInfo() => Time(nameof(GetDeviceInfo), null, _inner.GetDeviceInfo);

    public DeviceHealth GetHealth()
    {
        var health = _inner.GetHealth();
        return Degraded && health.ConnectionState == OnlineState
            ? health with { ConnectionState = DegradedState, Detail = HealthDetail }
            : health;
    }

    public DeviceCommandResult CreateUser(DeviceUserMutation mutation) =>
        Command(nameof(CreateUser), mutation.DeviceUserId, () => _inner.CreateUser(mutation));

    public DeviceCommandResult UpdateUser(DeviceUserMutation mutation) =>
        Command(nameof(UpdateUser), mutation.DeviceUserId, () => _inner.UpdateUser(mutation));

    public DeviceCommandResult DisableUser(string deviceUserId) =>
        Command(nameof(DisableUser), deviceUserId, () => _inner.DisableUser(deviceUserId));

    public DeviceCommandResult EnableUser(string deviceUserId) =>
        Command(nameof(EnableUser), deviceUserId, () => _inner.EnableUser(deviceUserId));

    public DeviceCommandResult DeleteUser(string deviceUserId) =>
        Command(nameof(DeleteUser), deviceUserId, () => _inner.DeleteUser(deviceUserId));

    public DeviceCommandResult UpdateValidity(DeviceUserMutation mutation) =>
        Command(nameof(UpdateValidity), mutation.DeviceUserId, () => _inner.UpdateValidity(mutation));

    public IReadOnlyList<DeviceUserSnapshot> ListUsers()
    {
        var sw = Stopwatch.StartNew();
        try
        {
            var users = _inner.ListUsers();
            Report(nameof(ListUsers), $"{users.Count} user(s)", sw.Elapsed);
            Succeeded();
            return users;
        }
        catch (DeviceReadException ex)
        {
            Report(nameof(ListUsers), "failed: " + ex.Message, sw.Elapsed);
            Failed(ex.Message);
            throw;
        }
    }

    public DeviceUserSnapshot? GetUser(string deviceUserId) =>
        Time(nameof(GetUser), deviceUserId, () => _inner.GetUser(deviceUserId));

    public DeviceCommandResult UpsertFace(string deviceUserId, byte[] jpegBytes)
    {
        var sw = Stopwatch.StartNew();
        var result = _inner.UpsertFace(deviceUserId, jpegBytes);
        Report(nameof(UpsertFace), $"user {deviceUserId}, {jpegBytes.Length / 1024} KB, ok={result.Ok}", sw.Elapsed);
        Observe(result.Ok, result.Error);
        return result;
    }

    public DeviceFaceRead GetFace(string deviceUserId)
    {
        var sw = Stopwatch.StartNew();
        var read = _inner.GetFace(deviceUserId);
        Report(nameof(GetFace), $"user {deviceUserId}, {(read.Photo?.Length ?? 0) / 1024} KB, ok={read.Ok}", sw.Elapsed);
        Observe(read.Ok, read.Error);
        return read;
    }

    public DeviceCommandResult DeleteFace(string deviceUserId) =>
        Command(nameof(DeleteFace), deviceUserId, () => _inner.DeleteFace(deviceUserId));

    public FaceProbeResult ProbeRemoteFaceInsert(string deviceUserId, byte[] jpegBytes) =>
        _inner.ProbeRemoteFaceInsert(deviceUserId, jpegBytes);

    public IReadOnlyList<DeviceAttendanceRecord> FetchAttendance(DateTimeOffset? fromUtc, DateTimeOffset? toUtc)
    {
        var sw = Stopwatch.StartNew();
        var records = _inner.FetchAttendance(fromUtc, toUtc);
        Report(nameof(FetchAttendance), $"{records.Count} record(s)", sw.Elapsed);
        return records;
    }

    public void RegisterEventListener(IDeviceEventListener listener) => _inner.RegisterEventListener(listener);

    public DeviceCommandResult OpenDoor() => Command(nameof(OpenDoor), null, _inner.OpenDoor);

    public DeviceCommandResult CloseDoor() => Command(nameof(CloseDoor), null, _inner.CloseDoor);

    public DeviceCommandResult SynchronizeTime(DateTimeOffset utcNow) =>
        Command(nameof(SynchronizeTime), null, () => _inner.SynchronizeTime(utcNow));

    public DeviceReconciliationResult Reconcile(DateTimeOffset? fromUtc = null, DateTimeOffset? toUtc = null)
    {
        var sw = Stopwatch.StartNew();
        var result = _inner.Reconcile(fromUtc, toUtc);
        Report(nameof(Reconcile), $"{result.Users.Count} user(s), {result.Events.Count} record(s), ok={result.Ok}", sw.Elapsed);
        Observe(result.Ok, result.Error);
        return result;
    }

    public void Dispose() => _inner.Dispose();

    internal static bool IsConnectionError(string? error) =>
        error != null && ConnectionErrors.Any(e => error.Contains(e, StringComparison.OrdinalIgnoreCase));

    private DeviceCommandResult Command(string call, string? user, Func<DeviceCommandResult> action)
    {
        var result = Time(call, user, action);
        Observe(result.Ok, result.Error);
        return result;
    }

    private void Observe(bool ok, string? error)
    {
        if (ok)
        {
            Succeeded();
        }
        else
        {
            Failed(error);
        }
    }

    private void Succeeded()
    {
        lock (_gate)
        {
            _failuresInARow = 0;
            if (_degradedSince != null)
            {
                _log.LogInformation("Reader {DeviceId}: calls succeed again; connection is healthy", DeviceId);
                _degradedSince = null;
                _reconnectAttempts = 0;
            }
        }
    }

    /// <summary>Only connection errors count; a reader refusing one user's data says nothing about the session.</summary>
    private void Failed(string? error)
    {
        if (!IsConnectionError(error))
        {
            return;
        }

        lock (_gate)
        {
            _failuresInARow++;
            _lastError = error;
            if (_degradedSince != null || _failuresInARow < FailuresBeforeDegraded)
            {
                return;
            }

            _degradedSince = DateTimeOffset.UtcNow;
            _nextReconnect = DateTimeOffset.UtcNow + ReconnectPauses[0];
            _log.LogWarning(
                "Reader {DeviceId}: {Count} calls in a row failed with connection errors (last: {Error}). Pausing work on this reader and reconnecting in {Seconds} s.",
                DeviceId, _failuresInARow, error, (long)ReconnectPauses[0].TotalSeconds);
        }
    }

    private T Time<T>(string call, string? user, Func<T> action)
    {
        var sw = Stopwatch.StartNew();
        try
        {
            return action();
        }
        finally
        {
            Report(call, user == null ? "" : "user " + user, sw.Elapsed);
        }
    }

    private void Report(string call, string details, TimeSpan elapsed)
    {
        var level = elapsed >= SlowCall ? LogLevel.Information : LogLevel.Debug;
        _log.Log(level, "Reader {DeviceId} {Call} took {ElapsedMs} ms {Details}",
            _inner.DeviceId, call, (long)elapsed.TotalMilliseconds, details);
    }
}
