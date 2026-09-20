namespace Gym.Gateway.Config;

public sealed class PersistedGatewayConfig
{
    public string BackendUrl { get; set; } = "";

    public string GatewayId { get; set; } = "";

    /// <summary>Protected operational credential (never log).</summary>
    public string CredentialProtected { get; set; } = "";

    public DateTimeOffset? CredentialExpiresAt { get; set; }

    public TimeSpan RenewBefore { get; set; } = TimeSpan.FromDays(7);

    public string Adapter { get; set; } = "TrueFace";

    public bool UseWebSocket { get; set; } = true;

    public string? NativeDirectory { get; set; }

    public List<PersistedDeviceConfig> Devices { get; set; } = [];
}

public sealed class PersistedDeviceConfig
{
    public string DeviceId { get; set; } = "";

    public string Ip { get; set; } = "";

    public ushort Port { get; set; } = 37777;

    public string Username { get; set; } = "admin";

    /// <summary>Protected device password (never log).</summary>
    public string PasswordProtected { get; set; } = "";
}
