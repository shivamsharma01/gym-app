namespace Gym.Gateway.Adapters;

/// <summary>The reader could not return a complete answer. Never to be read as "the reader holds nothing".</summary>
public sealed class DeviceReadException(string message) : Exception(message);

public sealed record DeviceConnectionConfig(
    string DeviceId,
    string Ip,
    ushort Port,
    string Username,
    string Password,
    string? NativeDirectory = null);

public sealed record DeviceConnectionStatus(bool Ok, string ConnectionState, string? Error)
{
    public static DeviceConnectionStatus Online() => new(true, "ONLINE", null);

    public static DeviceConnectionStatus Failed(string error) => new(false, "OFFLINE", error);
}

public sealed record DeviceInfoSnapshot(
    string? SerialNumber,
    int DeviceType,
    int ChannelCount,
    int AlarmInCount,
    int AlarmOutCount,
    int DiskCount);

public sealed record DeviceHealth(string ConnectionState, DateTimeOffset? LastSeenUtc, string? Detail);

public sealed record DeviceUserMutation(
    string DeviceUserId,
    string? Name = null,
    bool? Enabled = null,
    DateTimeOffset? ValidFrom = null,
    DateTimeOffset? ValidTo = null,
    string? Authority = null,
    string? NameEx = null);

public sealed record DeviceCommandResult(bool Ok, string? Error, bool Unchanged = false)
{
    public static DeviceCommandResult Success() => new(true, null);

    /// <summary>Reader already matched; no SDK write was issued.</summary>
    public static DeviceCommandResult AlreadyMatched() => new(true, null, Unchanged: true);

    public static DeviceCommandResult Fail(string error) => new(false, error);
}

/// <summary>
/// Result of reading a user's face photo from a device. <see cref="Photo"/> is null when the user
/// has no face (Ok = true) or when the read failed (Ok = false).
/// </summary>
public sealed record DeviceFaceRead(bool Ok, byte[]? Photo, DateTimeOffset? UpdatedAtUtc, string? Error)
{
    public static DeviceFaceRead Found(byte[] photo, DateTimeOffset? updatedAtUtc) => new(true, photo, updatedAtUtc, null);

    /// <summary>The reader answered and holds no photo. <paramref name="detail"/> says how it answered (for diagnostics only).</summary>
    public static DeviceFaceRead None(string? detail = null) => new(true, null, null, detail);

    public static DeviceFaceRead Fail(string error) => new(false, null, null, error);
}

/// <summary>
/// The reader's MD5 of every stored photo, keyed by user id, read from its photo index without
/// transferring any photo. Only a complete list is Ok; a user missing from it has no photo. A
/// user with several photos has their MD5s joined in the reader's order.
/// </summary>
public sealed record FaceChecksumList(bool Ok, IReadOnlyDictionary<string, string> ByUserId, string? Error)
{
    private static readonly IReadOnlyDictionary<string, string> None =
        new Dictionary<string, string>(StringComparer.Ordinal);

    public static FaceChecksumList Complete(IReadOnlyDictionary<string, string> byUserId) => new(true, byUserId, null);

    public static FaceChecksumList Unavailable(string error) => new(false, None, error);
}

public sealed record DeviceAttendanceRecord(
    string? DeviceUserId,
    DateTimeOffset OccurredAt,
    string Method,
    bool Granted,
    long? RecNo,
    int? ErrorCode = null);

/// <summary>The attendance window could not be read completely. Nothing from it may be stored as complete.</summary>
public sealed class AttendanceReadException(string message) : Exception(message);

/// <summary>
/// Kind is ACCESS, ALARM, STATUS or USER_CHANGED (a user or face was added/changed on the device;
/// DeviceUserId is set when the device reported it).
/// </summary>
public sealed record NormalizedDeviceEvent(
    string Kind,
    string? DeviceUserId,
    DateTimeOffset OccurredAt,
    string Method,
    bool Granted,
    long? RecNo,
    string? AlarmType,
    string? Details,
    int? ErrorCode = null);

/// <summary>Device-side access user as returned by enumeration (no biometrics).</summary>
public sealed record DeviceUserSnapshot(
    string DeviceUserId,
    string? Name,
    bool Frozen,
    DateTimeOffset? ValidFrom = null,
    DateTimeOffset? ValidTo = null,
    string? Authority = null,
    string? NameEx = null,
    string? ShortName = null);

/// <summary>
/// Raw evidence from a remote face INSERT attempt. Never treat as product success —
/// even if the SDK returns true, the product path remains guided on-device until verified.
/// </summary>
public sealed record FaceProbeResult(
    bool SdkCallReturnedTrue,
    int SdkErrorCode,
    string SdkErrorHex,
    string? FailCode,
    string Detail)
{
    public bool MatchesKnownFirmwareReject =>
        !SdkCallReturnedTrue
        && (SdkErrorCode == unchecked((int)0x10030110)
            || SdkErrorHex.Contains("10030110", StringComparison.OrdinalIgnoreCase)
            || (Detail?.Contains("10030110", StringComparison.OrdinalIgnoreCase) ?? false));
}
