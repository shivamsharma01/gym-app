using System.Collections.Concurrent;
using System.Diagnostics;
using System.Runtime.InteropServices;
using NetSDKCS;

namespace Gym.Gateway.SdkProbe;

internal sealed record UserRow(
    string Id, string? Name, uint Status, string ValidFrom, string ValidTo, string UpdateRaw, DateTime? UpdatedAt);

internal sealed record FaceRow(string UserId, string[] Md5s);

internal sealed record FaceList(
    bool Supported, uint Total, List<FaceRow> Rows, int Calls, long StartMs, long TotalMs, string? Error, bool PagingStuck);

internal sealed record FaceRead(
    bool Ok, byte[]? Photo, string UpdateRaw, DateTime? UpdatedAt, string? FailCode, string? Error, long Ms);

internal sealed record UserListResult(List<UserRow> Users, int Total, int CapNum, int Calls, long Ms, string? Error);

internal sealed record AlarmSeen(DateTime AtUtc, string Type, string Detail);

internal sealed record Punch(int RecNo, string? UserId, string TimeRaw, DateTime? Time, bool Granted, string Method, int ErrorCode);

internal sealed record PunchQuery(List<Punch> Rows, int Calls, long Ms, string? Error, bool Capped);

/// <summary>One logged-in reader. Uses NetSDK directly so raw fields (update times, MD5s) stay visible.</summary>
internal sealed class ReaderSession : IDisposable
{
    public const int WaitMs = 5000;
    public const int ListWaitMs = 10000;
    public const int FaceWaitMs = 8000;
    private const int PhotoBuffer = 256 * 1024;

    private static readonly ConcurrentDictionary<IntPtr, ReaderSession> ByLogin = new();
    private static fDisConnectCallBack? s_disconnect;
    private static fHaveReConnectCallBack? s_reconnect;
    private static fMessCallBack? s_alarm;

    private ReaderSession(DeviceTarget target, IntPtr login, NET_DEVICEINFO_Ex info)
    {
        Target = target;
        Login = login;
        Info = info;
    }

    public DeviceTarget Target { get; }
    public IntPtr Login { get; private set; }
    public NET_DEVICEINFO_Ex Info { get; }
    public bool Listening { get; private set; }
    public ConcurrentQueue<AlarmSeen> Alarms { get; } = new();
    public string Name => Target.DeviceId;

    public static bool Init(out string error)
    {
        NETClient.SetThrowErrorMessage(false);
        s_disconnect = (login, _, _, _) => Note(login, "SDK_DISCONNECTED", "");
        s_reconnect = (login, _, _, _) => Note(login, "SDK_RECONNECTED", "");
        s_alarm = OnAlarm;
        if (!NETClient.InitWithDefaultSetting(s_disconnect, s_reconnect, IntPtr.Zero, null))
        {
            error = "SDK init failed: " + NETClient.GetLastError();
            return false;
        }

        NETClient.SetDVRMessCallBack(s_alarm, IntPtr.Zero);
        error = "";
        return true;
    }

    public static ReaderSession? Open(DeviceTarget target, out string error)
    {
        var info = new NET_DEVICEINFO_Ex();
        var login = NETClient.LoginWithHighLevelSecurity(
            target.Ip, target.Port, target.Username, target.Password, EM_LOGIN_SPAC_CAP_TYPE.TCP, IntPtr.Zero, ref info);
        if (login == IntPtr.Zero)
        {
            error = "login failed: " + NETClient.GetLastError();
            return null;
        }

        var session = new ReaderSession(target, login, info);
        ByLogin[login] = session;
        session.Listening = NETClient.StartListen(login);
        error = "";
        return session;
    }

    public List<AlarmSeen> DrainAlarms()
    {
        var list = new List<AlarmSeen>();
        while (Alarms.TryDequeue(out var a))
        {
            list.Add(a);
        }

        return list;
    }

    public DateTime? DeviceTime()
    {
        var t = new NET_TIME();
        return NETClient.QueryDeviceTime(Login, ref t, WaitMs) ? ToDate(t) : null;
    }

