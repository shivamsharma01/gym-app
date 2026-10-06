using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace Gym.Gateway.SdkProbe;

internal sealed record DeviceTarget(string DeviceId, string Ip, ushort Port, string Username, string Password);

internal sealed class ProbeOptions
{
    public List<string> Readers { get; } = [];
    public ushort Port { get; set; } = 37777;
    public string Username { get; set; } = "admin";
    public string? Password { get; set; }
    public string? ConfigPath { get; set; }
    public string? DeviceFilter { get; set; }
    public string? NativeDir { get; set; }
    public string? OutDir { get; set; }
    public int Samples { get; set; } = 20;
    public bool Write { get; set; }
    public bool Fields { get; set; }
    public string? WriteDevice { get; set; }
    public string TestUser { get; set; } = "990001";
    public string? PhotoPath { get; set; }
    public bool Interactive { get; set; }
    public bool SkipRead { get; set; }
    public int StepDelaySeconds { get; set; } = 3;
    public bool AllowGatewayRunning { get; set; }
    public bool PauseAtEnd { get; set; }
    public bool Help { get; set; }
    public bool Gates { get; set; }
    public string? Photo2Path { get; set; }
    public bool NoWalks { get; set; }
    public bool SpareReader { get; set; }
    public int LogCap { get; set; } = 50000;

    public bool WritesToReader => Write || Fields || Gates;

    public const string Usage = """
        Gym.Gateway.SdkProbe: measures what the TrueFace readers can tell us cheaply.

        Readers and credentials (pick one):
          --reader <ip>[:port][,username,password]   repeat for each reader
              e.g. --reader 192.168.1.201 --reader 192.168.1.202 --password Secret123
              Readers without their own username/password use --username (default admin)
              and --password (or env TRUEFACE_PASSWORD; asked for if missing).
          (nothing)  uses C:\ProgramData\GymGateway\config.json if the gateway is installed,
                     otherwise asks for the reader IPs and password.

        Read-only (default, safe):
          Lists users with their last-modified time, lists photo MD5s, downloads a sample
          of photos to compare checksums and photo times, compares the readers.

        Writes (create and delete ONE test user on one reader):
          --write        step-by-step test: update times, photo MD5s, events
          --fields       every user field: write from here, read back; with --interactive
                         also edit the test user on the reader screen and see what arrives
          --interactive  ask for edits on the reader screen
          --write-device <ip or deviceId>   reader for the writes (default: the first)
          --test-user <id>                  test user ID (default 990001; must be unused)
          --photo <file.jpg>                photo for the test user (max 120 KB)
          --skip-read                       skip the read-only part

        Sync gates (third POC: checks P1-P12 of the sync architecture; read GATES.md first):
          --gates          run every gate check on one reader with throwaway SYNCPOC users,
                           ask for door walks and screen edits, and give each check
                           OBSERVED / NOT OBSERVED / UNKNOWN (output in .\sync-gates-<time>)
          --photo <file>   face of the person doing the door walks (max 120 KB); without it
                           the probe asks you to enrol your face on the reader screen
          --photo2 <file>  a second, different photo for the face-replace step
          --no-walks       no door walks; every check that needs one stays UNKNOWN
          --spare-reader   ONLY on a spare reader: also ask for a factory reset (P12) and use a
                           full attendance log (P6). Never on a gym reader.
          --log-cap <n>    most attendance records read for P8 (default 50000)

        Other options:
          --device <deviceId>      only this reader from the gateway config
          --config <path>          other gateway config.json
          --samples <n>            photos to download per reader (default 20)
          --step-delay <seconds>   wait after each write step (default 3)
          --out <dir>              output folder (default .\sdk-probe-<time>)
          --native-dir <dir>       folder with dhnetsdk.dll (default .\native\win-x64)
          --allow-gateway-running  run the read-only part while the Gym Gateway service runs

        Example (everything, two readers):
          Gym.Gateway.SdkProbe.exe --reader 192.168.1.201 --reader 192.168.1.202 --password Secret123 --write --fields --interactive --photo C:\face.jpg

        Send back the whole output folder.
        """;

    public static ProbeOptions Parse(string[] args)
    {
        var o = new ProbeOptions { PauseAtEnd = args.Length == 0 };
        for (var i = 0; i < args.Length; i++)
        {
            var a = args[i];
            string Next() => i + 1 < args.Length ? args[++i] : throw new ArgumentException($"{a} needs a value");
            switch (a)
            {
                case "--reader" or "--ip": o.Readers.Add(Next()); break;
                case "--port": o.Port = ushort.Parse(Next()); break;
                case "--username": o.Username = Next(); break;
                case "--password": o.Password = Next(); break;
                case "--config": o.ConfigPath = Next(); break;
                case "--device": o.DeviceFilter = Next(); break;
                case "--native-dir": o.NativeDir = Next(); break;
                case "--out": o.OutDir = Next(); break;
                case "--samples": o.Samples = Math.Clamp(int.Parse(Next()), 1, 500); break;
                case "--write": o.Write = true; break;
                case "--fields": o.Fields = true; break;
                case "--write-device": o.WriteDevice = Next(); break;
                case "--test-user": o.TestUser = Next(); break;
                case "--photo": o.PhotoPath = Next(); break;
                case "--interactive": o.Interactive = true; break;
                case "--skip-read": o.SkipRead = true; break;
                case "--step-delay": o.StepDelaySeconds = Math.Clamp(int.Parse(Next()), 1, 60); break;
                case "--allow-gateway-running": o.AllowGatewayRunning = true; break;
                case "--pause": o.PauseAtEnd = true; break;
                case "--gates": o.Gates = true; break;
                case "--photo2": o.Photo2Path = Next(); break;
                case "--no-walks": o.NoWalks = true; break;
                case "--spare-reader": o.SpareReader = true; break;
                case "--log-cap": o.LogCap = Math.Clamp(int.Parse(Next()), 100, 1_000_000); break;
                case "--self-test": break;
                case "-h" or "--help" or "/?": o.Help = true; break;
                default: throw new ArgumentException("Unknown option " + a);
            }
        }

        return o;
    }
}

