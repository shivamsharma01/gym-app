using System.Collections.Concurrent;
using System.Runtime.InteropServices;
using NetSDKCS;

namespace Gym.Gateway.Adapters;

/// <summary>
/// Windows-only TrueFace3000 adapter. All NetSDK types stay in this class.
/// Callbacks normalize immediately and enqueue; they never talk to the backend.
/// </summary>
public sealed class TrueFaceDeviceAdapter : IDeviceAdapter
{
    private const int WaitMs = 5000;
    private static readonly object SdkGate = new();
    private static bool s_sdkInitialized;
    private static fDisConnectCallBack? s_disconnect;
    private static fHaveReConnectCallBack? s_reconnect;
    private static fMessCallBack? s_alarm;
    private static readonly ConcurrentDictionary<nint, TrueFaceDeviceAdapter> ByLogin =
        new();

    private readonly object _gate = new();
    private DeviceConnectionConfig? _config;
    private IDeviceEventListener? _listener;
    private IntPtr _loginId = IntPtr.Zero;
    private NET_DEVICEINFO_Ex _info;
    private bool _listening;
    private DateTimeOffset? _lastSeen;

    public string DeviceId => _config?.DeviceId ?? "";

    public DeviceConnectionStatus Connect(DeviceConnectionConfig config)
    {
        _config = config;
        if (!OperatingSystem.IsWindows())
        {
            return DeviceConnectionStatus.Failed(
                "TrueFaceDeviceAdapter requires Windows and dhnetsdk.dll. Use Adapter=Mock on this host.");
        }

        var nativeDir = WindowsNativeBootstrap.ResolveNativeDirectory(config.NativeDirectory);
        if (!WindowsNativeBootstrap.TryConfigure(nativeDir, out var nativeError))
        {
            return DeviceConnectionStatus.Failed(nativeError);
        }

        lock (SdkGate)
        {
            if (!EnsureSdkInitialized())
            {
                return DeviceConnectionStatus.Failed(SdkError("SDK initialization failed"));
            }
        }

        NETClient.SetThrowErrorMessage(false);
        var info = new NET_DEVICEINFO_Ex();
        var loginId = NETClient.LoginWithHighLevelSecurity(
            config.Ip, config.Port, config.Username, config.Password,
            EM_LOGIN_SPAC_CAP_TYPE.TCP, IntPtr.Zero, ref info);

        if (loginId == IntPtr.Zero)
        {
            return DeviceConnectionStatus.Failed(SdkError("LoginWithHighLevelSecurity failed"));
        }

        _loginId = loginId;
        _info = info;
        ByLogin[loginId] = this;
        _lastSeen = DateTimeOffset.UtcNow;

        if (!NETClient.StartListen(loginId))
        {
            // Login succeeded; listening is required for live attendance but reconnect can retry.
            _listening = false;
        }
        else
        {
            _listening = true;
        }

        return DeviceConnectionStatus.Online();
    }

    public void Disconnect()
    {
        var login = _loginId;
        if (login == IntPtr.Zero)
        {
            return;
        }

        try
        {
            if (_listening)
            {
                NETClient.StopListen(login);
            }
        }
        catch
        {
            // best-effort
        }

        try
        {
            NETClient.Logout(login);
        }
        catch
        {
            // best-effort
        }

        ByLogin.TryRemove(login, out _);
        _loginId = IntPtr.Zero;
        _listening = false;
    }

    public DeviceInfoSnapshot GetDeviceInfo() =>
        new(
            NullIfEmpty(_info.sSerialNumber),
            (int)_info.nDVRType,
            _info.nChanNum,
            _info.nAlarmInPortNum,
            _info.nAlarmOutPortNum,
            _info.nDiskNum);

    public DeviceHealth GetHealth() =>
        new(_loginId == IntPtr.Zero ? "OFFLINE" : "ONLINE", _lastSeen, _listening ? "listening" : "not-listening");

    public DeviceCommandResult CreateUser(DeviceUserMutation mutation) =>
        UpsertUser(mutation, freeze: mutation.Enabled == false);

    public DeviceCommandResult UpdateUser(DeviceUserMutation mutation) =>
        UpsertUser(mutation, freeze: mutation.Enabled == false);

    public DeviceCommandResult DisableUser(string deviceUserId) =>
        UpsertUser(new DeviceUserMutation(deviceUserId, Enabled: false), freeze: true);

