using System.Diagnostics;
using Gym.Gateway.Adapters;
using Microsoft.Extensions.Logging;

namespace Gym.Gateway;

/// <summary>
/// Logs how long each reader call takes, so a slow sync shows whether the time goes to the reader or elsewhere.
/// Every call is logged at Debug; calls slower than <see cref="SlowCall"/> also at Information.
/// </summary>
public sealed class TimedDeviceAdapter : IDeviceAdapter
{
    private static readonly TimeSpan SlowCall = TimeSpan.FromSeconds(3);

    private readonly IDeviceAdapter _inner;
    private readonly ILogger _log;

    public TimedDeviceAdapter(IDeviceAdapter inner, ILogger log)
    {
        _inner = inner;
        _log = log;
    }

    public string DeviceId => _inner.DeviceId;

    public DeviceConnectionStatus Connect(DeviceConnectionConfig config) => Time(nameof(Connect), null, () => _inner.Connect(config));

    public void Disconnect() => _inner.Disconnect();

    public DeviceInfoSnapshot GetDeviceInfo() => Time(nameof(GetDeviceInfo), null, _inner.GetDeviceInfo);

    public DeviceHealth GetHealth() => _inner.GetHealth();

    public DeviceCommandResult CreateUser(DeviceUserMutation mutation) =>
        Time(nameof(CreateUser), mutation.DeviceUserId, () => _inner.CreateUser(mutation));

    public DeviceCommandResult UpdateUser(DeviceUserMutation mutation) =>
        Time(nameof(UpdateUser), mutation.DeviceUserId, () => _inner.UpdateUser(mutation));

    public DeviceCommandResult DisableUser(string deviceUserId) =>
        Time(nameof(DisableUser), deviceUserId, () => _inner.DisableUser(deviceUserId));

    public DeviceCommandResult EnableUser(string deviceUserId) =>
        Time(nameof(EnableUser), deviceUserId, () => _inner.EnableUser(deviceUserId));

    public DeviceCommandResult DeleteUser(string deviceUserId) =>
        Time(nameof(DeleteUser), deviceUserId, () => _inner.DeleteUser(deviceUserId));

    public DeviceCommandResult UpdateValidity(DeviceUserMutation mutation) =>
        Time(nameof(UpdateValidity), mutation.DeviceUserId, () => _inner.UpdateValidity(mutation));

    public IReadOnlyList<DeviceUserSnapshot> ListUsers()
    {
        var sw = Stopwatch.StartNew();
        var users = _inner.ListUsers();
        Report(nameof(ListUsers), $"{users.Count} user(s)", sw.Elapsed);
        return users;
    }

    public DeviceUserSnapshot? GetUser(string deviceUserId) =>
        Time(nameof(GetUser), deviceUserId, () => _inner.GetUser(deviceUserId));

    public DeviceCommandResult UpsertFace(string deviceUserId, byte[] jpegBytes)
    {
        var sw = Stopwatch.StartNew();
        var result = _inner.UpsertFace(deviceUserId, jpegBytes);
        Report(nameof(UpsertFace), $"user {deviceUserId}, {jpegBytes.Length / 1024} KB, ok={result.Ok}", sw.Elapsed);
        return result;
    }

    public DeviceFaceRead GetFace(string deviceUserId)
    {
        var sw = Stopwatch.StartNew();
        var read = _inner.GetFace(deviceUserId);
        Report(nameof(GetFace), $"user {deviceUserId}, {(read.Photo?.Length ?? 0) / 1024} KB, ok={read.Ok}", sw.Elapsed);
        return read;
    }

    public DeviceCommandResult DeleteFace(string deviceUserId) =>
        Time(nameof(DeleteFace), deviceUserId, () => _inner.DeleteFace(deviceUserId));

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

    public DeviceCommandResult OpenDoor() => Time(nameof(OpenDoor), null, _inner.OpenDoor);

    public DeviceCommandResult CloseDoor() => Time(nameof(CloseDoor), null, _inner.CloseDoor);

    public DeviceCommandResult SynchronizeTime(DateTimeOffset utcNow) =>
        Time(nameof(SynchronizeTime), null, () => _inner.SynchronizeTime(utcNow));

    public DeviceReconciliationResult Reconcile(DateTimeOffset? fromUtc = null, DateTimeOffset? toUtc = null)
    {
        var sw = Stopwatch.StartNew();
        var result = _inner.Reconcile(fromUtc, toUtc);
        Report(nameof(Reconcile), $"{result.Users.Count} user(s), {result.Events.Count} record(s), ok={result.Ok}", sw.Elapsed);
        return result;
    }

    public void Dispose() => _inner.Dispose();

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
