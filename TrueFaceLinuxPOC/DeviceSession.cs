using System.Runtime.InteropServices;
using NetSDKCS;

namespace TrueFaceLinuxPOC;

/// <summary>
/// Strictly: Init → Login (CLIENT_LoginEx2) → print NET_DEVICEINFO_Ex → Logout → Cleanup.
/// Delegates are stored in static fields so the GC cannot collect them while native code holds them.
/// </summary>
public sealed class DeviceSession : IDisposable
{
    // Kept alive for the process lifetime of this session (native callback requirement).
    private static fDisConnectCallBack? s_disconnect;
    private static fHaveReConnectCallBack? s_reconnect;

    private IntPtr _loginId = IntPtr.Zero;
    private bool _initialized;
    private bool _disposed;

    public IntPtr LoginId => _loginId;
    public bool IsLoggedIn => _loginId != IntPtr.Zero;

    public bool Initialize()
    {
        s_disconnect = OnDisconnect;
        s_reconnect = OnReconnect;

        NETClient.SetThrowErrorMessage(false);
        var ok = NETClient.InitWithDefaultSetting(s_disconnect, s_reconnect, IntPtr.Zero, null);
        _initialized = ok;
        return ok;
    }

    public NET_DEVICEINFO_Ex Login(string ip, ushort port, string username, string password)
    {
        var info = new NET_DEVICEINFO_Ex();
        _loginId = NETClient.Login(ip, port, username, password, EM_LOGIN_SPAC_CAP_TYPE.TCP, IntPtr.Zero, ref info);
        return info;
    }

    public bool Logout()
    {
        if (_loginId == IntPtr.Zero)
        {
            return true;
        }

        var ok = NETClient.Logout(_loginId);
        _loginId = IntPtr.Zero;
        return ok;
    }

    public void Cleanup()
    {
        if (_initialized)
        {
            NETClient.Cleanup();
            _initialized = false;
        }
    }

    public static int LastErrorCode() => OriginalSDK.CLIENT_GetLastError();

    public static string LastErrorDescription()
    {
        var text = NETClient.GetLastError();
        return string.IsNullOrWhiteSpace(text) ? "(no SDK error description)" : text;
    }

    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }

        try
        {
            Logout();
        }
        catch
        {
            // best-effort
        }

        try
        {
            Cleanup();
        }
        catch
        {
            // best-effort
        }

        _disposed = true;
        GC.SuppressFinalize(this);
    }

    private static void OnDisconnect(IntPtr loginId, IntPtr pchDvrIp, int nDvrPort, IntPtr dwUser)
    {
        Console.WriteLine($"[WARN] Device disconnected ip={PtrToString(pchDvrIp)} port={nDvrPort} loginId=0x{loginId.ToInt64():X}");
    }

    private static void OnReconnect(IntPtr loginId, IntPtr pchDvrIp, int nDvrPort, IntPtr dwUser)
    {
        Console.WriteLine($"[INFO] Device reconnected ip={PtrToString(pchDvrIp)} port={nDvrPort} loginId=0x{loginId.ToInt64():X}");
    }

    private static string PtrToString(IntPtr ptr) =>
        ptr == IntPtr.Zero ? "" : Marshal.PtrToStringAnsi(ptr) ?? "";
}