    public UserListResult ListUsers(int page, string? userIdFilter = null)
    {
        var watch = Stopwatch.StartNew();
        var startIn = new NET_IN_USERINFO_START_FIND
        {
            dwSize = (uint)Marshal.SizeOf<NET_IN_USERINFO_START_FIND>(),
            szUserID = userIdFilter ?? ""
        };
        var startOut = new NET_OUT_USERINFO_START_FIND
        {
            dwSize = (uint)Marshal.SizeOf<NET_OUT_USERINFO_START_FIND>(),
            nCapNum = page
        };
        var find = NETClient.StartFindUserInfo(Login, ref startIn, ref startOut, ListWaitMs);
        if (find == IntPtr.Zero)
        {
            return new UserListResult([], 0, 0, 0, watch.ElapsedMilliseconds, "StartFindUserInfo failed: " + NETClient.GetLastError());
        }

        var users = new List<UserRow>();
        var calls = 0;
        string? error = null;
        var size = Marshal.SizeOf<NET_ACCESS_USER_INFO>();
        var buffer = Marshal.AllocHGlobal(size * page);
        try
        {
            var start = 0;
            while (startOut.nTotalCount <= 0 || start < startOut.nTotalCount)
            {
                var read = 0;
                for (var attempt = 0; attempt < 3 && read <= 0; attempt++)
                {
                    calls++;
                    var findIn = new NET_IN_USERINFO_DO_FIND
                    {
                        dwSize = (uint)Marshal.SizeOf<NET_IN_USERINFO_DO_FIND>(),
                        nStartNo = start,
                        nCount = page
                    };
                    var findOut = new NET_OUT_USERINFO_DO_FIND
                    {
                        dwSize = (uint)Marshal.SizeOf<NET_OUT_USERINFO_DO_FIND>(),
                        nMaxNum = page,
                        pstuInfo = buffer
                    };
                    if (NETClient.DoFindUserInfo(find, ref findIn, ref findOut, ListWaitMs) && findOut.nRetNum > 0)
                    {
                        read = findOut.nRetNum;
                        for (var i = 0; i < read; i++)
                        {
                            var u = Marshal.PtrToStructure<NET_ACCESS_USER_INFO>(IntPtr.Add(buffer, size * i));
                            if (!string.IsNullOrWhiteSpace(u.szUserID))
                            {
                                users.Add(ToRow(u));
                            }
                        }
                    }
                }

                if (read <= 0)
                {
                    if (startOut.nTotalCount > 0 && start < startOut.nTotalCount)
                    {
                        error = $"page at {start} failed 3 times: {NETClient.GetLastError()}";
                    }

                    break;
                }

                start += read;
                if (read < page)
                {
                    break;
                }
            }
        }
        finally
        {
            Marshal.FreeHGlobal(buffer);
            NETClient.StopFindUserInfo(find);
        }

        return new UserListResult(users, startOut.nTotalCount, startOut.nCapNum, calls, watch.ElapsedMilliseconds, error);
    }

    public NET_ACCESS_USER_INFO? GetUser(string userId)
    {
        var ok = NETClient.GetOperateAccessUserService(Login, [userId], out var users, out _, WaitMs);
        return ok && users is { Length: > 0 } && !string.IsNullOrWhiteSpace(users[0].szUserID) ? users[0] : null;
    }

    /// <summary>The raw answer to a single-user read: call result, fail code, SDK error and what came back.</summary>
    public string DescribeGet(string userId)
    {
        var watch = Stopwatch.StartNew();
        var ok = NETClient.GetOperateAccessUserService(Login, [userId], out var users, out var fails, WaitMs);
        var ms = watch.ElapsedMilliseconds;
        var code = $"0x{NETClient.GetLastErrorCode():X8}";
        var fail = fails is { Length: > 0 } ? fails[0].emCode.ToString() : "(none)";
        var returned = users is { Length: > 0 } ? $"\"{users[0].szUserID?.Trim()}\"" : "(no record)";
        return $"ok={ok} failCode={fail} sdkError={code} {NETClient.GetLastError()} returnedId={returned} in {ms} ms";
    }

