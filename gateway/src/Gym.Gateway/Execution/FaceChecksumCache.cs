using System.Text.Json;

namespace Gym.Gateway.Execution;

/// <summary>
/// The SHA-256 of the photo behind each reader photo checksum, so a scan downloads a photo only when
/// the reader's MD5 for it changed. Keyed by the checksum rather than the user, so an entry is only
/// ever reused for the same bytes. A missing or unreadable file starts empty and only costs one
/// download per photo.
/// </summary>
public sealed class FaceChecksumCache
{
    private readonly string? _path;
    private readonly object _gate = new();
    private Dictionary<string, string> _sha256ByChecksum;

    public FaceChecksumCache(string? path)
    {
        _path = path;
        _sha256ByChecksum = Load(path);
    }

    public bool TryGet(string checksum, out string sha256)
    {
        lock (_gate)
        {
            return _sha256ByChecksum.TryGetValue(checksum, out sha256!);
        }
    }

    /// <summary>Keeps only the photos the reader listed this time, and saves when that changed anything.</summary>
    public void Replace(IReadOnlyDictionary<string, string> sha256ByChecksum)
    {
        lock (_gate)
        {
            if (_sha256ByChecksum.Count == sha256ByChecksum.Count
                && sha256ByChecksum.All(entry =>
                    _sha256ByChecksum.TryGetValue(entry.Key, out var known) && known == entry.Value))
            {
                return;
            }

            _sha256ByChecksum = new Dictionary<string, string>(sha256ByChecksum, StringComparer.Ordinal);
            Save();
        }
    }

    private void Save()
    {
        if (_path == null)
        {
            return;
        }

        try
        {
            var temporary = _path + ".tmp";
            File.WriteAllText(temporary, JsonSerializer.Serialize(_sha256ByChecksum));
            File.Move(temporary, _path, overwrite: true);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // The next scan downloads the photos again.
        }
    }

    private static Dictionary<string, string> Load(string? path)
    {
        try
        {
            if (path != null && File.Exists(path)
                && JsonSerializer.Deserialize<Dictionary<string, string>>(File.ReadAllText(path)) is { } saved)
            {
                return new Dictionary<string, string>(saved, StringComparer.Ordinal);
            }
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or JsonException)
        {
        }

        return new Dictionary<string, string>(StringComparer.Ordinal);
    }
}
