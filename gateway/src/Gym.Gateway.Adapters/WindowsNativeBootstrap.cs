using System.Runtime.InteropServices;
using NetSDKCS;

namespace Gym.Gateway.Adapters;

/// <summary>
/// Resolves dhnetsdk.dll (and companions) from an application-local folder on Windows.
/// Must run before the first P/Invoke into NetSDKCS.
/// </summary>
public static class WindowsNativeBootstrap
{
    public const string PrimaryLibrary = "dhnetsdk.dll";

    public static string ResolveNativeDirectory(string? configured)
    {
        if (!string.IsNullOrWhiteSpace(configured))
        {
            return Path.GetFullPath(configured);
        }

        var baseDir = AppContext.BaseDirectory;
        var candidates = new[]
        {
            Path.Combine(baseDir, "native", "win-x64"),
            Path.Combine(baseDir, "Native"),
            Path.Combine(Environment.CurrentDirectory, "native", "win-x64"),
            Path.Combine(Environment.CurrentDirectory, "..", "..", "..", "..", "native", "win-x64")
        };

        foreach (var candidate in candidates)
        {
            var full = Path.GetFullPath(candidate);
            if (File.Exists(Path.Combine(full, PrimaryLibrary)))
            {
                return full;
            }
        }

        return Path.GetFullPath(Path.Combine(baseDir, "native", "win-x64"));
    }

    public static bool TryConfigure(string nativeDir, out string error)
    {
        var path = Path.Combine(nativeDir, PrimaryLibrary);
        if (!File.Exists(path))
        {
            error = $"Native library not found: {path}";
            return false;
        }

        try
        {
            NativeLibrary.SetDllImportResolver(typeof(NETClient).Assembly, (name, _, _) =>
            {
                var fileName = name.EndsWith(".dll", StringComparison.OrdinalIgnoreCase) ? name : name + ".dll";
                var candidate = Path.Combine(nativeDir, fileName);
                if (File.Exists(candidate))
                {
                    return NativeLibrary.Load(candidate);
                }

                return IntPtr.Zero;
            });
            error = "";
            return true;
        }
        catch (Exception ex)
        {
            error = ex.Message;
            return false;
        }
    }
}