    public FaceList ListFaces(string? userId, int page)
    {
        var watch = Stopwatch.StartNew();
        var startIn = new NET_IN_FACEINFO_START_FIND
        {
            dwSize = (uint)Marshal.SizeOf<NET_IN_FACEINFO_START_FIND>(),
            szUserID = userId ?? ""
        };
        var startOut = new NET_OUT_FACEINFO_START_FIND { dwSize = (uint)Marshal.SizeOf<NET_OUT_FACEINFO_START_FIND>() };
        var find = NETClient.StartFindFaceInfo(Login, startIn, ref startOut, ListWaitMs);
        var startMs = watch.ElapsedMilliseconds;
        if (find == IntPtr.Zero)
        {
            return new FaceList(false, 0, [], 0, startMs, startMs, "StartFindFaceInfo failed: " + NETClient.GetLastError(), false);
        }

        var rows = new List<FaceRow>();
        var calls = 0;
        string? error = null;
        var stuck = false;
        var size = Marshal.SizeOf<NET_FACEINFO>();
        var buffer = Marshal.AllocHGlobal(size * page);
        try
        {
            var start = 0;
            string? previousFirst = null;
            var maxCalls = (int)(startOut.nTotalCount / (uint)Math.Max(1, page)) + 20;
            while (calls < maxCalls && (startOut.nTotalCount == 0 || start < startOut.nTotalCount))
            {
                calls++;
                var findIn = new NET_IN_FACEINFO_DO_FIND
                {
                    dwSize = (uint)Marshal.SizeOf<NET_IN_FACEINFO_DO_FIND>(),
                    nStartNo = start,
                    nCount = page
                };
                var findOut = new NET_OUT_FACEINFO_DO_FIND
                {
                    dwSize = (uint)Marshal.SizeOf<NET_OUT_FACEINFO_DO_FIND>(),
                    pstuInfo = buffer,
                    nMaxNum = page,
                    byReserved = new byte[4]
                };
                if (!NETClient.DoFindFaceInfo(find, findIn, ref findOut, ListWaitMs))
                {
                    error = $"DoFindFaceInfo at {start} failed: {NETClient.GetLastError()}";
                    break;
                }

                if (findOut.nRetNum <= 0)
                {
                    break;
                }

                var batch = new List<FaceRow>();
                for (var i = 0; i < findOut.nRetNum && i < page; i++)
                {
                    var info = Marshal.PtrToStructure<NET_FACEINFO>(IntPtr.Add(buffer, size * i));
                    var md5s = (info.szMD5 ?? [])
                        .Take(Math.Clamp(info.nMD5, 0, 5))
                        .Select(m => (m.szDM5 ?? "").Trim().ToUpperInvariant())
                        .Where(m => m.Length > 0)
                        .ToArray();
                    batch.Add(new FaceRow((info.szUserID ?? "").Trim(), md5s));
                }

                if (batch.Count > 0 && start > 0 && batch[0].UserId == previousFirst)
                {
                    stuck = true;
                    break;
                }

                previousFirst = batch.Count > 0 ? batch[0].UserId : null;
                rows.AddRange(batch);
                start += findOut.nRetNum;
                if (startOut.nTotalCount == 0 && findOut.nRetNum < page)
                {
                    break;
                }
            }
        }
        finally
        {
            Marshal.FreeHGlobal(buffer);
            NETClient.StopFindFaceInfo(find);
        }

        return new FaceList(true, startOut.nTotalCount, rows, calls, startMs, watch.ElapsedMilliseconds, error, stuck);
    }

