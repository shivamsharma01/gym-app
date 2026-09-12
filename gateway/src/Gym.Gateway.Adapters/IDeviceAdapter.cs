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

    /// <summary>Enumerate access users (id, name, frozen). No biometrics.</summary>
    IReadOnlyList<DeviceUserSnapshot> ListUsers();

    /// <summary>
    /// Remote face capture is UNVERIFIED on this firmware. Implementations must not report success.
    /// </summary>
    EnrollmentOutcome StartFaceEnrollment(string deviceUserId);

    /// <summary>
    /// Actually calls OperateAccessFaceService(INSERT) for evidence. Never claim product success.
    /// Image bytes must not be logged by callers.
    /// </summary>
    FaceProbeResult ProbeRemoteFaceInsert(string deviceUserId, byte[] jpegBytes);

    DeviceCommandResult DeleteFace(string deviceUserId);

    IReadOnlyList<DeviceAttendanceRecord> FetchAttendance(DateTimeOffset? fromUtc, DateTimeOffset? toUtc);

    void RegisterEventListener(IDeviceEventListener listener);

    DeviceCommandResult OpenDoor();

    DeviceCommandResult CloseDoor();

    DeviceCommandResult SynchronizeTime(DateTimeOffset utcNow);

    DeviceReconciliationResult Reconcile();
}
