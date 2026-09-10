using System.Runtime.InteropServices;
using NetSDKCS;

namespace TrueFaceLinuxPOC;

internal static class Program
{
    private static DeviceSession? s_session;
    private static int s_exitCode = 2;

    public static int Main(string[] args)
    {
        Console.WriteLine("[INFO] Starting TrueFace Linux SDK POC");
        Console.WriteLine($"[INFO] Runtime: {RuntimeInformation.FrameworkDescription}");
        Console.WriteLine($"[INFO] OS: {RuntimeInformation.OSDescription} ({RuntimeInformation.OSArchitecture})");
        Console.WriteLine("[INFO] Native SDK: libdhnetsdk.so");

        if (HasFlag(args, "--help") || HasFlag(args, "-h"))
        {
            Console.WriteLine(PocOptions.Usage());
            return 0;
        }

        PocOptions options;
        try
        {
            options = PocOptions.Parse(args, EnvironmentVars());
        }
        catch (PocConfigException ex)
        {
            Console.WriteLine($"[ERROR] {ex.Message}");
            Console.WriteLine();
            Console.WriteLine(PocOptions.Usage());
            return 2;
        }

        var nativeDir = NativeBootstrap.ResolveNativeDirectory(options.NativeDir);
        Console.WriteLine($"[INFO] Native directory: {nativeDir}");

        if (!File.Exists(NativeBootstrap.PrimaryLibraryPath(nativeDir)))
        {
            Console.WriteLine("[ERROR] Native loading: libdhnetsdk.so not found");
            Console.WriteLine($"[ERROR] Expected: {NativeBootstrap.PrimaryLibraryPath(nativeDir)}");
            Console.WriteLine("[ERROR] Copy it from dahua-sdk-master/libs/lin64/ (see README).");
            return 3;
        }

        NativeBootstrap.Configure(nativeDir);

        var handle = NativeBootstrap.TryLoadPrimary(nativeDir, out var loadError);
        if (handle == IntPtr.Zero)
        {
            Console.WriteLine("[ERROR] Native loading: failed to load libdhnetsdk.so");
            Console.WriteLine($"[ERROR] {loadError}");
            return 3;
        }

        Console.WriteLine("[OK] Native library loaded");
        var companions = NativeBootstrap.CompanionLibrariesPresent(nativeDir);
        Console.WriteLine($"[INFO] Companion libraries present: {string.Join(", ", companions)}");

        if (options.SelfCheckOnly)
        {
            Console.WriteLine("[INFO] Initializing SDK (self-check, no device)...");
            using var check = new DeviceSession();
            if (!check.Initialize())
            {
                PrintSdkError("SDK initialization failed");
                NativeLibrary.Free(handle);
                return 4;
            }

            Console.WriteLine("[OK] SDK initialized");
            check.Cleanup();
            Console.WriteLine("[OK] SDK cleanup completed");
            Console.WriteLine("[OK] Self-check complete (no device contact)");
            NativeLibrary.Free(handle);
            return 0;
        }

        if (!options.HasConnectionTarget)
        {
            Console.WriteLine("[ERROR] Missing --ip and/or --username (or TRUEFACE_IP / TRUEFACE_USERNAME).");
            Console.WriteLine("[INFO] Use --self-check to verify native loading without a device.");
            Console.WriteLine();
            Console.WriteLine(PocOptions.Usage());
            NativeLibrary.Free(handle);
            return 2;
        }

        var password = options.Password;
        if (string.IsNullOrEmpty(password))
        {
            password = ReadPassword();
        }

        Console.WriteLine($"[INFO] Device: {options.Ip}:{options.Port}");
        Console.WriteLine($"[INFO] Username: {options.Username}");

        Console.CancelKeyPress += OnCancel;

        using var session = new DeviceSession();
        s_session = session;

        try
        {
            Console.WriteLine("[INFO] Initializing SDK...");
            if (!session.Initialize())
            {
                PrintSdkError("SDK initialization failed");
                s_exitCode = 4;
                return s_exitCode;
            }

            Console.WriteLine("[OK] SDK initialized");
            Console.WriteLine("[INFO] Logging in via NETClient.Login → CLIENT_LoginEx2 (TCP)...");

            NET_DEVICEINFO_Ex info;
            try
            {
                info = session.Login(options.Ip, options.Port, options.Username, password);
            }
            catch (DllNotFoundException ex)
            {
                Console.WriteLine("[ERROR] Native loading: dependent .so not found during login");
                Console.WriteLine($"[ERROR] {ex.Message}");
                s_exitCode = 3;
                return s_exitCode;
            }
            catch (Exception ex)
            {
                Console.WriteLine("[ERROR] Unexpected exception during login");
                Console.WriteLine($"[ERROR] {ex.GetType().Name}: {ex.Message}");
                s_exitCode = 5;
                return s_exitCode;
            }

            if (!session.IsLoggedIn)
            {
                PrintSdkError("Login failed");
                s_exitCode = 6;
                return s_exitCode;
            }

            Console.WriteLine("[OK] Login successful");
            PrintDeviceInfo(options, info);

            Console.WriteLine("[INFO] Logging out...");
            if (!session.Logout())
            {
                PrintSdkError("Logout failed");
                s_exitCode = 7;
                return s_exitCode;
            }

            Console.WriteLine("[OK] Logout successful");
            session.Cleanup();
            Console.WriteLine("[OK] SDK cleanup completed");
            s_exitCode = 0;
            return 0;
        }
        catch (DllNotFoundException ex)
        {
            Console.WriteLine("[ERROR] Native loading problem");
            Console.WriteLine($"[ERROR] {ex.Message}");
            s_exitCode = 3;
            return s_exitCode;
        }
        catch (Exception ex)
        {
            Console.WriteLine("[ERROR] Unexpected exception");
            Console.WriteLine($"[ERROR] {ex.GetType().Name}: {ex.Message}");
            s_exitCode = 5;
            return s_exitCode;
        }
        finally
        {
            s_session = null;
        }
    }

