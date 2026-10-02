using Gym.Gateway;
using Gym.Gateway.Config;
using Gym.Gateway.Security;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using Serilog;
using Serilog.Core;
using Serilog.Events;

var logDir = Path.Combine(GatewayConfigStore.DefaultConfigDirectory(), "logs");
Directory.CreateDirectory(logDir);

// Raised after the configuration is read (Gateway:LogLevel, config.json logLevel, or GYM_GATEWAY_LOG_LEVEL).
var levelSwitch = new LoggingLevelSwitch(LogEventLevel.Information);
Log.Logger = new LoggerConfiguration()
    .MinimumLevel.ControlledBy(levelSwitch)
    .MinimumLevel.Override("Microsoft", LogEventLevel.Information)
    .MinimumLevel.Override("System", LogEventLevel.Information)
    .Enrich.FromLogContext()
    .WriteTo.Console()
    .WriteTo.File(
        Path.Combine(logDir, "gateway-.log"),
        rollingInterval: RollingInterval.Day,
        retainedFileCountLimit: 14)
    .CreateLogger();

try
{
    var builder = Host.CreateApplicationBuilder(args);
    builder.Services.AddSerilog();

    if (OperatingSystem.IsWindows())
    {
        builder.Services.AddWindowsService(options =>
        {
            options.ServiceName = "Gym Gateway";
        });
    }

    ISecretProtector protector = OperatingSystem.IsWindows()
        ? new DpapiSecretProtector()
        : new PlaintextSecretProtector();

    var configStore = new GatewayConfigStore(
        protector,
        LoggerFactory.Create(b => b.AddSerilog()).CreateLogger<GatewayConfigStore>());

    var options = new GatewayOptions();
    builder.Configuration.GetSection(GatewayOptions.SectionName).Bind(options);

    if (configStore.TryLoad() is { } persisted)
    {
        configStore.ApplyTo(options, persisted);
        Log.Information("Loaded ProgramData gateway configuration for id={Id}", options.Id);
    }
    else
    {
        // Dev / Mock: appsettings + env overlay only.
        var env = Environment.GetEnvironmentVariables()
            .Cast<System.Collections.DictionaryEntry>()
            .ToDictionary(e => e.Key.ToString() ?? "", e => e.Value?.ToString(), StringComparer.Ordinal);
        options.OverlayEnvironment(env);
    }

    var envLevel = Environment.GetEnvironmentVariable("GYM_GATEWAY_LOG_LEVEL");
    if (!string.IsNullOrWhiteSpace(envLevel))
    {
        options.LogLevel = envLevel;
    }

    if (Enum.TryParse<LogEventLevel>(options.LogLevel, ignoreCase: true, out var level))
    {
        levelSwitch.MinimumLevel = level;
    }
    else
    {
        Log.Warning("Unknown log level '{Level}'; using Information (use Debug for sync troubleshooting)", options.LogLevel);
    }

    var errors = options.Validate();
    if (errors.Count > 0)
    {
        foreach (var error in errors)
        {
            Log.Error("{Error}", error);
        }

        Log.Information(
            "Configure via ProgramData GymGateway/config.json (production) or GYM_GATEWAY_ID + GYM_GATEWAY_TOKEN (dev).");
        return 2;
    }

    builder.Services.AddSingleton(options);
    builder.Services.AddSingleton(protector);
    builder.Services.AddSingleton(configStore);
    builder.Services.AddSingleton<GatewayEnrollmentClient>();
    builder.Services.AddSingleton(sp =>
        new DurableOutboundStore(sp.GetRequiredService<ILoggerFactory>().CreateLogger<DurableOutboundStore>()));
    builder.Services.AddSingleton(sp =>
        new BackendLink(
            options,
            sp.GetRequiredService<ILoggerFactory>().CreateLogger<BackendLink>(),
            sp.GetRequiredService<DurableOutboundStore>()));
    builder.Services.AddHostedService<GatewayWorker>();
    builder.Services.AddHostedService<CredentialRotationService>();

    Log.Information(
        "Starting gym device gateway id={Id} adapter={Adapter} backend={Backend} devices={DeviceCount} logLevel={Level}",
        options.Id, options.Adapter, options.BackendUrl, options.Devices.Count, levelSwitch.MinimumLevel);

    await builder.Build().RunAsync().ConfigureAwait(false);
    return 0;
}
catch (Exception ex)
{
    Log.Fatal(ex, "Gateway terminated unexpectedly");
    return 1;
}
finally
{
    await Log.CloseAndFlushAsync().ConfigureAwait(false);
}
