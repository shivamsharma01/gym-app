using System.Collections.Concurrent;
using Microsoft.Extensions.Logging;

namespace Gym.Gateway;

/// <summary>
/// Desired-revision handlers for readers that have a worker. A notice with no worker is not
/// acknowledged and is not given to the command dispatcher. A reader may be attached while the
/// loops run, when it comes online after the gateway started.
/// </summary>
public sealed class DesiredRevisionHub
{
    private readonly ConcurrentDictionary<string, Func<DesiredRevisionNotice, CancellationToken, Task>> _paths = new(StringComparer.Ordinal);
    private readonly ConcurrentDictionary<string, Func<CancellationToken, Task>> _reconnects = new(StringComparer.Ordinal);
    private readonly ConcurrentDictionary<string, Func<CancellationToken, Task>> _observations = new(StringComparer.Ordinal);
    private readonly ConcurrentDictionary<string, Func<CancellationToken, Task<bool>>> _scansNow = new(StringComparer.Ordinal);
    private readonly ConcurrentDictionary<string, Func<CancellationToken, Task>> _retries = new(StringComparer.Ordinal);
    private readonly ILogger<DesiredRevisionHub> _log;
    private readonly ReaderLabels _labels;

    public DesiredRevisionHub(ILogger<DesiredRevisionHub> log, ReaderLabels? labels = null)
    {
        _log = log;
        _labels = labels ?? new ReaderLabels();
    }

    public void Attach(
        string deviceId,
        Func<DesiredRevisionNotice, CancellationToken, Task> handle,
        Func<CancellationToken, Task> reconnect,
        Func<CancellationToken, Task>? observe = null,
        Func<CancellationToken, Task<bool>>? scanNow = null)
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

        if (scanNow != null)
        {
            _scansNow[deviceId] = scanNow;
        }
    }

    /// <summary>The reader's periodic retry of failed revisions, unsent acknowledgements, and its observation.</summary>
    public void AttachRetry(string deviceId, Func<CancellationToken, Task> retry)
    {
        ArgumentNullException.ThrowIfNull(retry);
        _retries[deviceId] = retry;
    }

    /// <summary>Each reader's retry. A failed reader does not stop the next.</summary>
    public async Task RetryAsync(CancellationToken cancellationToken)
    {
        foreach (var (deviceId, retry) in _retries)
        {
            try
            {
                await retry(cancellationToken).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                throw;
            }
            catch (Exception ex)
            {
                _log.LogWarning(ex, "Reader {Reader} retry did not apply desired state", _labels.Of(deviceId));
            }
        }
    }

    /// <summary>
    /// One reader, now, for a staff request. Null when that reader has no worker; false when it gave
    /// no trusted list.
    /// </summary>
    public async Task<bool?> ScanNowAsync(string deviceId, CancellationToken cancellationToken)
    {
        if (!_scansNow.TryGetValue(deviceId, out var scan))
        {
            return null;
        }

        return await scan(cancellationToken).ConfigureAwait(false);
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
            catch (Exception ex) when (IsTransientBackend(ex))
            {
                _log.LogWarning(
                    "Reader {Reader}: observation upload timed out or was canceled; will retry on the next scan ({Detail})",
                    _labels.Of(deviceId),
                    ex.GetType().Name);
            }
            catch (Exception ex)
            {
                _log.LogWarning(ex, "Reader {Reader}: observation was not uploaded", _labels.Of(deviceId));
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
            catch (Exception ex) when (IsTransientBackend(ex))
            {
                _log.LogWarning(
                    "Reader {Reader}: desired retry timed out or was canceled; will retry ({Detail})",
                    _labels.Of(deviceId),
                    ex.GetType().Name);
            }
            catch (Exception ex)
            {
                _log.LogWarning(ex, "Reader {Reader}: reconnect did not apply desired state", _labels.Of(deviceId));
            }
        }
    }

    private static bool IsTransientBackend(Exception ex) =>
        ex is TaskCanceledException or TimeoutException or OperationCanceledException
        || ex.InnerException is TaskCanceledException or TimeoutException;

    /// <summary>One reader, as after a reconnect. Nothing when it has no worker.</summary>
    public Task ReconnectAsync(string deviceId, CancellationToken cancellationToken) =>
        _reconnects.TryGetValue(deviceId, out var reconnect) ? reconnect(cancellationToken) : Task.CompletedTask;

    public Task HandleAsync(DesiredRevisionNotice notice, CancellationToken cancellationToken)
    {
        if (_paths.TryGetValue(notice.DeviceId, out var handle))
        {
            return handle(notice, cancellationToken);
        }

        _log.LogInformation(
            "Desired revision {Revision} for reader {Reader} has no worker attached; it was not sent to the command dispatcher",
            notice.Revision, _labels.Of(notice.DeviceId));
        return Task.CompletedTask;
    }
}
