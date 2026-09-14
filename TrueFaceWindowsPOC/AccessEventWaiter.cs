using Gym.Gateway.Adapters;

namespace TrueFaceWindowsPOC;

/// <summary>Waits for a live ACCESS event, with attendance-record poll + operator fallback.</summary>
public sealed class AccessEventWaiter : IDeviceEventListener
{
    private readonly object _gate = new();
    private readonly List<NormalizedDeviceEvent> _events = [];
    private readonly List<string> _debugLines = [];

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

    public IReadOnlyList<string> DebugLines
    {
        get
        {
            lock (_gate)
            {
                return _debugLines.ToArray();
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
            _debugLines.Add(
                $"ACCESS user={evt.DeviceUserId ?? "(null)"} granted={evt.Granted} method={evt.Method} at={evt.OccurredAt:O}");
        }
    }

    public async Task<NormalizedDeviceEvent?> WaitForAsync(
        string deviceUserId,
        bool? granted,
        TimeSpan timeout,
        CancellationToken cancellationToken,
        IDeviceAdapter? adapter = null,
        DateTimeOffset? pollFromUtc = null)
    {
        var deadline = DateTime.UtcNow + timeout;
        var seenRecNos = new HashSet<long>();
        while (DateTime.UtcNow < deadline)
        {
            cancellationToken.ThrowIfCancellationRequested();

            var match = ConsumeMatch(deviceUserId, granted);
            if (match != null)
            {
                return match;
            }

            if (adapter != null)
            {
                try
                {
                    var from = pollFromUtc ?? DateTimeOffset.UtcNow.AddMinutes(-5);
                    foreach (var rec in adapter.FetchAttendance(from, null))
                    {
                        if (rec.RecNo is long rn)
                        {
                            if (!seenRecNos.Add(rn))
                            {
                                continue;
                            }
                        }

                        if (!IdsEqual(rec.DeviceUserId, deviceUserId))
                        {
                            continue;
                        }

                        if (granted.HasValue && rec.Granted != granted.Value)
                        {
                            continue;
                        }

                        var synthesized = new NormalizedDeviceEvent(
                            "ACCESS",
                            rec.DeviceUserId,
                            rec.OccurredAt,
                            rec.Method,
                            rec.Granted,
                            rec.RecNo,
                            null,
                            "from-attendance-poll");
                        lock (_gate)
                        {
                            _debugLines.Add(
                                $"POLL match user={rec.DeviceUserId} granted={rec.Granted} recNo={rec.RecNo}");
                        }

                        return synthesized;
                    }
                }
                catch
                {
                    // Poll is best-effort; keep waiting for live events.
                }
            }

            await Task.Delay(200, cancellationToken).ConfigureAwait(false);
        }

        return null;
    }

    private NormalizedDeviceEvent? ConsumeMatch(string deviceUserId, bool? granted)
    {
        lock (_gate)
        {
            var match = _events.FirstOrDefault(e =>
                IdsEqual(e.DeviceUserId, deviceUserId)
                && (!granted.HasValue || e.Granted == granted.Value));
            if (match != null)
            {
                _events.Remove(match);
            }

            return match;
        }
    }

    private static bool IdsEqual(string? a, string? b) =>
        string.Equals(a?.Trim(), b?.Trim(), StringComparison.OrdinalIgnoreCase);

    /// <summary>Test helper: inject an event as if the tablet reported it.</summary>
    public void Inject(NormalizedDeviceEvent evt) => OnNormalizedEvent(evt);
}