    public DeviceCommandResult EnableUser(string deviceUserId) =>
        UpsertUser(new DeviceUserMutation(deviceUserId, Enabled: true), freeze: false);

    public DeviceCommandResult DeleteUser(string deviceUserId)
    {
        if (!EnsureLogin(out var err))
        {
            return DeviceCommandResult.Fail(err);
        }

        var ok = NETClient.RemoveOperateAccessUserService(_loginId, [deviceUserId], out var fail, WaitMs);
        if (!ok)
        {
            return DeviceCommandResult.Fail(FailCodes("RemoveOperateAccessUserService", fail));
        }

        Touch();
        return DeviceCommandResult.Success();
    }

    public DeviceCommandResult UpdateValidity(DeviceUserMutation mutation) => UpsertUser(mutation, freeze: mutation.Enabled == false);

    public IReadOnlyList<DeviceUserSnapshot> ListUsers()
    {
        if (_loginId == IntPtr.Zero)
        {
            return [];
        }

        return QueryUsers();
    }

    public EnrollmentOutcome StartFaceEnrollment(string deviceUserId)
    {
        // Product path: do not claim remote success. POC uses ProbeRemoteFaceInsert for evidence.
        _ = deviceUserId;
        return EnrollmentOutcome.GuidedPending(
            "UNVERIFIED: remote face enrollment failed in the recorded session (0x10030110); complete on device");
    }

    public FaceProbeResult ProbeRemoteFaceInsert(string deviceUserId, byte[] jpegBytes)
    {
        if (jpegBytes == null || jpegBytes.Length == 0)
        {
            return new FaceProbeResult(false, -1, "0xFFFFFFFF", null, "No jpeg bytes provided");
        }

        if (!EnsureLogin(out var err))
        {
            return new FaceProbeResult(false, -1, "0xFFFFFFFF", null, err);
        }

        // Marshal pattern mirrors AccessDemo2s UserInfoForm.btn_AddFace_Click (never log image bytes).
        var faceInfoPtr = IntPtr.Zero;
        var photoPtr = IntPtr.Zero;
        var failCodePtr = IntPtr.Zero;
        var inParamPtr = IntPtr.Zero;
        var outParamPtr = IntPtr.Zero;
        try
        {
            faceInfoPtr = Marshal.AllocHGlobal(Marshal.SizeOf<NET_ACCESS_FACE_INFO>());
            photoPtr = Marshal.AllocHGlobal(jpegBytes.Length);
            Marshal.Copy(jpegBytes, 0, photoPtr, jpegBytes.Length);

            var faceInfo = new NET_ACCESS_FACE_INFO
            {
                szUserID = deviceUserId,
                nFacePhoto = 1,
                nInFacePhotoLen = new int[5],
                nOutFacePhotoLen = new int[5],
                pFacePhoto = new IntPtr[5]
            };
            faceInfo.nInFacePhotoLen[0] = jpegBytes.Length;
            faceInfo.nOutFacePhotoLen[0] = jpegBytes.Length;
            faceInfo.pFacePhoto[0] = photoPtr;
            Marshal.StructureToPtr(faceInfo, faceInfoPtr, false);

            var insertIn = new NET_IN_ACCESS_FACE_SERVICE_INSERT
            {
                dwSize = (uint)Marshal.SizeOf<NET_IN_ACCESS_FACE_SERVICE_INSERT>(),
                nFaceInfoNum = 1,
                pFaceInfo = faceInfoPtr
            };
            inParamPtr = Marshal.AllocHGlobal(Marshal.SizeOf<NET_IN_ACCESS_FACE_SERVICE_INSERT>());
            Marshal.StructureToPtr(insertIn, inParamPtr, false);

            failCodePtr = Marshal.AllocHGlobal(Marshal.SizeOf<NET_EM_FAILCODE>());
            Marshal.StructureToPtr(new NET_EM_FAILCODE(), failCodePtr, false);

            var insertOut = new NET_OUT_ACCESS_FACE_SERVICE_INSERT
            {
                dwSize = (uint)Marshal.SizeOf<NET_OUT_ACCESS_FACE_SERVICE_INSERT>(),
                nMaxRetNum = 1,
                pFailCode = failCodePtr
            };
            outParamPtr = Marshal.AllocHGlobal(Marshal.SizeOf<NET_OUT_ACCESS_FACE_SERVICE_INSERT>());
            Marshal.StructureToPtr(insertOut, outParamPtr, false);

            var result = NETClient.OperateAccessFaceService(
                _loginId, EM_NET_ACCESS_CTL_FACE_SERVICE.INSERT, inParamPtr, outParamPtr, WaitMs);
            var errorCode = NETClient.GetLastErrorCode();
            var errorHex = $"0x{errorCode:X8}";
            var errorText = NETClient.GetLastError() ?? "(none)";
            string? failCode = null;
            try
            {
                var outStruct = Marshal.PtrToStructure<NET_OUT_ACCESS_FACE_SERVICE_INSERT>(outParamPtr);
                if (outStruct.pFailCode != IntPtr.Zero)
                {
                    var fail = Marshal.PtrToStructure<NET_EM_FAILCODE>(outStruct.pFailCode);
                    failCode = fail.emCode.ToString();
                }
            }
            catch
            {
                // best-effort failcode read
            }

            Touch();
            var detail = result
                ? $"OperateAccessFaceService INSERT returned true for user={deviceUserId} jpegBytes={jpegBytes.Length} sdkText={errorText} (do NOT claim product remote enroll success)"
                : $"OperateAccessFaceService INSERT returned false for user={deviceUserId} jpegBytes={jpegBytes.Length} sdk={errorHex} sdkText={errorText} failCode={failCode ?? "(none)"}";
            return new FaceProbeResult(result, errorCode, errorHex, failCode, detail);
        }
        catch (Exception ex)
        {
            var errorCode = NETClient.GetLastErrorCode();
            return new FaceProbeResult(
                false,
                errorCode,
                $"0x{errorCode:X8}",
                null,
                $"OperateAccessFaceService INSERT threw {ex.GetType().Name}: {ex.Message}");
        }
        finally
        {
            if (photoPtr != IntPtr.Zero)
            {
                Marshal.FreeHGlobal(photoPtr);
            }

            if (faceInfoPtr != IntPtr.Zero)
            {
                Marshal.FreeHGlobal(faceInfoPtr);
            }

            if (failCodePtr != IntPtr.Zero)
            {
                Marshal.FreeHGlobal(failCodePtr);
            }

            if (inParamPtr != IntPtr.Zero)
            {
                Marshal.FreeHGlobal(inParamPtr);
            }

            if (outParamPtr != IntPtr.Zero)
            {
                Marshal.FreeHGlobal(outParamPtr);
            }
        }
    }

