using System.Collections.Concurrent;

namespace Gym.Gateway.Adapters;

/// <summary>
/// In-memory device used for Linux development and local protocol tests.
/// Does not load native libraries. Face enrolment is never reported as success.
/// </summary>
public sealed class MockDeviceAdapter : IDeviceAdapter
{
    private readonly ConcurrentDictionary<string, DeviceUserMutation> _users = new(StringComparer.Ordinal);
    private readonly List<DeviceAttendanceRecord> _records = [];
    private readonly object _gate = new();
    private IDeviceEventListener? _listener;
    private DeviceConnectionConfig? _config;
    private bool _connected;
    private DateTimeOffset? _lastSeen;

    public string DeviceId => _config?.DeviceId ?? "";

    public DeviceConnectionStatus Connect(DeviceConnectionConfig config)
    {
        _config = config;
        _connected = true;
        _lastSeen = DateTimeOffset.UtcNow;
        return DeviceConnectionStatus.Online();
    }

    public void Disconnect()
    {
        _connected = false;
    }

    public DeviceInfoSnapshot GetDeviceInfo() =>
        new("MOCK-SERIAL", 0, 1, 0, 0, 0);

    public DeviceHealth GetHealth() =>
        new(_connected ? "ONLINE" : "OFFLINE", _lastSeen, "mock");

    public DeviceCommandResult CreateUser(DeviceUserMutation mutation)
    {
        if (!EnsureConnected(out var err))
        {
            return DeviceCommandResult.Fail(err);
        }

        _users[mutation.DeviceUserId] = mutation with { Enabled = mutation.Enabled ?? true };
        Touch();
        return DeviceCommandResult.Success();
    }

    public DeviceCommandResult UpdateUser(DeviceUserMutation mutation)
    {
        if (!EnsureConnected(out var err))
        {
            return DeviceCommandResult.Fail(err);
        }

        _users.AddOrUpdate(mutation.DeviceUserId, mutation, (_, existing) => Merge(existing, mutation));
        Touch();
        return DeviceCommandResult.Success();
    }

    public DeviceCommandResult DisableUser(string deviceUserId) =>
        SetEnabled(deviceUserId, false);

    public DeviceCommandResult EnableUser(string deviceUserId) =>
        SetEnabled(deviceUserId, true);

    public DeviceCommandResult DeleteUser(string deviceUserId)
    {
        if (!EnsureConnected(out var err))
        {
            return DeviceCommandResult.Fail(err);
        }

        _users.TryRemove(deviceUserId, out _);
        Touch();
        return DeviceCommandResult.Success();
    }

    public DeviceCommandResult UpdateValidity(DeviceUserMutation mutation) => UpdateUser(mutation);

    public IReadOnlyList<DeviceUserSnapshot> ListUsers()
    {
        if (!_connected)
        {
            return [];
        }

        return _users.Values
            .Select(u => new DeviceUserSnapshot(
                u.DeviceUserId,
                u.Name,
                Frozen: u.Enabled == false,
                ValidFrom: u.ValidFrom,
                ValidTo: u.ValidTo))
            .OrderBy(u => u.DeviceUserId, StringComparer.Ordinal)
            .ToArray();
    }

    public EnrollmentOutcome StartFaceEnrollment(string deviceUserId)
    {
        if (!_users.ContainsKey(deviceUserId))
        {
            _users[deviceUserId] = new DeviceUserMutation(deviceUserId, Enabled: true);
        }

        return EnrollmentOutcome.GuidedPending(
            "UNVERIFIED: remote face enrollment is not claimed as success; complete enrollment on the device");
    }

    public FaceProbeResult ProbeRemoteFaceInsert(string deviceUserId, byte[] jpegBytes)
    {
        _ = jpegBytes;
        if (!_connected)
        {
            return new FaceProbeResult(false, -1, "0xFFFFFFFF", null, "Device is not connected");
        }

        // Mock mirrors the known firmware reject so Linux CI can exercise the POC evidence path.
        return new FaceProbeResult(
            false,
            unchecked((int)0x10030110),
            "0x10030110",
            null,
            $"Mock: OperateAccessFaceService INSERT for {deviceUserId} rejected (simulated 0x10030110)");
    }

    public DeviceCommandResult DeleteFace(string deviceUserId) =>
        DeviceCommandResult.Fail("UNVERIFIED: DELETE_FACE is not claimed as success (OperateAccessFaceService failed in log)");

    public IReadOnlyList<DeviceAttendanceRecord> FetchAttendance(DateTimeOffset? fromUtc, DateTimeOffset? toUtc)
    {
        lock (_gate)
        {
            return _records
                .Where(r => (!fromUtc.HasValue || r.OccurredAt >= fromUtc)
                            && (!toUtc.HasValue || r.OccurredAt <= toUtc))
                .ToArray();
        }
    }

    public void RegisterEventListener(IDeviceEventListener listener) => _listener = listener;

    public DeviceCommandResult OpenDoor() => ConnectedOrFail();

    public DeviceCommandResult CloseDoor() => ConnectedOrFail();

    public DeviceCommandResult SynchronizeTime(DateTimeOffset utcNow)
    {
        Touch();
        return ConnectedOrFail();
    }

    public DeviceReconciliationResult Reconcile(DateTimeOffset? fromUtc = null, DateTimeOffset? toUtc = null)
    {
        if (!EnsureConnected(out var err))
        {
            return new DeviceReconciliationResult(false, err, [], []);
        }

        return new DeviceReconciliationResult(true, null, FetchAttendance(fromUtc, toUtc), ListUsers());
    }

    /// <summary>Test helper: emit a mock attendance event as if the terminal reported it.</summary>
    public void EmitAccessEvent(string deviceUserId, bool granted, long? recNo = null, string method = "FACE")
    {
        var record = new DeviceAttendanceRecord(deviceUserId, DateTimeOffset.UtcNow, method, granted, recNo);
        lock (_gate)
        {
            _records.Add(record);
        }

        _listener?.OnNormalizedEvent(new NormalizedDeviceEvent(
            "ACCESS", deviceUserId, record.OccurredAt, method, granted, recNo, null, null));
    }

    public IReadOnlyCollection<string> KnownUserIds => _users.Keys.ToArray();

    public void Dispose() => Disconnect();

    private DeviceCommandResult SetEnabled(string deviceUserId, bool enabled)
    {
        if (!EnsureConnected(out var err))
        {
            return DeviceCommandResult.Fail(err);
        }

        _users.AddOrUpdate(
            deviceUserId,
            new DeviceUserMutation(deviceUserId, Enabled: enabled),
            (_, existing) => existing with { Enabled = enabled });
        Touch();
        return DeviceCommandResult.Success();
    }

    private DeviceCommandResult ConnectedOrFail() =>
        EnsureConnected(out var err) ? DeviceCommandResult.Success() : DeviceCommandResult.Fail(err);

    private bool EnsureConnected(out string error)
    {
        if (_connected)
        {
            error = "";
            return true;
        }

        error = "Device is not connected";
        return false;
    }

    private void Touch() => _lastSeen = DateTimeOffset.UtcNow;

    private static DeviceUserMutation Merge(DeviceUserMutation existing, DeviceUserMutation incoming) =>
        existing with
        {
            Name = incoming.Name ?? existing.Name,
            Enabled = incoming.Enabled ?? existing.Enabled,
            ValidFrom = incoming.ValidFrom ?? existing.ValidFrom,
            ValidTo = incoming.ValidTo ?? existing.ValidTo
        };
}
