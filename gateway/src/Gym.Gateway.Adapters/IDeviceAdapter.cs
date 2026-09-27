namespace Gym.Gateway.Adapters;

/// <summary>
/// Native-SDK boundary. Implementations must not leak NetSDK types past this interface.
/// </summary>
public interface IDeviceAdapter : IDisposable
{
    string DeviceId { get; }

    DeviceConnectionStatus Connect(DeviceConnectionConfig config);

    void Disconnect();

    DeviceInfoSnapshot GetDeviceInfo();

    DeviceHealth GetHealth();

    DeviceCommandResult CreateUser(DeviceUserMutation mutation);

    DeviceCommandResult UpdateUser(DeviceUserMutation mutation);

    DeviceCommandResult DisableUser(string deviceUserId);

    DeviceCommandResult EnableUser(string deviceUserId);

    DeviceCommandResult DeleteUser(string deviceUserId);

    DeviceCommandResult UpdateValidity(DeviceUserMutation mutation);

    /// <summary>Enumerate access users (id, name, frozen, validity). No biometrics.</summary>
    IReadOnlyList<DeviceUserSnapshot> ListUsers();

    /// <summary>One access user, or null when absent / not readable.</summary>
    DeviceUserSnapshot? GetUser(string deviceUserId);

    /// <summary>
    /// Writes the user's face photo (UPDATE, or INSERT when the user has no face yet).
    /// Image bytes must not be logged.
    /// </summary>
    DeviceCommandResult UpsertFace(string deviceUserId, byte[] jpegBytes);

    /// <summary>Reads the user's face photo as stored on the device.</summary>
    DeviceFaceRead GetFace(string deviceUserId);

    DeviceCommandResult DeleteFace(string deviceUserId);

    /// <summary>
    /// Raw OperateAccessFaceService(INSERT) evidence call kept for the PoC tooling.
    /// Image bytes must not be logged by callers.
    /// </summary>
    FaceProbeResult ProbeRemoteFaceInsert(string deviceUserId, byte[] jpegBytes);

    IReadOnlyList<DeviceAttendanceRecord> FetchAttendance(DateTimeOffset? fromUtc, DateTimeOffset? toUtc);

    void RegisterEventListener(IDeviceEventListener listener);

    DeviceCommandResult OpenDoor();

    DeviceCommandResult CloseDoor();

    DeviceCommandResult SynchronizeTime(DateTimeOffset utcNow);

    DeviceReconciliationResult Reconcile(DateTimeOffset? fromUtc = null, DateTimeOffset? toUtc = null);
}
