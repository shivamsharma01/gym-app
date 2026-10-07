using Microsoft.Extensions.Logging;

namespace Gym.Gateway;

/// <summary>
/// Desired-revision handlers for readers that have a worker. A notice with no worker is not
/// acknowledged and is not given to the command dispatcher.
/// </summary>
public sealed class DesiredRevisionHub
{
    private readonly Dictionary<string, Func<DesiredRevisionNotice, CancellationToken, Task>> _paths = new(StringComparer.Ordinal);
    private readonly ILogger<DesiredRevisionHub> _log;

    public DesiredRevisionHub(ILogger<DesiredRevisionHub> log)
    {
        _log = log;
    }

    public void Attach(string deviceId, Func<DesiredRevisionNotice, CancellationToken, Task> handle)
    {
        if (string.IsNullOrWhiteSpace(deviceId))
        {
            throw new ArgumentException("A device id is required.", nameof(deviceId));
        }

        ArgumentNullException.ThrowIfNull(handle);
        _paths[deviceId] = handle;
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