    public DeviceCommandResult DeleteFace(string deviceUserId)
    {
        _ = deviceUserId;
        return DeviceCommandResult.Fail(
            "UNVERIFIED: OperateAccessFaceService is not claimed as success on this firmware");
    }

    public IReadOnlyList<DeviceAttendanceRecord> FetchAttendance(DateTimeOffset? fromUtc, DateTimeOffset? toUtc)
    {
        if (_loginId == IntPtr.Zero)
        {
            return [];
        }

        return QueryAttendance(fromUtc, toUtc);
    }

    public void RegisterEventListener(IDeviceEventListener listener) => _listener = listener;

    public DeviceCommandResult OpenDoor() => ControlDoor(open: true);

    public DeviceCommandResult CloseDoor() => ControlDoor(open: false);

    public DeviceCommandResult SynchronizeTime(DateTimeOffset utcNow)
    {
        if (!EnsureLogin(out var err))
        {
            return DeviceCommandResult.Fail(err);
        }

        var local = utcNow.UtcDateTime;
        var ok = NETClient.SetupDeviceTime(_loginId, NET_TIME.FromDateTime(local));
        return ok ? DeviceCommandResult.Success() : DeviceCommandResult.Fail(SdkError("SetupDeviceTime failed"));
    }

    public DeviceReconciliationResult Reconcile(DateTimeOffset? fromUtc = null, DateTimeOffset? toUtc = null)
    {
        if (!EnsureLogin(out var err))
        {
            return new DeviceReconciliationResult(false, err, [], []);
        }

        var from = fromUtc ?? DateTimeOffset.UtcNow.AddDays(-1);
        var to = toUtc ?? DateTimeOffset.UtcNow.AddHours(1);
        var events = QueryAttendance(from, to);
        var users = QueryUsers();
        return new DeviceReconciliationResult(true, null, events, users);
    }

