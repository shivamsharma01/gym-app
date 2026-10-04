using System.Collections.Concurrent;

namespace Gym.Gateway.Adapters;

/// <summary>
/// In-memory device used for Linux development and local protocol tests.
/// Does not load native libraries. Keeps face photos in memory; the PoC INSERT probe still
/// simulates the historical firmware reject.
/// </summary>
public sealed class MockDeviceAdapter : IDeviceAdapter
{
    private readonly ConcurrentDictionary<string, DeviceUserMutation> _users = new(StringComparer.Ordinal);
    private readonly ConcurrentDictionary<string, (byte[] Photo, DateTimeOffset UpdatedAt)> _faces = new(StringComparer.Ordinal);
    private readonly List<DeviceAttendanceRecord> _records = [];
    private readonly object _gate = new();
    private IDeviceEventListener? _listener;
    private DeviceConnectionConfig? _config;
    private bool _connected;
    private DateTimeOffset? _lastSeen;

    public string DeviceId => _config?.DeviceId ?? "";

    /// <summary>Tests: returns an error for photo reads of the given user, or null to read normally.</summary>
    internal Func<string, string?>? FaceReadFault { get; set; }

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
        _faces.TryRemove(deviceUserId, out _);
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
                ValidTo: u.ValidTo,
                Authority: u.Authority ?? "USER"))
            .OrderBy(u => u.DeviceUserId, StringComparer.Ordinal)
            .ToArray();
    }

    public DeviceUserSnapshot? GetUser(string deviceUserId)
    {
        if (!_connected || !_users.TryGetValue(deviceUserId, out var u))
        {
            return null;
        }

        return new DeviceUserSnapshot(u.DeviceUserId, u.Name, u.Enabled == false, u.ValidFrom, u.ValidTo, u.Authority ?? "USER");
    }

    public DeviceCommandResult UpsertFace(string deviceUserId, byte[] jpegBytes)
    {
        if (!EnsureConnected(out var err))
        {
            return DeviceCommandResult.Fail(err);
        }

        if (!_users.ContainsKey(deviceUserId))
        {
            return DeviceCommandResult.Fail("INVALID_USER: user does not exist on device");
        }

        if (jpegBytes == null || jpegBytes.Length == 0)
        {
            return DeviceCommandResult.Fail("No face image");
        }

        _faces[deviceUserId] = (jpegBytes.ToArray(), DateTimeOffset.UtcNow);
        Touch();
        return DeviceCommandResult.Success();
    }

    public DeviceFaceRead GetFace(string deviceUserId)
    {
        if (!EnsureConnected(out var err))
        {
            return DeviceFaceRead.Fail(err);
        }

        if (FaceReadFault?.Invoke(deviceUserId) is { } fault)
        {
            return DeviceFaceRead.Fail(fault);
        }

        return _faces.TryGetValue(deviceUserId, out var face)
            ? DeviceFaceRead.Found(face.Photo.ToArray(), face.UpdatedAt)
            : DeviceFaceRead.None();
    }

    public DeviceCommandResult DeleteFace(string deviceUserId)
    {
        if (!EnsureConnected(out var err))
        {
            return DeviceCommandResult.Fail(err);
        }

        _faces.TryRemove(deviceUserId, out _);
        Touch();
        return DeviceCommandResult.Success();
    }

    /// <summary>Test helper: someone enrolled or edited a user directly on the terminal.</summary>
    public void SimulateLocalUserChange(string deviceUserId, string? name, byte[]? facePhoto = null, bool emitEvent = true)
    {
        _users.AddOrUpdate(
            deviceUserId,
            new DeviceUserMutation(deviceUserId, name, Enabled: true),
            (_, existing) => existing with { Name = name ?? existing.Name });
        if (facePhoto != null)
        {
            _faces[deviceUserId] = (facePhoto.ToArray(), DateTimeOffset.UtcNow);
        }

        if (emitEvent)
        {
            _listener?.OnNormalizedEvent(new NormalizedDeviceEvent(
                "USER_CHANGED", deviceUserId, DateTimeOffset.UtcNow, "UNKNOWN", true, null, null, "mock"));
        }
    }

    /// <summary>Test helper: someone changed the admin level on the terminal ("ADMIN" or "USER").</summary>
    public void SimulateLocalAuthorityChange(string deviceUserId, string authority) =>
        _users.AddOrUpdate(
            deviceUserId,
            new DeviceUserMutation(deviceUserId, Enabled: true, Authority: authority),
            (_, existing) => existing with { Authority = authority });

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
            ValidTo = incoming.ValidTo ?? existing.ValidTo,
            Authority = incoming.Authority ?? existing.Authority
        };
}
