using System.Collections.Concurrent;

namespace Gym.Gateway;

/// <summary>Maps device public ids to staff-facing reader names for logs.</summary>
public sealed class ReaderLabels
{
    private readonly ConcurrentDictionary<string, string> _names = new(StringComparer.Ordinal);

    public void Remember(string deviceId, string? name)
    {
        if (string.IsNullOrWhiteSpace(deviceId))
        {
            return;
        }

        if (string.IsNullOrWhiteSpace(name))
        {
            return;
        }

        _names[deviceId] = name.Trim();
    }

    public string Of(string deviceId)
    {
        if (string.IsNullOrWhiteSpace(deviceId))
        {
            return "?";
        }

        if (_names.TryGetValue(deviceId, out var name) && !string.IsNullOrWhiteSpace(name))
        {
            return name;
        }

        return ShortId(deviceId);
    }

    public static string ShortId(string deviceId) =>
        deviceId.Length <= 8 ? deviceId : deviceId[..8] + "…";
}
