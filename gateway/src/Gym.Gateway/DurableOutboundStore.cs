using System.Collections.Concurrent;
using System.Text.Json;
using Microsoft.Extensions.Logging;

namespace Gym.Gateway;

/// <summary>
/// Append-only durable log of outbound envelopes under ProgramData until the backend ACKs
/// {@code messageId}. Survives gateway crashes so live events are not lost when Internet drops.
/// </summary>
public sealed class DurableOutboundStore
{
    private readonly string _directory;
    private readonly ILogger<DurableOutboundStore> _log;
    private readonly ConcurrentDictionary<string, byte> _pending = new(StringComparer.Ordinal);

    public DurableOutboundStore(ILogger<DurableOutboundStore> log, string? directory = null)
    {
        _log = log;
        _directory = directory ?? Path.Combine(Config.GatewayConfigStore.DefaultConfigDirectory(), "outbox");
        Directory.CreateDirectory(_directory);
    }

    public string DirectoryPath => _directory;

    public void Persist(GatewayEnvelope envelope)
    {
        var path = Path.Combine(_directory, envelope.MessageId + ".json");
        var json = JsonSerializer.Serialize(envelope, JsonOptions.Outbound);
        var tmp = path + ".tmp";
        File.WriteAllText(tmp, json);
        File.Move(tmp, path, overwrite: true);
        _pending[envelope.MessageId] = 0;
    }

    public void Acknowledge(string messageId)
    {
        if (string.IsNullOrWhiteSpace(messageId))
        {
            return;
        }

        _pending.TryRemove(messageId, out _);
        var path = Path.Combine(_directory, messageId + ".json");
        try
        {
            if (File.Exists(path))
            {
                File.Delete(path);
            }
        }
        catch (Exception ex)
        {
            _log.LogWarning(ex, "Failed to delete acknowledged outbox file {MessageId}", messageId);
        }
    }

    public IReadOnlyList<GatewayEnvelope> LoadPending()
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
                    _pending[envelope.MessageId] = 0;
                }
            }
            catch (Exception ex)
            {
                _log.LogWarning(ex, "Skipping corrupt outbox file {File}", file);
            }
        }

        return list.OrderBy(e => e.Timestamp, StringComparer.Ordinal).ToList();
    }
}