    public FaceRead GetFace(string userId)
    {
        var watch = Stopwatch.StartNew();
        IntPtr inPtr = IntPtr.Zero, outPtr = IntPtr.Zero, facePtr = IntPtr.Zero, photoPtr = IntPtr.Zero, failPtr = IntPtr.Zero;
        try
        {
            var input = new NET_IN_ACCESS_FACE_SERVICE_GET
            {
                dwSize = (uint)Marshal.SizeOf<NET_IN_ACCESS_FACE_SERVICE_GET>(),
                nUserNum = 1,
                szUserID = new NET_IN_ACCESS_FACE_SERVICE_UserID[100],
                szUserIDEx = "",
                bUserIDEx = false
            };
            input.szUserID[0].userID = userId;
            inPtr = Alloc(input);
            photoPtr = Marshal.AllocHGlobal(PhotoBuffer);
            var face = new NET_ACCESS_FACE_INFO
            {
                nInFacePhotoLen = new int[5],
                nOutFacePhotoLen = new int[5],
                pFacePhoto = new IntPtr[5]
            };
            face.nInFacePhotoLen[0] = PhotoBuffer;
            face.pFacePhoto[0] = photoPtr;
            facePtr = Alloc(face);
            failPtr = Alloc(new NET_EM_FAILCODE());
            outPtr = Alloc(new NET_OUT_ACCESS_FACE_SERVICE_GET
            {
                dwSize = (uint)Marshal.SizeOf<NET_OUT_ACCESS_FACE_SERVICE_GET>(),
                nMaxRetNum = 1,
                pFaceInfo = facePtr,
                pFailCode = failPtr
            });

            var ok = NETClient.OperateAccessFaceService(Login, EM_NET_ACCESS_CTL_FACE_SERVICE.GET, inPtr, outPtr, FaceWaitMs);
            var fail = Marshal.PtrToStructure<NET_EM_FAILCODE>(failPtr).emCode.ToString();
            var read = Marshal.PtrToStructure<NET_ACCESS_FACE_INFO>(facePtr);
            var raw = Raw(read.stuUpdateTime);
            if (!ok)
            {
                var noPhoto = fail is nameof(EM_FAILCODE.NO_RECORD) or nameof(EM_FAILCODE.INVALID_FACE) or nameof(EM_FAILCODE.INVALID_USER);
                return new FaceRead(noPhoto, null, raw, null, fail, noPhoto ? null : NETClient.GetLastError(), watch.ElapsedMilliseconds);
            }

            var len = read.nOutFacePhotoLen is { Length: > 0 } ? read.nOutFacePhotoLen[0] : 0;
            byte[]? bytes = null;
            if (read.nFacePhoto > 0 && len > 0)
            {
                bytes = new byte[Math.Min(len, PhotoBuffer)];
                Marshal.Copy(photoPtr, bytes, 0, bytes.Length);
            }

            return new FaceRead(true, bytes, raw, ToDate(read.stuUpdateTime), fail, null, watch.ElapsedMilliseconds);
        }
        catch (Exception ex)
        {
            return new FaceRead(false, null, "", null, null, ex.GetType().Name + ": " + ex.Message, watch.ElapsedMilliseconds);
        }
        finally
        {
            FreeAll(inPtr, outPtr, facePtr, photoPtr, failPtr);
        }
    }

    public string InsertUser(NET_ACCESS_USER_INFO user)
    {
        var ok = NETClient.InsertOperateAccessUserService(Login, [user], out var fail, WaitMs);
        return ok ? "ok" : $"FAILED {NETClient.GetLastError()} codes=[{string.Join(",", (fail ?? []).Select(f => f.emCode))}]";
    }

    public string RemoveUser(string userId)
    {
        var ok = NETClient.RemoveOperateAccessUserService(Login, [userId], out var fail, WaitMs);
        return ok ? "ok" : $"FAILED {NETClient.GetLastError()} codes=[{string.Join(",", (fail ?? []).Select(f => f.emCode))}]";
    }

