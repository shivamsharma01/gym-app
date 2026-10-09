using Microsoft.Extensions.Logging;

namespace Gym.Gateway;

/// <summary>
/// Desired-revision handlers for readers that have a worker. A notice with no worker is not
/// acknowledged and is not given to the command dispatcher.
/// </summary>
public sealed class DesiredRevisionHub
{
    private readonly Dictionary<string, Func<DesiredRevisionNotice, CancellationToken, Task>> _paths = new(StringComparer.Ordinal);
    private readonly Dictionary<string, Func<CancellationToken, Task>> _reconnects = new(StringComparer.Ordinal);
    private readonly Dictionary<string, Func<CancellationToken, Task>> _observations = new(StringComparer.Ordinal);
    private readonly ILogger<DesiredRevisionHub> _log;

    public DesiredRevisionHub(ILogger<DesiredRevisionHub> log)
    {
        _log = log;
    }

    public void Attach(
        string deviceId,
        Func<DesiredRevisionNotice, CancellationToken, Task> handle,
        Func<CancellationToken, Task> reconnect,
        Func<CancellationToken, Task>? observe = null)
    {
        if (string.IsNullOrWhiteSpace(deviceId))
        {
            throw new ArgumentException("A device id is required.", nameof(deviceId));
        }

        ArgumentNullException.ThrowIfNull(handle);
        ArgumentNullException.ThrowIfNull(reconnect);
        _paths[deviceId] = handle;
        _reconnects[deviceId] = reconnect;
        if (observe != null)
        {
            _observations[deviceId] = observe;
        }
    }

    /// <summary>Each flagged reader uploads a trusted list. A failed reader does not stop the next.</summary>
    public async Task ObserveAsync(CancellationToken cancellationToken)
    {
        foreach (var (deviceId, observe) in _observations)
        {
            try
            {
                await observe(cancellationToken).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                throw;
            }
            catch (Exception ex)
            {
                _log.LogWarning(ex, "Reader {DeviceId} observation was not uploaded", deviceId);
            }
        }
    }

    /// <summary>Each attached reader is read before its desired revisions are pulled.</summary>
    public async Task ReconnectAsync(CancellationToken cancellationToken)
    {
        foreach (var (deviceId, reconnect) in _reconnects)
        {
            try
            {
                await reconnect(cancellationToken).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                throw;
            }
            catch (Exception ex)
            {
                _log.LogWarning(ex, "Reader {DeviceId} reconnect did not apply desired state", deviceId);
            }
        }
    }

    public Task HandleAsync(DesiredRevisionNotice notice, CancellationToken cancellationToken)
    {
        if (_paths.TryGetValue(notice.DeviceId, out var handle))
        {
            return handle(notice, cancellationToken);
        }

        _log.LogInformation(
            "Desired revision {Revision} for {DeviceId} has no reader worker attached; it was not sent to the command dispatcher",
            notice.Revision, notice.DeviceId);
        return Task.CompletedTask;
    }
}
