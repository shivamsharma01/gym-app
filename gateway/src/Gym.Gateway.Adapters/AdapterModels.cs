namespace Gym.Gateway.Adapters;

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
    DateTimeOffset? ValidTo = null);

public sealed record DeviceCommandResult(bool Ok, string? Error)
{
    public static DeviceCommandResult Success() => new(true, null);

    public static DeviceCommandResult Fail(string error) => new(false, error);
}

public sealed record EnrollmentOutcome(string Status, string? Error)
{
    /// <summary>Guided on-device enrollment; never a fabricated remote success.</summary>
    public static EnrollmentOutcome GuidedPending(string reason) => new("GUIDED_PENDING", reason);

    public static EnrollmentOutcome Failed(string error) => new("FAILED", error);
}

public sealed record DeviceAttendanceRecord(
    string? DeviceUserId,
    DateTimeOffset OccurredAt,
    string Method,
    bool Granted,
    long? RecNo,
    int? ErrorCode = null);

public sealed record DeviceReconciliationResult(
    bool Ok,
    string? Error,
    IReadOnlyList<DeviceAttendanceRecord> Events,
    IReadOnlyList<DeviceUserSnapshot> Users);

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
    DateTimeOffset? ValidTo = null);

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
