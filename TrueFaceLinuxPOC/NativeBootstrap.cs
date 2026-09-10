using System.Runtime.InteropServices;

namespace TrueFaceLinuxPOC;

/// <summary>
/// Makes libdhnetsdk.so and its runtime-dlopened companions resolvable without installing them in /usr/lib.
/// </summary>
public static class NativeBootstrap
{
    public const string PrimaryLibrary = "libdhnetsdk.so";

    public static string ResolveNativeDirectory(string? configured)
    {
        if (!string.IsNullOrWhiteSpace(configured))
        {
            return Path.GetFullPath(configured);
        }

        var baseDir = AppContext.BaseDirectory;
        var candidates = new[]
        {
            Path.Combine(baseDir, "Native"),
            Path.Combine(baseDir, "..", "..", "..", "Native"),
            Path.Combine(Environment.CurrentDirectory, "Native")
        };

        foreach (var candidate in candidates)
        {
            var full = Path.GetFullPath(candidate);
            if (File.Exists(Path.Combine(full, PrimaryLibrary)))
            {
                return full;
            }
        }

        return Path.GetFullPath(Path.Combine(baseDir, "Native"));
    }

    public static string PrimaryLibraryPath(string nativeDir) => Path.Combine(nativeDir, PrimaryLibrary);

    /// <summary>
    /// Prepends <paramref name="nativeDir"/> to LD_LIBRARY_PATH and registers a DllImport resolver.
    /// Must run before the first P/Invoke into NetSDKCS.
    /// </summary>
    public static void Configure(string nativeDir)
    {
        var existing = Environment.GetEnvironmentVariable("LD_LIBRARY_PATH");
        var combined = string.IsNullOrEmpty(existing) ? nativeDir : nativeDir + Path.PathSeparator + existing;
        Environment.SetEnvironmentVariable("LD_LIBRARY_PATH", combined);

        NativeLibrary.SetDllImportResolver(typeof(NetSDKCS.NETClient).Assembly, (name, _, _) =>
        {
            var fileName = name.EndsWith(".so", StringComparison.OrdinalIgnoreCase) ? name : "lib" + name + ".so";
            var path = Path.Combine(nativeDir, fileName);
            if (File.Exists(path))
            {
                return NativeLibrary.Load(path);
            }

            return IntPtr.Zero;
        });
    }

    public static IntPtr TryLoadPrimary(string nativeDir, out string? error)
    {
        var path = PrimaryLibraryPath(nativeDir);
        if (!File.Exists(path))
        {
            error = $"Native library not found: {path}";
            return IntPtr.Zero;
        }

        try
        {
            var handle = NativeLibrary.Load(path);
            error = null;
            return handle;
        }
        catch (Exception ex)
        {
            error = ex.Message;
            return IntPtr.Zero;
        }
    }

    public static IReadOnlyList<string> CompanionLibrariesPresent(string nativeDir)
    {
        string[] expected =
        [
            "libdhconfigsdk.so",
            "libavnetsdk.so",
            "libInfra.so",
            "libNetFramework.so",
            "libStream.so",
            "libStreamSvr.so"
        ];
        return expected.Where(name => File.Exists(Path.Combine(nativeDir, name))).ToArray();
    }
}
