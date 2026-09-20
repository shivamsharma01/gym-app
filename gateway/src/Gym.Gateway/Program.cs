using Gym.Gateway;
using Gym.Gateway.Config;
using Gym.Gateway.Security;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using Serilog;

var logDir = Path.Combine(GatewayConfigStore.DefaultConfigDirectory(), "logs");
Directory.CreateDirectory(logDir);

Log.Logger = new LoggerConfiguration()
    .MinimumLevel.Information()
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
        new BackendLink(options, sp.GetRequiredService<ILoggerFactory>().CreateLogger<BackendLink>()));
    builder.Services.AddHostedService<GatewayWorker>();
    builder.Services.AddHostedService<CredentialRotationService>();

    Log.Information(
        "Starting gym device gateway id={Id} adapter={Adapter} backend={Backend} devices={DeviceCount}",
        options.Id, options.Adapter, options.BackendUrl, options.Devices.Count);

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