    public void Dispose()
    {
        Disconnect();
        GC.SuppressFinalize(this);
    }

    private DeviceCommandResult UpsertUser(DeviceUserMutation mutation, bool freeze)
    {
        if (!EnsureLogin(out var err))
        {
            return DeviceCommandResult.Fail(err);
        }

        if (TryGetExistingUser(mutation.DeviceUserId, out var existing)
            && UserMatchesDesired(existing, mutation, freeze))
        {
            Touch();
            return DeviceCommandResult.Success();
        }

        var user = BuildUser(mutation, freeze);
        var ok = NETClient.InsertOperateAccessUserService(_loginId, [user], out var fail, WaitMs);
        if (!ok)
        {
            // INSERT may fail when the user already exists — re-GET and treat match as success.
            if (TryGetExistingUser(mutation.DeviceUserId, out var afterFail)
                && UserMatchesDesired(afterFail, mutation, freeze))
            {
                Touch();
                return DeviceCommandResult.Success();
            }

            return DeviceCommandResult.Fail(FailCodes("InsertOperateAccessUserService", fail));
        }

        Touch();
        return DeviceCommandResult.Success();
    }

    private bool TryGetExistingUser(string deviceUserId, out NET_ACCESS_USER_INFO user)
    {
        user = default;
        if (string.IsNullOrWhiteSpace(deviceUserId) || _loginId == IntPtr.Zero)
        {
            return false;
        }

        var ok = NETClient.GetOperateAccessUserService(_loginId, [deviceUserId], out var users, out _, WaitMs);
        if (!ok || users == null || users.Length == 0 || string.IsNullOrWhiteSpace(users[0].szUserID))
        {
            return false;
        }

        user = users[0];
        return true;
    }

    private static bool UserMatchesDesired(NET_ACCESS_USER_INFO existing, DeviceUserMutation mutation, bool freeze)
    {
        var frozen = existing.nUserStatus != 0;
        if (frozen != freeze)
        {
            return false;
        }

        if (!string.IsNullOrWhiteSpace(mutation.Name))
        {
            var desired = Truncate(mutation.Name, 31);
            if (!string.Equals(NullIfEmpty(existing.szName), desired, StringComparison.Ordinal))
            {
                // Name mismatch is soft — still treat freeze/validity as authoritative
            }
        }

        return true;
    }

    private static NET_ACCESS_USER_INFO BuildUser(DeviceUserMutation mutation, bool freeze)
    {
        var user = new NET_ACCESS_USER_INFO
        {
            szUserID = mutation.DeviceUserId,
            szName = Truncate(mutation.Name ?? mutation.DeviceUserId, 31),
            emUserType = EM_USER_TYPE.NORMAL,
            nUserStatus = freeze ? 1u : 0u,
            nDoorNum = 1,
            nDoors = new int[32],
            nTimeSectionNum = 1,
            nTimeSectionNo = new int[32],
            nSpecialDaysSchedule = new int[128],
            nFirstEnterDoors = new int[32]
        };
        user.nDoors[0] = 0;
        user.nTimeSectionNo[0] = 0;
        if (mutation.ValidFrom.HasValue)
        {
            user.stuValidBeginTime = NET_TIME.FromDateTime(mutation.ValidFrom.Value.UtcDateTime);
        }

        if (mutation.ValidTo.HasValue)
        {
            user.stuValidEndTime = NET_TIME.FromDateTime(mutation.ValidTo.Value.UtcDateTime);
        }

        return user;
    }

