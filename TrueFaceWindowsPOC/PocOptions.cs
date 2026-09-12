namespace TrueFaceWindowsPOC;

public sealed class PocConfigException : Exception
{
    public PocConfigException(string message) : base(message)
    {
    }
}

public sealed class PocOptions
{
    public string Ip { get; init; } = "";
    public ushort Port { get; init; } = 37777;
    public string Username { get; init; } = "";
    public string Password { get; init; } = "";
    public string? NativeDir { get; init; }
    public string? FaceImagePath { get; init; }
    public bool SkipDoor { get; init; }
    public bool SkipFaceUpload { get; init; }
    public bool SelfCheckOnly { get; init; }
    public bool UseMockAdapter { get; init; }
    public int DoorWaitSeconds { get; init; } = 90;

    public bool HasConnectionTarget =>
        !string.IsNullOrWhiteSpace(Ip) && !string.IsNullOrWhiteSpace(Username);

    public static string Usage() =>
        """
        TrueFace Windows gym-visit POC (no Spring / MySQL).

        Required (or env TRUEFACE_IP / TRUEFACE_USERNAME / TRUEFACE_PASSWORD):
          --ip <addr> --username <device-admin> [--password <pwd>] [--port 37777]

        Optional:
          --face-image <path.jpg>   Probe OperateAccessFaceService INSERT (never claims success)
          --skip-face-upload        Skip remote face probe
          --skip-door               Skip interactive door allow/deny walks
          --door-wait-seconds 90    ACCESS event wait timeout
          --native-dir <path>       Folder with dhnetsdk.dll
          --mock                    Use MockDeviceAdapter (no hardware; Linux CI)
          --self-check              Load native DLL path only (Windows) / mock connect (any OS)
          --help

        Never commit device passwords. Prefer env TRUEFACE_PASSWORD over --password.
        """;

    public static PocOptions Parse(string[] args, IDictionary<string, string?> env)
    {
        string? ip = env.TryGetValue("TRUEFACE_IP", out var eip) ? eip : null;
        string? portRaw = env.TryGetValue("TRUEFACE_PORT", out var eport) ? eport : null;
        string? username = env.TryGetValue("TRUEFACE_USERNAME", out var eu) ? eu : null;
        string? password = env.TryGetValue("TRUEFACE_PASSWORD", out var ep) ? ep : null;
        string? nativeDir = env.TryGetValue("TRUEFACE_NATIVE_DIR", out var en) ? en : null;
        string? faceImage = null;
        var skipDoor = false;
        var skipFace = false;
        var selfCheck = false;
        var mock = false;
        var doorWait = 90;

        for (var i = 0; i < args.Length; i++)
        {
            var a = args[i];
            string Next(string name)
            {
                if (i + 1 >= args.Length)
                {
                    throw new PocConfigException($"{name} requires a value");
                }

                return args[++i];
            }

            switch (a.ToLowerInvariant())
            {
                case "--ip":
                    ip = Next("--ip");
                    break;
                case "--port":
                    portRaw = Next("--port");
                    break;
                case "--username":
                    username = Next("--username");
                    break;
                case "--password":
                    password = Next("--password");
                    break;
                case "--native-dir":
                    nativeDir = Next("--native-dir");
                    break;
                case "--face-image":
                    faceImage = Next("--face-image");
                    break;
                case "--door-wait-seconds":
                    if (!int.TryParse(Next("--door-wait-seconds"), out doorWait) || doorWait < 5)
                    {
                        throw new PocConfigException("Invalid --door-wait-seconds (min 5)");
                    }

                    break;
                case "--skip-door":
                    skipDoor = true;
                    break;
                case "--skip-face-upload":
                    skipFace = true;
                    break;
                case "--self-check":
                    selfCheck = true;
                    break;
                case "--mock":
                    mock = true;
                    break;
                case "--help":
                case "-h":
                    throw new PocConfigException("HELP");
                default:
                    throw new PocConfigException($"Unknown argument: {a}");
            }
        }

        ushort port = 37777;
        if (!string.IsNullOrWhiteSpace(portRaw))
        {
            if (!ushort.TryParse(portRaw, out port) || port == 0)
            {
                throw new PocConfigException($"Invalid port: {portRaw}");
            }
        }

        return new PocOptions
        {
            Ip = ip?.Trim() ?? "",
            Port = port,
            Username = username?.Trim() ?? "",
            Password = password ?? "",
            NativeDir = string.IsNullOrWhiteSpace(nativeDir) ? null : nativeDir.Trim(),
            FaceImagePath = faceImage,
            SkipDoor = skipDoor,
            SkipFaceUpload = skipFace,
            SelfCheckOnly = selfCheck,
            UseMockAdapter = mock,
            DoorWaitSeconds = doorWait
        };
    }

    public override string ToString() =>
        $"ip={Ip} port={Port} username={Username} password={(string.IsNullOrEmpty(Password) ? "(empty)" : "***")} " +
        $"faceImage={(FaceImagePath ?? "(none)")} skipDoor={SkipDoor} skipFace={SkipFaceUpload} mock={UseMockAdapter}";
}
