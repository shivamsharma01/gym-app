using System.Security.Cryptography;
using System.Text;
using Gym.Gateway.Adapters;

namespace Gym.Gateway.Execution;

/// <summary>
/// What the server already has from one reader, so a scan sends only what changed. A list counts as
/// known only after the server acknowledged it. A reconnect forgets that acknowledgement, and an
/// acknowledgement from a scan that started before a reconnect is not recorded. Everything is sent
/// again once <see cref="Refresh"/> has passed, so the server's own retries (such as seeding a
/// reader) still run.
/// </summary>
public sealed class ScanUploadState
{
    private readonly object _gate = new();
    private readonly TimeProvider _clock;
    private Dictionary<string, string> _sentEntries = new(StringComparer.Ordinal);
    private DateTimeOffset _allSentAt = DateTimeOffset.MinValue;
    private string? _acceptedList;
    private DateTimeOffset _acceptedAt = DateTimeOffset.MinValue;
    private long _generation;

    public ScanUploadState(TimeSpan refresh, TimeProvider? clock = null)
    {
        Refresh = refresh;
        _clock = clock ?? TimeProvider.System;
    }

    public TimeSpan Refresh { get; }

    public long Generation
    {
        get
        {
            lock (_gate)
            {
                return _generation;
            }
        }
    }

    /// <summary>True when the server acknowledged this exact list and absences within the refresh period.</summary>
    public bool AlreadyAccepted(string list)
    {
        lock (_gate)
        {
            return list == _acceptedList && _clock.GetUtcNow() - _acceptedAt < Refresh;
        }
    }

    public void Accepted(long generation, string list, IReadOnlyDictionary<string, string> entries)
    {
        lock (_gate)
        {
            if (generation != _generation)
            {
                return;
            }

            _acceptedList = list;
            _acceptedAt = _clock.GetUtcNow();
            AllSent(entries);
        }
    }

    /// <summary>
    /// A reconnect's list is not awaited, so nothing is known as accepted afterwards, and a scan
    /// that was already waiting for its acknowledgement does not record one.
    /// </summary>
    public void Reconnecting()
    {
        lock (_gate)
        {
            _generation++;
            _acceptedList = null;
        }
    }

    /// <summary>
    /// The users to send one by one after an unacknowledged list: those whose entry differs from
    /// the last one sent, or all of them once the refresh period has passed.
    /// </summary>
    public IReadOnlySet<string> Unsent(IReadOnlyDictionary<string, string> entries)
    {
        lock (_gate)
        {
            if (_clock.GetUtcNow() - _allSentAt >= Refresh)
            {
                return entries.Keys.ToHashSet(StringComparer.Ordinal);
            }

            return entries
                .Where(entry => !_sentEntries.TryGetValue(entry.Key, out var sent) || sent != entry.Value)
                .Select(entry => entry.Key)
                .ToHashSet(StringComparer.Ordinal);
        }
    }

    /// <summary>
    /// Counts these users as sent once they are handed to the outbound journal, which keeps them
    /// until they are delivered.
    /// </summary>
    public void Sent(IReadOnlySet<string> ids, IReadOnlyDictionary<string, string> entries)
    {
        lock (_gate)
        {
            if (ids.Count == entries.Count)
            {
                AllSent(entries);
                return;
            }

            foreach (var id in ids)
            {
                _sentEntries[id] = entries[id];
            }
        }
    }

    private void AllSent(IReadOnlyDictionary<string, string> entries)
    {
        _sentEntries = new Dictionary<string, string>(entries, StringComparer.Ordinal);
        _allSentAt = _clock.GetUtcNow();
    }

    /// <summary>Everything the server is told about one user, as one comparable string.</summary>
    public static string Entry(ReaderUser user, IReadOnlyDictionary<string, string> faceHashes) =>
        string.Join('\u001f',
            user.DeviceUserId,
            user.Name ?? "",
            user.NameEx ?? "",
            user.UserStatus,
            user.ValidFrom?.ToString("o") ?? "",
            user.ValidTo?.ToString("o") ?? "",
            user.Authority ?? "",
            faceHashes.TryGetValue(user.DeviceUserId, out var hash) ? hash : "");

    /// <summary>The whole upload: announced total, every entry, and the absences sent with it.</summary>
    public static string List(int announcedTotal, IReadOnlyDictionary<string, string> entries, IEnumerable<string> absent)
    {
        var text = new StringBuilder().Append(announcedTotal).Append('\u001e');
        foreach (var entry in entries.OrderBy(entry => entry.Key, StringComparer.Ordinal))
        {
            text.Append(entry.Value).Append('\u001e');
        }

        text.Append("absent");
        foreach (var id in absent.Order(StringComparer.Ordinal))
        {
            text.Append('\u001e').Append(id);
        }

        return Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(text.ToString())));
    }
}