    private static void PrintDeviceInfo(PocOptions options, NET_DEVICEINFO_Ex info)
    {
        Console.WriteLine($"[INFO] Device IP: {options.Ip}");
        Console.WriteLine($"[INFO] Device port: {options.Port}");
        Console.WriteLine($"[INFO] Serial Number: {NullIfEmpty(info.sSerialNumber)}");
        Console.WriteLine($"[INFO] Device Type: {info.nDVRType}");
        Console.WriteLine($"[INFO] Channel Count: {info.nChanNum}");
        Console.WriteLine($"[INFO] Alarm input count: {info.nAlarmInPortNum}");
        Console.WriteLine($"[INFO] Alarm output count: {info.nAlarmOutPortNum}");
        Console.WriteLine($"[INFO] Disk count: {info.nDiskNum}");
    }

    private static string NullIfEmpty(string? value) =>
        string.IsNullOrWhiteSpace(value) ? "(empty / not reported)" : value.Trim();

    private static void PrintSdkError(string headline)
    {
        Console.WriteLine($"[ERROR] {headline}");
        Console.WriteLine($"[ERROR] SDK error code: {DeviceSession.LastErrorCode()} (0x{DeviceSession.LastErrorCode():X})");
        Console.WriteLine($"[ERROR] SDK error description: {DeviceSession.LastErrorDescription()}");
    }

    private static void OnCancel(object? sender, ConsoleCancelEventArgs e)
    {
        e.Cancel = true;
        Console.WriteLine();
        Console.WriteLine("[INFO] Interrupted — logging out and cleaning up...");
        try
        {
            s_session?.Dispose();
        }
        catch
        {
            // best-effort
        }

        Environment.Exit(130);
    }

    private static string ReadPassword()
    {
        if (Console.IsInputRedirected)
        {
            var line = Console.In.ReadLine() ?? "";
            return line.TrimEnd('\r', '\n');
        }

        Console.Write("Password: ");
        var buffer = new System.Text.StringBuilder();
        while (true)
        {
            var key = Console.ReadKey(intercept: true);
            if (key.Key == ConsoleKey.Enter)
            {
                Console.WriteLine();
                break;
            }

            if (key.Key == ConsoleKey.Backspace)
            {
                if (buffer.Length > 0)
                {
                    buffer.Length--;
                }

                continue;
            }

            if (!char.IsControl(key.KeyChar))
            {
                buffer.Append(key.KeyChar);
            }
        }

        return buffer.ToString();
    }

    private static bool HasFlag(string[] args, string name) =>
        args.Any(a => string.Equals(a, name, StringComparison.OrdinalIgnoreCase));

    private static Dictionary<string, string?> EnvironmentVars()
    {
        var map = new Dictionary<string, string?>(StringComparer.Ordinal);
        foreach (System.Collections.DictionaryEntry entry in Environment.GetEnvironmentVariables())
        {
            map[entry.Key.ToString() ?? ""] = entry.Value?.ToString();
        }

        return map;
    }
}