    public string WriteFace(EM_NET_ACCESS_CTL_FACE_SERVICE op, string userId, byte[] jpeg)
    {
        IntPtr facePtr = IntPtr.Zero, photoPtr = IntPtr.Zero, failPtr = IntPtr.Zero, inPtr = IntPtr.Zero, outPtr = IntPtr.Zero;
        try
        {
            photoPtr = Marshal.AllocHGlobal(jpeg.Length);
            Marshal.Copy(jpeg, 0, photoPtr, jpeg.Length);
            var face = new NET_ACCESS_FACE_INFO
            {
                szUserID = userId,
                nFacePhoto = 1,
                nInFacePhotoLen = new int[5],
                nOutFacePhotoLen = new int[5],
                pFacePhoto = new IntPtr[5]
            };
            face.nInFacePhotoLen[0] = jpeg.Length;
            face.nOutFacePhotoLen[0] = jpeg.Length;
            face.pFacePhoto[0] = photoPtr;
            facePtr = Alloc(face);
            failPtr = Alloc(new NET_EM_FAILCODE());
            if (op == EM_NET_ACCESS_CTL_FACE_SERVICE.UPDATE)
            {
                inPtr = Alloc(new NET_IN_ACCESS_FACE_SERVICE_UPDATE
                {
                    dwSize = (uint)Marshal.SizeOf<NET_IN_ACCESS_FACE_SERVICE_UPDATE>(),
                    nFaceInfoNum = 1,
                    pFaceInfo = facePtr
                });
                outPtr = Alloc(new NET_OUT_ACCESS_FACE_SERVICE_UPDATE
                {
                    dwSize = (uint)Marshal.SizeOf<NET_OUT_ACCESS_FACE_SERVICE_UPDATE>(),
                    nMaxRetNum = 1,
                    pFailCode = failPtr
                });
            }
            else
            {
                inPtr = Alloc(new NET_IN_ACCESS_FACE_SERVICE_INSERT
                {
                    dwSize = (uint)Marshal.SizeOf<NET_IN_ACCESS_FACE_SERVICE_INSERT>(),
                    nFaceInfoNum = 1,
                    pFaceInfo = facePtr
                });
                outPtr = Alloc(new NET_OUT_ACCESS_FACE_SERVICE_INSERT
                {
                    dwSize = (uint)Marshal.SizeOf<NET_OUT_ACCESS_FACE_SERVICE_INSERT>(),
                    nMaxRetNum = 1,
                    pFailCode = failPtr
                });
            }

            var ok = NETClient.OperateAccessFaceService(Login, op, inPtr, outPtr, FaceWaitMs);
            return ok ? "ok" : $"FAILED {NETClient.GetLastError()} failCode={Marshal.PtrToStructure<NET_EM_FAILCODE>(failPtr).emCode}";
        }
        catch (Exception ex)
        {
            return $"threw {ex.GetType().Name}: {ex.Message}";
        }
        finally
        {
            FreeAll(photoPtr, facePtr, failPtr, inPtr, outPtr);
        }
    }

    public string RemoveFace(string userId)
    {
        IntPtr inPtr = IntPtr.Zero, outPtr = IntPtr.Zero, failPtr = IntPtr.Zero;
        try
        {
            var input = new NET_IN_ACCESS_FACE_SERVICE_REMOVE
            {
                dwSize = (uint)Marshal.SizeOf<NET_IN_ACCESS_FACE_SERVICE_REMOVE>(),
                nUserNum = 1,
                szUserID = new NET_IN_ACCESS_FACE_SERVICE_UserID[100],
                szUserIDEx = "",
                bUserIDEx = false
            };
            input.szUserID[0].userID = userId;
            inPtr = Alloc(input);
            failPtr = Alloc(new NET_EM_FAILCODE());
            outPtr = Alloc(new NET_OUT_ACCESS_FACE_SERVICE_REMOVE
            {
                dwSize = (uint)Marshal.SizeOf<NET_OUT_ACCESS_FACE_SERVICE_REMOVE>(),
                nMaxRetNum = 1,
                pFailCode = failPtr
            });
            var ok = NETClient.OperateAccessFaceService(Login, EM_NET_ACCESS_CTL_FACE_SERVICE.REMOVE, inPtr, outPtr, WaitMs);
            return ok ? "ok" : $"FAILED {NETClient.GetLastError()} failCode={Marshal.PtrToStructure<NET_EM_FAILCODE>(failPtr).emCode}";
        }
        finally
        {
            FreeAll(inPtr, outPtr, failPtr);
        }
    }

