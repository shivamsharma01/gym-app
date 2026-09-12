using Gym.Gateway.Adapters;

namespace TrueFaceWindowsPOC;

/// <summary>Waits for a live ACCESS event for a specific device user id.</summary>
public sealed class AccessEventWaiter : IDeviceEventListener
{
    private readonly object _gate = new();
    private readonly List<NormalizedDeviceEvent> _events = [];

    public IReadOnlyList<NormalizedDeviceEvent> Captured
    {
        get
        {
            lock (_gate)
            {
                return _events.ToArray();
            }
        }
    }

    public void OnNormalizedEvent(NormalizedDeviceEvent evt)
    {
        if (!string.Equals(evt.Kind, "ACCESS", StringComparison.OrdinalIgnoreCase))
        {
            return;
        }

        lock (_gate)
        {
            _events.Add(evt);
        }
    }

    public async Task<NormalizedDeviceEvent?> WaitForAsync(
        string deviceUserId,
        bool? granted,
        TimeSpan timeout,
        CancellationToken cancellationToken)
    {
        var deadline = DateTime.UtcNow + timeout;
        while (DateTime.UtcNow < deadline)
        {
            cancellationToken.ThrowIfCancellationRequested();
            NormalizedDeviceEvent? match;
            lock (_gate)
            {
                match = _events.FirstOrDefault(e =>
                    string.Equals(e.DeviceUserId, deviceUserId, StringComparison.Ordinal)
                    && (!granted.HasValue || e.Granted == granted.Value));
                if (match != null)
                {
                    // consume so the same event is not reused for a later wait
                    _events.Remove(match);
                }
            }

            if (match != null)
            {
                return match;
            }

            await Task.Delay(200, cancellationToken).ConfigureAwait(false);
        }

        return null;
    }

    /// <summary>Test helper: inject an event as if the tablet reported it.</summary>
    public void Inject(NormalizedDeviceEvent evt) => OnNormalizedEvent(evt);
}
