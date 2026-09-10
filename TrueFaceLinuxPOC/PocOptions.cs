namespace TrueFaceLinuxPOC;

/// <summary>
/// Runtime connection settings. Password is never included in <see cref="ToString"/>.
/// </summary>
public sealed class PocOptions
{
    public string Ip { get; init; } = "";
    public ushort Port { get; init; } = 37777;
    public string Username { get; init; } = "";
    public string Password { get; init; } = "";
    public string? NativeDir { get; init; }
    public bool SelfCheckOnly { get; init; }

    public bool HasConnectionTarget =>
        !string.IsNullOrWhiteSpace(Ip) && !string.IsNullOrWhiteSpace(Username);

    public override string ToString() =>
        $"ip={Ip} port={Port} username={Username} password=*** nativeDir={NativeDir ?? "(default)"}";

    public static PocOptions Parse(string[] args, IDictionary<string, string?> env)
    {
        var ip = First(GetArg(args, "--ip"), env, "TRUEFACE_IP");
        var portRaw = First(GetArg(args, "--port"), env, "TRUEFACE_PORT") ?? "37777";
        var username = First(GetArg(args, "--username"), env, "TRUEFACE_USERNAME");
        var password = First(GetArg(args, "--password"), env, "TRUEFACE_PASSWORD") ?? "";
        var nativeDir = First(GetArg(args, "--native-dir"), env, "TRUEFACE_NATIVE_DIR");
        var selfCheck = HasFlag(args, "--self-check");

        if (!ushort.TryParse(portRaw, out var port) || port == 0)
        {
            throw new PocConfigException($"Invalid port '{portRaw}'. Expected 1-65535.");
        }

        return new PocOptions
        {
            Ip = ip?.Trim() ?? "",
            Port = port,
            Username = username?.Trim() ?? "",
            Password = password,
            NativeDir = string.IsNullOrWhiteSpace(nativeDir) ? null : nativeDir,
            SelfCheckOnly = selfCheck
        };
    }

    public static string Usage() =>
        """
        TrueFace Linux SDK compatibility POC (.NET 10 / libdhnetsdk.so)

        Usage:
          TrueFaceLinuxPOC --ip <DEVICE_IP> --username <USER> [--port 37777]
          TrueFaceLinuxPOC --self-check

        Options:
          --ip            Device IPv4/IPv6 address (or TRUEFACE_IP)
          --port          SDK TCP port, default 37777 (or TRUEFACE_PORT)
          --username      Device username (or TRUEFACE_USERNAME)
          --password      Device password (or TRUEFACE_PASSWORD). If omitted, prompted without echo.
          --native-dir    Directory containing libdhnetsdk.so (default: ./Native next to the app)
          --self-check    Load libdhnetsdk.so, run CLIENT_InitEx, cleanup; do not contact a device

        Do not pass a password on a shared command line if it will be recorded in shell history.
        """;

    private static string? GetArg(string[] args, string name)
    {
        for (var i = 0; i < args.Length; i++)
        {
            if (!string.Equals(args[i], name, StringComparison.OrdinalIgnoreCase))
            {
                continue;
            }

            if (i + 1 >= args.Length || args[i + 1].StartsWith("--", StringComparison.Ordinal))
            {
                throw new PocConfigException($"Option {name} requires a value.");
            }

            return args[i + 1];
        }

        return null;
    }

    private static bool HasFlag(string[] args, string name) =>
        args.Any(a => string.Equals(a, name, StringComparison.OrdinalIgnoreCase));

    private static string? First(string? arg, IDictionary<string, string?> env, string envName)
    {
        if (!string.IsNullOrWhiteSpace(arg))
        {
            return arg;
        }

        return env.TryGetValue(envName, out var value) ? value : null;
    }
}

public sealed class PocConfigException : Exception
{
    public PocConfigException(string message) : base(message)
    {
    }
}