    /// <summary>Attendance records in a reader-clock time window, optionally sorted by record number.</summary>
    public PunchQuery QueryPunches(DateTime from, DateTime to, bool? ascending = null, int cap = 5000)
    {
        const int page = 100;
        var watch = Stopwatch.StartNew();
        var find = StartPunchFind(from, to, ascending, out var error);
        if (find == IntPtr.Zero)
        {
            return new PunchQuery([], 0, watch.ElapsedMilliseconds, error, false);
        }

        var rows = new List<Punch>();
        var calls = 0;
        var capped = false;
        try
        {
            while (true)
            {
                if (rows.Count >= cap)
                {
                    capped = true;
                    break;
                }

                var list = PunchPage(page);
                var returned = 0;
                calls++;
                if (NETClient.FindNextRecord(find, page, ref returned, ref list, typeof(NET_RECORDSET_ACCESS_CTL_CARDREC), ListWaitMs) < 0)
                {
                    error = "FindNextRecord failed: " + NETClient.GetLastError();
                    break;
                }

                AppendPunches(rows, list, returned);

                if (returned < page)
                {
                    break;
                }
            }
        }
        finally
        {
            NETClient.FindRecordClose(find);
        }

        return new PunchQuery(rows, calls, watch.ElapsedMilliseconds, error, capped);
    }

    private static List<object> PunchPage(int page)
    {
        var list = new List<object>(page);
        for (var i = 0; i < page; i++)
        {
            list.Add(new NET_RECORDSET_ACCESS_CTL_CARDREC { dwSize = (uint)Marshal.SizeOf<NET_RECORDSET_ACCESS_CTL_CARDREC>() });
        }

        return list;
    }

    private static void AppendPunches(List<Punch> rows, List<object> list, int returned)
    {
        for (var i = 0; i < returned && i < list.Count; i++)
        {
            var x = (NET_RECORDSET_ACCESS_CTL_CARDREC)list[i];
            rows.Add(new Punch(x.nRecNo, string.IsNullOrWhiteSpace(x.szUserID) ? null : x.szUserID.Trim(),
                Raw(x.stuTime), ToDate(x.stuTime), x.bStatus, x.emMethod.ToString(), x.nErrorCode));
        }
    }

    public int? CountPunches(DateTime from, DateTime to)
    {
        var find = StartPunchFind(from, to, null, out _);
        if (find == IntPtr.Zero)
        {
            return null;
        }

        try
        {
            var count = 0;
            return NETClient.QueryRecordCount(find, ref count, ListWaitMs) ? count : null;
        }
        finally
        {
            NETClient.FindRecordClose(find);
        }
    }

    private IntPtr StartPunchFind(DateTime from, DateTime to, bool? ascending, out string? error)
    {
        var condition = new NET_FIND_RECORD_ACCESSCTLCARDREC_CONDITION_EX
        {
            dwSize = (uint)Marshal.SizeOf<NET_FIND_RECORD_ACCESSCTLCARDREC_CONDITION_EX>(),
            bTimeEnable = true,
            stStartTime = NET_TIME.FromDateTime(from),
            stEndTime = NET_TIME.FromDateTime(to),
            stuOrders = new NET_FIND_RECORD_ACCESSCTLCARDREC_ORDER[6]
        };
        for (var i = 0; i < condition.stuOrders.Length; i++)
        {
            condition.stuOrders[i].byReverse = new byte[64];
        }

        if (ascending != null)
        {
            condition.nOrderNum = 1;
            condition.stuOrders[0].emField = EM_RECORD_ACCESSCTLCARDREC_ORDER_FIELD.RECNO;
            condition.stuOrders[0].emOrderType = ascending.Value ? EM_RECORD_ORDER_TYPE.ASCENT : EM_RECORD_ORDER_TYPE.DESCENT;
        }

        var find = IntPtr.Zero;
        var ok = NETClient.FindRecord(Login, EM_NET_RECORD_TYPE.ACCESSCTLCARDREC_EX, condition,
            typeof(NET_FIND_RECORD_ACCESSCTLCARDREC_CONDITION_EX), ref find, ListWaitMs);
        error = ok && find != IntPtr.Zero ? null : "FindRecord failed: " + NETClient.GetLastError();
        return error == null ? find : IntPtr.Zero;
    }

    public static UserRow ToRow(NET_ACCESS_USER_INFO u) =>
        new(u.szUserID.Trim(), string.IsNullOrWhiteSpace(u.szName) ? null : u.szName.Trim(), u.nUserStatus,
            Raw(u.stuValidBeginTime), Raw(u.stuValidEndTime), Raw(u.stuUpdateTime), ToDate(u.stuUpdateTime));

