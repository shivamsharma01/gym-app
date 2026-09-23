using System.Text.Json;
using Gym.Gateway.Security;
using Microsoft.Extensions.Logging;

namespace Gym.Gateway.Config;

/// <summary>
/// Loads/saves gateway runtime config under ProgramData (Windows) or a test path.
/// Secrets are stored only through <see cref="ISecretProtector"/>.
/// </summary>
public sealed class GatewayConfigStore
{
    public const string DefaultDirName = "GymGateway";
    public const string ConfigFileName = "config.json";

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        WriteIndented = true,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase
    };

    private readonly ISecretProtector _protector;
    private readonly ILogger<GatewayConfigStore> _log;
    private readonly string _configPath;

    public GatewayConfigStore(ISecretProtector protector, ILogger<GatewayConfigStore> log, string? configPath = null)
    {
        _protector = protector;
        _log = log;
        _configPath = configPath ?? DefaultConfigPath();
    }

    public string ConfigPath => _configPath;

    public static string DefaultConfigDirectory()
    {
        if (OperatingSystem.IsWindows())
        {
            var programData = Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData);
            return Path.Combine(programData, DefaultDirName);
        }

        var home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        return Path.Combine(home, ".local", "share", DefaultDirName);
    }

    public static string DefaultConfigPath() => Path.Combine(DefaultConfigDirectory(), ConfigFileName);

    public bool Exists() => File.Exists(_configPath);

    public PersistedGatewayConfig? TryLoad()
    {
        if (!File.Exists(_configPath))
        {
            return null;
        }

        try
        {
            var json = File.ReadAllText(_configPath);
            return JsonSerializer.Deserialize<PersistedGatewayConfig>(json, JsonOptions);
        }
        catch (Exception ex)
        {
            _log.LogError(ex, "Failed to load gateway config from {Path}", _configPath);
            return null;
        }
    }

    public void Save(PersistedGatewayConfig config)
    {
        var dir = Path.GetDirectoryName(_configPath);
        if (!string.IsNullOrEmpty(dir))
        {
            Directory.CreateDirectory(dir);
        }

        var json = JsonSerializer.Serialize(config, JsonOptions);
        var temp = _configPath + ".tmp";
        File.WriteAllText(temp, json);
        File.Move(temp, _configPath, overwrite: true);
        _log.LogInformation("Gateway configuration saved");
    }

    public string Protect(string plaintext) => _protector.Protect(plaintext);

    public string Unprotect(string protectedPayload) => _protector.Unprotect(protectedPayload);

    public void ApplyTo(GatewayOptions options, PersistedGatewayConfig config)
    {
        options.BackendUrl = config.BackendUrl.TrimEnd('/');
        options.Id = config.GatewayId;
        options.Token = string.IsNullOrWhiteSpace(config.CredentialProtected)
            ? ""
            : Unprotect(config.CredentialProtected);
        options.CredentialExpiresAt = config.CredentialExpiresAt;
        options.RenewBefore = config.RenewBefore <= TimeSpan.Zero ? TimeSpan.FromDays(7) : config.RenewBefore;
        options.Adapter = string.IsNullOrWhiteSpace(config.Adapter) ? "TrueFace" : config.Adapter;
        options.UseWebSocket = config.UseWebSocket;
        options.NativeDirectory = config.NativeDirectory;
        options.Devices = config.Devices.Select(d => new DeviceEndpointOptions
        {
            DeviceId = d.DeviceId,
            Ip = d.Ip,
            Port = d.Port,
            Username = d.Username,
            Password = string.IsNullOrWhiteSpace(d.PasswordProtected) ? "" : Unprotect(d.PasswordProtected)
        }).ToList();
    }

    public PersistedGatewayConfig FromOptions(GatewayOptions options, PersistedGatewayConfig? previous = null)
    {
        return new PersistedGatewayConfig
        {
            BackendUrl = options.BackendUrl,
            GatewayId = options.Id,
            CredentialProtected = string.IsNullOrWhiteSpace(options.Token)
                ? previous?.CredentialProtected ?? ""
                : Protect(options.Token),
            CredentialExpiresAt = options.CredentialExpiresAt ?? previous?.CredentialExpiresAt,
            RenewBefore = options.RenewBefore,
            Adapter = options.Adapter,
            UseWebSocket = options.UseWebSocket,
            NativeDirectory = options.NativeDirectory,
            Devices = options.Devices.Select(d => new PersistedDeviceConfig
            {
                DeviceId = d.DeviceId,
                Ip = d.Ip,
                Port = d.Port,
                Username = d.Username,
                PasswordProtected = string.IsNullOrWhiteSpace(d.Password) ? "" : Protect(d.Password)
            }).ToList()
        };
    }
}
