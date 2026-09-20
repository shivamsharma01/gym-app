using Gym.Gateway.Config;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace Gym.Gateway;

/// <summary>
/// Renews the operational credential before expiry. Persists V2 to disk before swapping in-memory
/// token / reconnecting so a crash mid-rotate never loses V1.
/// </summary>
public sealed class CredentialRotationService : BackgroundService
{
    private readonly GatewayOptions _options;
    private readonly GatewayConfigStore _store;
    private readonly BackendLink _link;
    private readonly GatewayEnrollmentClient _client;
    private readonly ILogger<CredentialRotationService> _log;
    private readonly TimeSpan _pollInterval;

    public CredentialRotationService(
        GatewayOptions options,
        GatewayConfigStore store,
        BackendLink link,
        GatewayEnrollmentClient client,
        ILogger<CredentialRotationService> log,
        TimeSpan? pollInterval = null)
    {
        _options = options;
        _store = store;
        _link = link;
        _client = client;
        _log = log;
        _pollInterval = pollInterval ?? TimeSpan.FromHours(1);
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        if (!_store.Exists() && _options.CredentialExpiresAt is null)
        {
            _log.LogInformation("Credential rotation idle (no ProgramData config / no expiry)");
            return;
        }

        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                await MaybeRotateAsync(stoppingToken).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                break;
            }
            catch (Exception ex)
            {
                _log.LogWarning(ex, "Credential rotation attempt failed; will retry");
            }

            try
            {
                await Task.Delay(_pollInterval, stoppingToken).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                break;
            }
        }
    }

    internal async Task MaybeRotateAsync(CancellationToken cancellationToken)
    {
        if (!ShouldRenew(DateTimeOffset.UtcNow))
        {
            return;
        }

        _log.LogInformation("Renewing gateway credential (approaching expiry)");
        var result = await _client.RotateAsync(_options.BackendUrl, _options.Token, cancellationToken)
            .ConfigureAwait(false);

        // Persist V2 first — never delete V1 from disk until this succeeds.
        var previous = _store.TryLoad();
        var toSave = new PersistedGatewayConfig
        {
            BackendUrl = _options.BackendUrl,
            GatewayId = _options.Id,
            CredentialProtected = _store.Protect(result.Credential),
            CredentialExpiresAt = result.ExpiresAt,
            RenewBefore = _options.RenewBefore <= TimeSpan.Zero ? TimeSpan.FromDays(7) : _options.RenewBefore,
            Adapter = _options.Adapter,
            UseWebSocket = _options.UseWebSocket,
            NativeDirectory = _options.NativeDirectory,
            Devices = previous?.Devices ?? _options.Devices.Select(d => new PersistedDeviceConfig
            {
                DeviceId = d.DeviceId,
                Ip = d.Ip,
                Port = d.Port,
                Username = d.Username,
                PasswordProtected = string.IsNullOrWhiteSpace(d.Password) ? "" : _store.Protect(d.Password)
            }).ToList()
        };
        _store.Save(toSave);

        _options.Token = result.Credential;
        _options.CredentialExpiresAt = result.ExpiresAt;
        _link.ApplyCredential(result.Credential);
        _log.LogInformation("Credential renewed; expires at {ExpiresAt:u}", result.ExpiresAt);
    }

    internal bool ShouldRenew(DateTimeOffset now)
    {
        if (_options.CredentialExpiresAt is not { } expires)
        {
            return false;
        }

        var renewBefore = _options.RenewBefore <= TimeSpan.Zero ? TimeSpan.FromDays(7) : _options.RenewBefore;
        return now >= expires - renewBefore;
    }
}