internal static class ProbeConfig
{
    private static readonly byte[] Entropy = Encoding.UTF8.GetBytes("GymGateway.v1");

    public static List<DeviceTarget> Load(ProbeOptions o, Report r)
    {
        if (o.Readers.Count > 0)
        {
            return FromArguments(o, o.Readers);
        }

        var path = o.ConfigPath ?? Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "GymGateway", "config.json");
        if (File.Exists(path))
        {
            r.Line($"Gateway config: {path}");
            return FromGatewayConfig(o, path);
        }

        r.Line("No gateway config and no --reader given; asking.");
        Console.Write("Reader IP addresses, separated by commas (e.g. 192.168.1.201,192.168.1.202): ");
        var ips = (Console.ReadLine() ?? "").Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries).ToList();
        Console.Write($"Reader username [{o.Username}]: ");
        var user = Console.ReadLine()?.Trim();
        if (!string.IsNullOrEmpty(user))
        {
            o.Username = user;
        }

        return FromArguments(o, ips);
    }

    private static List<DeviceTarget> FromArguments(ProbeOptions o, List<string> readers)
    {
        var targets = new List<DeviceTarget>();
        foreach (var spec in readers)
        {
            var parts = spec.Split(',', StringSplitOptions.TrimEntries);
            var host = parts[0];
            var port = o.Port;
            var colon = host.LastIndexOf(':');
            if (colon > 0)
            {
                port = ushort.Parse(host[(colon + 1)..]);
                host = host[..colon];
            }

            var username = parts.Length > 1 && parts[1].Length > 0 ? parts[1] : o.Username;
            var password = parts.Length > 2 ? parts[2] : SharedPassword(o);
            targets.Add(new DeviceTarget(host, host, port, username, password));
        }

        return targets;
    }

    private static string SharedPassword(ProbeOptions o)
    {
        o.Password ??= Environment.GetEnvironmentVariable("TRUEFACE_PASSWORD") ?? ReadSecret($"Password for reader user '{o.Username}': ");
        return o.Password;
    }

    private static string ReadSecret(string prompt)
    {
        Console.Write(prompt);
        if (Console.IsInputRedirected)
        {
            return Console.ReadLine() ?? "";
        }

        var sb = new StringBuilder();
        while (true)
        {
            var key = Console.ReadKey(intercept: true);
            if (key.Key == ConsoleKey.Enter)
            {
                Console.WriteLine();
                return sb.ToString();
            }

            if (key.Key == ConsoleKey.Backspace)
            {
                if (sb.Length > 0)
                {
                    sb.Length--;
                }
            }
            else if (!char.IsControl(key.KeyChar))
            {
                sb.Append(key.KeyChar);
            }
        }
    }

    private static List<DeviceTarget> FromGatewayConfig(ProbeOptions o, string path)
    {
        using var doc = JsonDocument.Parse(File.ReadAllText(path));
        var targets = new List<DeviceTarget>();
        if (!doc.RootElement.TryGetProperty("devices", out var devices))
        {
            return targets;
        }

        foreach (var d in devices.EnumerateArray())
        {
            var id = Str(d, "deviceId");
            if (o.DeviceFilter != null && !string.Equals(id, o.DeviceFilter, StringComparison.OrdinalIgnoreCase))
            {
                continue;
            }

            var port = d.TryGetProperty("port", out var p) && p.TryGetUInt16(out var pv) ? pv : (ushort)37777;
            var password = o.Password ?? Unprotect(Str(d, "passwordProtected"));
            targets.Add(new DeviceTarget(id, Str(d, "ip"), port, Str(d, "username", "admin"), password));
        }

        return targets;
    }

    private static string Str(JsonElement e, string name, string fallback = "") =>
        e.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() ?? fallback : fallback;

    private static string Unprotect(string payload)
    {
        if (string.IsNullOrEmpty(payload))
        {
            return "";
        }

        if (!payload.StartsWith("dpapi:", StringComparison.Ordinal) || !OperatingSystem.IsWindows())
        {
            throw new InvalidOperationException("Device password is not DPAPI-protected; pass --password.");
        }

        var bytes = ProtectedData.Unprotect(
            Convert.FromBase64String(payload["dpapi:".Length..]), Entropy, DataProtectionScope.LocalMachine);
        return Encoding.UTF8.GetString(bytes);
    }
}