    private DeviceCommandResult ControlDoor(bool open)
    {
        if (!EnsureLogin(out var err))
        {
            return DeviceCommandResult.Fail(err);
        }

        var ptr = IntPtr.Zero;
        try
        {
            bool ok;
            if (open)
            {
                var info = new NET_CTRL_ACCESS_OPEN
                {
                    dwSize = (uint)Marshal.SizeOf<NET_CTRL_ACCESS_OPEN>(),
                    nChannelID = 0,
                    szTargetID = IntPtr.Zero,
                    emOpenDoorType = EM_OPEN_DOOR_TYPE.REMOTE
                };
                ptr = Marshal.AllocHGlobal(Marshal.SizeOf<NET_CTRL_ACCESS_OPEN>());
                Marshal.StructureToPtr(info, ptr, true);
                ok = NETClient.ControlDevice(_loginId, EM_CtrlType.ACCESS_OPEN, ptr, WaitMs);
            }
            else
            {
                var info = new NET_CTRL_ACCESS_CLOSE
                {
                    dwSize = (uint)Marshal.SizeOf<NET_CTRL_ACCESS_CLOSE>(),
                    nChannelID = 0
                };
                ptr = Marshal.AllocHGlobal(Marshal.SizeOf<NET_CTRL_ACCESS_CLOSE>());
                Marshal.StructureToPtr(info, ptr, true);
                ok = NETClient.ControlDevice(_loginId, EM_CtrlType.ACCESS_CLOSE, ptr, WaitMs);
            }

            return ok
                ? DeviceCommandResult.Success()
                : DeviceCommandResult.Fail(SdkError(open ? "ACCESS_OPEN failed" : "ACCESS_CLOSE failed"));
        }
        finally
        {
            if (ptr != IntPtr.Zero)
            {
                Marshal.FreeHGlobal(ptr);
            }
        }
    }

    private IReadOnlyList<DeviceAttendanceRecord> QueryAttendance(DateTimeOffset? fromUtc, DateTimeOffset? toUtc)
    {
        var findId = IntPtr.Zero;
        try
        {
            var condition = new NET_FIND_RECORD_ACCESSCTLCARDREC_CONDITION_EX
            {
                dwSize = (uint)Marshal.SizeOf<NET_FIND_RECORD_ACCESSCTLCARDREC_CONDITION_EX>(),
                bTimeEnable = fromUtc.HasValue || toUtc.HasValue
            };
            if (fromUtc.HasValue)
            {
                condition.stStartTime = NET_TIME.FromDateTime(fromUtc.Value.UtcDateTime);
            }

            if (toUtc.HasValue)
            {
                condition.stEndTime = NET_TIME.FromDateTime(toUtc.Value.UtcDateTime);
            }

            object boxed = condition;
            if (!NETClient.FindRecord(
                    _loginId,
                    EM_NET_RECORD_TYPE.ACCESSCTLCARDREC_EX,
                    boxed,
                    typeof(NET_FIND_RECORD_ACCESSCTLCARDREC_CONDITION_EX),
                    ref findId,
                    10000)
                || findId == IntPtr.Zero)
            {
                return [];
            }

            const int page = 20;
            var records = new List<DeviceAttendanceRecord>();
            while (true)
            {
                var ls = new List<object>(page);
                for (var i = 0; i < page; i++)
                {
                    var rec = new NET_RECORDSET_ACCESS_CTL_CARDREC
                    {
                        dwSize = (uint)Marshal.SizeOf<NET_RECORDSET_ACCESS_CTL_CARDREC>()
                    };
                    ls.Add(rec);
                }

                var retNum = 0;
                NETClient.FindNextRecord(findId, page, ref retNum, ref ls, typeof(NET_RECORDSET_ACCESS_CTL_CARDREC), 10000);
                if (retNum <= 0)
                {
                    break;
                }

                for (var i = 0; i < retNum && i < ls.Count; i++)
                {
                    var info = (NET_RECORDSET_ACCESS_CTL_CARDREC)ls[i];
                    records.Add(ToAttendance(info));
                }

                if (retNum < page)
                {
                    break;
                }
            }

            return records;
        }
        finally
        {
            if (findId != IntPtr.Zero)
            {
                NETClient.FindRecordClose(findId);
            }
        }
    }

