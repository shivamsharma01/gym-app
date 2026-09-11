namespace Gym.Gateway;

public sealed class GatewayOptions
{
    public const string SectionName = "Gateway";

    public string Id { get; set; } = "";

    public string Token { get; set; } = "";

    public string BackendUrl { get; set; } = "http://127.0.0.1:8080";

    /// <summary>Mock (default, any OS) or TrueFace (Windows + dhnetsdk.dll).</summary>
    public string Adapter { get; set; } = "Mock";

    public bool UseWebSocket { get; set; } = true;

    public int HeartbeatSeconds { get; set; } = 15;

    public int ReconnectBaseSeconds { get; set; } = 2;

    public int ReconnectMaxSeconds { get; set; } = 60;

    public string? NativeDirectory { get; set; }

    public List<DeviceEndpointOptions> Devices { get; set; } = [];

    public void OverlayEnvironment(IDictionary<string, string?> env)
    {
        Overlay(env, "GYM_GATEWAY_ID", v => Id = v);
        Overlay(env, "GYM_GATEWAY_TOKEN", v => Token = v);
        Overlay(env, "GYM_BACKEND", v => BackendUrl = v.TrimEnd('/'));
        Overlay(env, "GYM_ADAPTER", v => Adapter = v);
        Overlay(env, "GYM_NATIVE_DIR", v => NativeDirectory = v);
        if (env.TryGetValue("GYM_USE_WEBSOCKET", out var ws) && bool.TryParse(ws, out var useWs))
        {
            UseWebSocket = useWs;
        }

        var deviceId = Get(env, "GYM_DEVICE_ID");
        if (string.IsNullOrWhiteSpace(deviceId))
        {
            return;
        }

        var existing = Devices.FirstOrDefault(d => d.DeviceId == deviceId);
        if (existing == null)
        {
            existing = new DeviceEndpointOptions { DeviceId = deviceId };
            Devices.Add(existing);
        }

        Overlay(env, "GYM_DEVICE_IP", v => existing.Ip = v);
        Overlay(env, "GYM_DEVICE_PORT", v =>
        {
            if (ushort.TryParse(v, out var port))
            {
                existing.Port = port;
            }
        });
        Overlay(env, "GYM_DEVICE_USERNAME", v => existing.Username = v);
        Overlay(env, "GYM_DEVICE_PASSWORD", v => existing.Password = v);
    }

    public IReadOnlyList<string> Validate()
    {
        var errors = new List<string>();
        if (string.IsNullOrWhiteSpace(Id))
        {
            errors.Add("Gateway:Id / GYM_GATEWAY_ID is required");
        }

        if (string.IsNullOrWhiteSpace(Token))
        {
            errors.Add("Gateway:Token / GYM_GATEWAY_TOKEN is required");
        }

        if (string.IsNullOrWhiteSpace(BackendUrl))
        {
            errors.Add("Gateway:BackendUrl / GYM_BACKEND is required");
        }

        return errors;
    }

    private static void Overlay(IDictionary<string, string?> env, string key, Action<string> apply)
    {
        var value = Get(env, key);
        if (!string.IsNullOrWhiteSpace(value))
        {
            apply(value);
        }
    }

    private static string? Get(IDictionary<string, string?> env, string key) =>
        env.TryGetValue(key, out var value) ? value : null;
}

public sealed class DeviceEndpointOptions
{
    public string DeviceId { get; set; } = "";

    public string Ip { get; set; } = "";

    public ushort Port { get; set; } = 37777;

    public string Username { get; set; } = "admin";

    public string Password { get; set; } = "";
}
