using System.Collections.Concurrent;
using System.Text.Json;
using Microsoft.Extensions.Logging;

namespace Gym.Gateway;

/// <summary>
/// Append-only durable log of outbound envelopes under ProgramData until the backend ACKs
/// {@code messageId}. Survives gateway crashes so live events are not lost when Internet drops.
/// A complete reader list is a snapshot: a newer list of the same reader replaces an earlier one
/// that is still unsent or unacknowledged. Every other envelope is kept until it is acknowledged.
/// </summary>
public sealed class DurableOutboundStore
{
    private readonly string _directory;
    private readonly ILogger<DurableOutboundStore> _log;
    private readonly ConcurrentDictionary<string, byte> _pending = new(StringComparer.Ordinal);
    private readonly Dictionary<string, Snapshot> _latestSnapshot = new(StringComparer.Ordinal);
    private readonly object _snapshotGate = new();

    public DurableOutboundStore(ILogger<DurableOutboundStore> log, string? directory = null)
    {
        _log = log;
        _directory = directory ?? Path.Combine(Config.GatewayConfigStore.DefaultConfigDirectory(), "outbox");
        Directory.CreateDirectory(_directory);
    }

    public string DirectoryPath => _directory;

    /// <summary>
    /// The reader whose complete list this envelope is, or null for every other envelope. A list
    /// carries its announced total; a single user's observation or absence does not.
    /// </summary>
    internal static string? SnapshotKey(GatewayEnvelope envelope) =>
        envelope.Type == ProtocolTypes.DeviceUserChanged
        && !string.IsNullOrWhiteSpace(envelope.DeviceId)
        && envelope.Payload.ValueKind == JsonValueKind.Object
        && envelope.Payload.TryGetProperty("announcedTotal", out _)
            ? envelope.DeviceId
            : null;

    /// <summary>
    /// Writes the envelope before anything else. A list then drops the earlier list of the same
    /// reader, so a crash between the two steps leaves both on disk and loading keeps the newer.
    /// </summary>
    /// <returns>The message id of the list this one replaced, or null.</returns>
    public string? Persist(GatewayEnvelope envelope)
    {
        var path = PathOf(envelope.MessageId);
        var json = JsonSerializer.Serialize(envelope, JsonOptions.Outbound);
        var tmp = path + ".tmp";
        File.WriteAllText(tmp, json);
        File.Move(tmp, path, overwrite: true);
        _pending[envelope.MessageId] = 0;

        var key = SnapshotKey(envelope);
        if (key == null)
        {
            return null;
        }

        lock (_snapshotGate)
        {
            string? replaced = null;
            if (_latestSnapshot.TryGetValue(key, out var earlier)
                && !string.Equals(earlier.MessageId, envelope.MessageId, StringComparison.Ordinal))
            {
                Drop(earlier.MessageId);
                replaced = earlier.MessageId;
            }

            _latestSnapshot[key] = new Snapshot(envelope.MessageId, envelope.Timestamp);
            return replaced;
        }
    }

    /// <summary>
    /// Drops every unacknowledged observation of one reader. Used when the reader's scan journal is
    /// new: those observations were numbered in a journal the server can no longer place them in.
    /// </summary>
    /// <returns>How many were dropped.</returns>
    public int DropObservations(string deviceId)
    {
        var dropped = 0;
        lock (_snapshotGate)
        {
            foreach (var envelope in ReadFiles())
            {
                if (envelope.Type != ProtocolTypes.DeviceUserChanged
                    || !string.Equals(envelope.DeviceId, deviceId, StringComparison.Ordinal))
                {
                    continue;
                }

                Drop(envelope.MessageId);
                dropped++;
            }

            _latestSnapshot.Remove(deviceId);
        }

        return dropped;
    }

    /// <summary>False once the envelope was acknowledged or replaced by a newer list.</summary>
    public bool IsPending(string messageId) => _pending.ContainsKey(messageId);

    public void Acknowledge(string messageId)
    {
        if (string.IsNullOrWhiteSpace(messageId))
        {
            return;
        }

        lock (_snapshotGate)
        {
            foreach (var (key, latest) in _latestSnapshot)
            {
                if (string.Equals(latest.MessageId, messageId, StringComparison.Ordinal))
                {
                    _latestSnapshot.Remove(key);
                    break;
                }
            }
        }

        Drop(messageId);
    }

    /// <summary>
    /// Every unacknowledged envelope, oldest first. Of several lists left for one reader (a crash
    /// between writing a list and dropping the earlier one), only the newest is kept, and none is
    /// kept when this process has queued a newer list of that reader since the files were read.
    /// </summary>
    public IReadOnlyList<GatewayEnvelope> LoadPending()
    {
        var ordered = ReadFiles().OrderBy(e => e.Timestamp, StringComparer.Ordinal).ToList();
        var kept = new List<GatewayEnvelope>(ordered.Count);
        lock (_snapshotGate)
        {
            var newest = NewestListPerReader(ordered);
            foreach (var envelope in ordered)
            {
                var key = SnapshotKey(envelope);
                if (key != null && !(newest.TryGetValue(key, out var latest) && ReferenceEquals(latest, envelope)))
                {
                    _log.LogInformation(
                        "Dropping list {MessageId} for {DeviceId}; a newer list is queued",
                        envelope.MessageId, key);
                    Drop(envelope.MessageId);
                    continue;
                }

                _pending[envelope.MessageId] = 0;
                kept.Add(envelope);
            }

            foreach (var (key, envelope) in newest)
            {
                _latestSnapshot[key] = new Snapshot(envelope.MessageId, envelope.Timestamp);
            }
        }

        return kept;
    }

    private List<GatewayEnvelope> ReadFiles()
    {
        var list = new List<GatewayEnvelope>();
        foreach (var file in Directory.EnumerateFiles(_directory, "*.json"))
        {
            try
            {
                var json = File.ReadAllText(file);
                var envelope = JsonSerializer.Deserialize<GatewayEnvelope>(json, JsonOptions.Inbound);
                if (envelope != null && !string.IsNullOrWhiteSpace(envelope.MessageId))
                {
                    list.Add(envelope);
                }
            }
            catch (Exception ex)
            {
                _log.LogWarning(ex, "Skipping corrupt outbox file {File}", file);
            }
        }

        return list;
    }

    /// <summary>The newest list per reader on disk, unless this process already queued a newer one. Caller holds the snapshot gate.</summary>
    private Dictionary<string, GatewayEnvelope> NewestListPerReader(List<GatewayEnvelope> ordered)
    {
        var newest = new Dictionary<string, GatewayEnvelope>(StringComparer.Ordinal);
        foreach (var envelope in ordered)
        {
            var key = SnapshotKey(envelope);
            if (key != null)
            {
                newest[key] = envelope;
            }
        }

        foreach (var key in newest.Keys.ToList())
        {
            if (_latestSnapshot.TryGetValue(key, out var held)
                && _pending.ContainsKey(held.MessageId)
                && string.CompareOrdinal(held.Timestamp, newest[key].Timestamp) > 0)
            {
                newest.Remove(key);
            }
        }

        return newest;
    }

    private string PathOf(string messageId) => Path.Combine(_directory, messageId + ".json");

    private sealed record Snapshot(string MessageId, string Timestamp);

    private void Drop(string messageId)
    {
        _pending.TryRemove(messageId, out _);
        var path = PathOf(messageId);
        try
        {
            if (File.Exists(path))
            {
                File.Delete(path);
            }
        }
        catch (Exception ex)
        {
            _log.LogWarning(ex, "Failed to delete outbox file {MessageId}", messageId);
        }
    }
}