    public static string Raw(NET_TIME t) =>
        t.dwYear == 0 && t.dwMonth == 0 && t.dwDay == 0 && t.dwHour == 0 && t.dwMinute == 0 && t.dwSecond == 0
            ? "(zero)"
            : $"{t.dwYear:0000}-{t.dwMonth:00}-{t.dwDay:00} {t.dwHour:00}:{t.dwMinute:00}:{t.dwSecond:00}";

    public static DateTime? ToDate(NET_TIME t)
    {
        if (t.dwYear < 2000 || t.dwMonth is 0 or > 12 || t.dwDay is 0 or > 31)
        {
            return null;
        }

        try
        {
            return new DateTime((int)t.dwYear, (int)t.dwMonth, (int)t.dwDay, (int)t.dwHour, (int)t.dwMinute, (int)t.dwSecond, DateTimeKind.Unspecified);
        }
        catch (ArgumentOutOfRangeException)
        {
            return null;
        }
    }

    private static IntPtr Alloc<T>(T value) where T : struct
    {
        var ptr = Marshal.AllocHGlobal(Marshal.SizeOf<T>());
        Marshal.StructureToPtr(value, ptr, false);
        return ptr;
    }

    private static void FreeAll(params IntPtr[] pointers)
    {
        foreach (var p in pointers)
        {
            if (p != IntPtr.Zero)
            {
                Marshal.FreeHGlobal(p);
            }
        }
    }

    private static void Note(IntPtr login, string type, string detail)
    {
        if (ByLogin.TryGetValue(login, out var s))
        {
            s.Alarms.Enqueue(new AlarmSeen(DateTime.UtcNow, type, detail));
        }
    }

    private static bool OnAlarm(int command, IntPtr login, IntPtr buf, uint len, IntPtr ip, int port, IntPtr user)
    {
        try
        {
            var type = (EM_ALARM_TYPE)command;
            var name = Enum.IsDefined(type) ? type.ToString() : $"0x{command:X}";
            var detail = $"{len} bytes";
            if (buf != IntPtr.Zero)
            {
                detail += Decode(type, buf, len);
            }

            Note(login, name, detail);
        }
        catch
        {
            // Native callback: never throw.
        }

        return true;
    }

    private static string Decode(EM_ALARM_TYPE type, IntPtr buf, uint len)
    {
        switch (type)
        {
            case EM_ALARM_TYPE.ALARM_ACCESS_CTL_EVENT when len >= Marshal.SizeOf<NET_ALARM_ACCESS_CTL_EVENT_INFO>():
                var access = Marshal.PtrToStructure<NET_ALARM_ACCESS_CTL_EVENT_INFO>(buf);
                return $", user={access.szUserID} ok={access.bStatus} method={access.emOpenMethod} rec={access.nPunchingRecNo} "
                       + $"err=0x{access.nErrorCode:X} time={Raw(access.stuTime)}";
            case EM_ALARM_TYPE.FACEINFO_COLLECT when len >= Marshal.SizeOf<NET_ALARM_FACEINFO_COLLECT_INFO>():
                var collect = Marshal.PtrToStructure<NET_ALARM_FACEINFO_COLLECT_INFO>(buf);
                return $", user={collect.szUserID}";
            case EM_ALARM_TYPE.ALARM_USER_MODIFIED when len >= Marshal.SizeOf<NET_ALARM_USER_MODIFIED_INFO>():
                var modified = Marshal.PtrToStructure<NET_ALARM_USER_MODIFIED_INFO>(buf);
                return $", user={modified.szUser} op={modified.emOpType} userType={modified.emUserType}";
            default:
                return RawHead(buf, len);
        }
    }

    private static string RawHead(IntPtr buf, uint len)
    {
        var n = (int)Math.Min(len, 64u);
        if (n == 0)
        {
            return "";
        }

        var bytes = new byte[n];
        Marshal.Copy(buf, bytes, 0, n);
        return ", raw " + Convert.ToHexString(bytes) + (len > 64 ? "..." : "");
    }

    public void Dispose()
    {
        if (Login == IntPtr.Zero)
        {
            return;
        }

        if (Listening)
        {
            NETClient.StopListen(Login);
        }

        NETClient.Logout(Login);
        ByLogin.TryRemove(Login, out _);
        Login = IntPtr.Zero;
    }
}