    private IReadOnlyList<DeviceUserSnapshot> QueryUsers()
    {
        var startIn = new NET_IN_USERINFO_START_FIND
        {
            dwSize = (uint)Marshal.SizeOf<NET_IN_USERINFO_START_FIND>()
        };
        var startOut = new NET_OUT_USERINFO_START_FIND
        {
            dwSize = (uint)Marshal.SizeOf<NET_OUT_USERINFO_START_FIND>(),
            nCapNum = 50
        };
        var find = NETClient.StartFindUserInfo(_loginId, ref startIn, ref startOut, WaitMs);
        if (find == IntPtr.Zero)
        {
            return [];
        }

        var users = new List<DeviceUserSnapshot>();
        try
        {
            const int page = 50;
            var startNo = 0;
            while (true)
            {
                var findIn = new NET_IN_USERINFO_DO_FIND
                {
                    dwSize = (uint)Marshal.SizeOf<NET_IN_USERINFO_DO_FIND>(),
                    nStartNo = startNo,
                    nCount = page
                };
                var findOut = new NET_OUT_USERINFO_DO_FIND
                {
                    dwSize = (uint)Marshal.SizeOf<NET_OUT_USERINFO_DO_FIND>(),
                    nMaxNum = page
                };
                var buffer = Marshal.AllocHGlobal(Marshal.SizeOf<NET_ACCESS_USER_INFO>() * page);
                try
                {
                    findOut.pstuInfo = buffer;
                    if (!NETClient.DoFindUserInfo(find, ref findIn, ref findOut, WaitMs) || findOut.nRetNum <= 0)
                    {
                        break;
                    }

                    for (var i = 0; i < findOut.nRetNum; i++)
                    {
                        var ptr = IntPtr.Add(buffer, Marshal.SizeOf<NET_ACCESS_USER_INFO>() * i);
                        var user = Marshal.PtrToStructure<NET_ACCESS_USER_INFO>(ptr);
                        if (!string.IsNullOrWhiteSpace(user.szUserID))
                        {
                            users.Add(new DeviceUserSnapshot(
                                user.szUserID.Trim(),
                                NullIfEmpty(user.szName),
                                Frozen: user.nUserStatus != 0,
                                ValidFrom: NetTimeOrNull(user.stuValidBeginTime),
                                ValidTo: NetTimeOrNull(user.stuValidEndTime)));
                        }
                    }

                    if (findOut.nRetNum < page)
                    {
                        break;
                    }

                    startNo += findOut.nRetNum;
                }
                finally
                {
                    Marshal.FreeHGlobal(buffer);
                }
            }
        }
        finally
        {
            NETClient.StopFindUserInfo(find);
        }

        return users;
    }

    private bool EnsureLogin(out string error)
    {
        if (_loginId != IntPtr.Zero)
        {
            error = "";
            return true;
        }

        error = "Device is not logged in";
        return false;
    }

    private void Touch() => _lastSeen = DateTimeOffset.UtcNow;

    private static bool EnsureSdkInitialized()
    {
        if (s_sdkInitialized)
        {
            return true;
        }

        NETClient.SetThrowErrorMessage(false);
        s_disconnect = OnDisconnect;
        s_reconnect = OnReconnect;
        s_alarm = OnAlarm;
        var ok = NETClient.InitWithDefaultSetting(s_disconnect, s_reconnect, IntPtr.Zero, null);
        if (!ok)
        {
            return false;
        }

        NETClient.SetDVRMessCallBack(s_alarm, IntPtr.Zero);
        s_sdkInitialized = true;
        return true;
    }

    private static void OnDisconnect(IntPtr loginId, IntPtr pchDvrIp, int nDvrPort, IntPtr dwUser)
    {
        if (!ByLogin.TryGetValue(loginId, out var adapter))
        {
            return;
        }

        adapter._lastSeen = DateTimeOffset.UtcNow;
        adapter._listener?.OnNormalizedEvent(new NormalizedDeviceEvent(
            "STATUS", null, DateTimeOffset.UtcNow, "UNKNOWN", false, null, null, "disconnected"));
    }

    private static void OnReconnect(IntPtr loginId, IntPtr pchDvrIp, int nDvrPort, IntPtr dwUser)
    {
        if (!ByLogin.TryGetValue(loginId, out var adapter))
        {
            return;
        }

        adapter._lastSeen = DateTimeOffset.UtcNow;
        adapter._listener?.OnNormalizedEvent(new NormalizedDeviceEvent(
            "STATUS", null, DateTimeOffset.UtcNow, "UNKNOWN", true, null, null, "reconnected"));
    }

