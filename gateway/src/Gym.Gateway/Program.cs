using Gym.Gateway;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Serilog;

Log.Logger = new LoggerConfiguration()
    .MinimumLevel.Information()
    .Enrich.FromLogContext()
    .WriteTo.Console()
    .CreateLogger();

try
{
    var builder = Host.CreateApplicationBuilder(args);
    builder.Services.AddSerilog();

    var options = new GatewayOptions();
    builder.Configuration.GetSection(GatewayOptions.SectionName).Bind(options);
    var env = Environment.GetEnvironmentVariables()
        .Cast<System.Collections.DictionaryEntry>()
        .ToDictionary(e => e.Key.ToString() ?? "", e => e.Value?.ToString(), StringComparer.Ordinal);
    options.OverlayEnvironment(env);

    var errors = options.Validate();
    if (errors.Count > 0)
    {
        foreach (var error in errors)
        {
            Log.Error("{Error}", error);
        }

        Log.Information("Set GYM_GATEWAY_ID and GYM_GATEWAY_TOKEN (from POST /api/v1/gateways). Adapter defaults to Mock.");
        return 2;
    }

    builder.Services.AddSingleton(options);
    builder.Services.AddHostedService<GatewayWorker>();

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