    private static bool OnAlarm(int lCommand, IntPtr lLoginID, IntPtr pBuf, uint dwBufLen, IntPtr pchDVRIP, int nDVRPort, IntPtr dwUser)
    {
        try
        {
            if (!ByLogin.TryGetValue(lLoginID, out var adapter) || pBuf == IntPtr.Zero)
            {
                return true;
            }

            var type = (EM_ALARM_TYPE)lCommand;
            if (type == EM_ALARM_TYPE.ALARM_ACCESS_CTL_EVENT)
            {
                var info = Marshal.PtrToStructure<NET_ALARM_ACCESS_CTL_EVENT_INFO>(pBuf);
                adapter._lastSeen = DateTimeOffset.UtcNow;
                adapter._listener?.OnNormalizedEvent(new NormalizedDeviceEvent(
                    "ACCESS",
                    NullIfEmpty(info.szUserID),
                    ToUtc(info.stuTime),
                    MapMethod(info.emOpenMethod),
                    info.bStatus,
                    info.nPunchingRecNo > 0 ? info.nPunchingRecNo : null,
                    null,
                    null,
                    info.bStatus ? null : info.nErrorCode));
                return true;
            }

            if (IsSecurityAlarm(type, out var alarmName))
            {
                adapter._listener?.OnNormalizedEvent(new NormalizedDeviceEvent(
                    "ALARM", null, DateTimeOffset.UtcNow, "UNKNOWN", false, null, alarmName, type.ToString()));
            }
        }
        catch
        {
            // Never let native callback exceptions escape.
        }

        return true;
    }

    private static bool IsSecurityAlarm(EM_ALARM_TYPE type, out string name)
    {
        switch (type)
        {
            case EM_ALARM_TYPE.ALARM_ACCESS_CTL_NOT_CLOSE:
                name = "NOT_CLOSE";
                return true;
            case EM_ALARM_TYPE.ALARM_ACCESS_CTL_BREAK_IN:
                name = "BREAK_IN";
                return true;
            case EM_ALARM_TYPE.ALARM_ACCESS_CTL_REPEAT_ENTER:
                name = "REPEAT_ENTER";
                return true;
            case EM_ALARM_TYPE.ALARM_ACCESS_CTL_DURESS:
                name = "DURESS";
                return true;
            default:
                name = type.ToString();
                return type.ToString().StartsWith("ALARM_ACCESS_CTL", StringComparison.Ordinal);
        }
    }

    private static DeviceAttendanceRecord ToAttendance(NET_RECORDSET_ACCESS_CTL_CARDREC info) =>
        new(NullIfEmpty(info.szUserID), ToUtc(info.stuTime), MapMethod(info.emMethod), info.bStatus,
            info.nRecNo, info.bStatus ? null : info.nErrorCode);

    private static string MapMethod(EM_ACCESS_DOOROPEN_METHOD method) =>
        method == EM_ACCESS_DOOROPEN_METHOD.FACE_RECOGNITION ? "FACE" : method.ToString();

    private static DateTimeOffset ToUtc(NET_TIME time)
    {
        try
        {
            var dt = new DateTime(
                (int)time.dwYear, (int)time.dwMonth, (int)time.dwDay,
                (int)time.dwHour, (int)time.dwMinute, (int)time.dwSecond,
                DateTimeKind.Utc);
            return new DateTimeOffset(dt);
        }
        catch
        {
            return DateTimeOffset.UtcNow;
        }
    }

    private static string SdkError(string headline) =>
        $"{headline}: {NETClient.GetLastError()}";

    private static string FailCodes(string op, NET_EM_FAILCODE[]? codes)
    {
        var sdk = NETClient.GetLastError();
        if (codes == null || codes.Length == 0)
        {
            return $"{op} failed: {sdk}";
        }

        return $"{op} failed: {sdk} codes=[{string.Join(",", codes.Select(c => c.emCode))}]";
    }

    private static string Truncate(string value, int max) =>
        value.Length <= max ? value : value[..max];

    private static string? NullIfEmpty(string? value) =>
        string.IsNullOrWhiteSpace(value) ? null : value.Trim();

    /// <summary>Null when the device left validity unset (zeroed NET_TIME).</summary>
    private static DateTimeOffset? NetTimeOrNull(NET_TIME time)
    {
        if (time.dwYear == 0 || time.dwMonth == 0 || time.dwDay == 0)
        {
            return null;
        }

        try
        {
            var dt = new DateTime(
                (int)time.dwYear,
                (int)time.dwMonth,
                (int)time.dwDay,
                (int)time.dwHour,
                (int)time.dwMinute,
                (int)time.dwSecond,
                DateTimeKind.Utc);
            return new DateTimeOffset(dt);
        }
        catch
        {
            return null;
        }
    }
}
