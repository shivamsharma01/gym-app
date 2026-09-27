using System.Collections.Concurrent;
using System.Security.Cryptography;
using System.Text.Json;
using Gym.Gateway.Adapters;

namespace Gym.Gateway;

/// <summary>REST transfer of face images (never inside WebSocket frames).</summary>
public interface IFaceTransfer
{
    Task<FaceDownload> DownloadFaceAsync(string memberId, int version, CancellationToken cancellationToken);

    /// <summary>Uploads an image read from a device. Never throws for network failures.</summary>
    Task<FaceUpload> UploadFaceAsync(byte[] jpegBytes, CancellationToken cancellationToken);
}

public sealed record FaceDownload(bool Ok, byte[]? Bytes, string? Error);

/// <summary>
/// Upload outcome. <see cref="Rejected"/> means the server will never accept this image (e.g. not
/// a valid photo); otherwise a missing id is transient (server unreachable) and should be retried.
/// </summary>
public sealed record FaceUpload(string? UploadId, bool Rejected = false)
{
    public static readonly FaceUpload Transient = new(UploadId: null);
}

public static class FaceHash
{
    public static string Sha256Hex(byte[] bytes) => Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();
}

/// <summary>One lock per device so command dispatch and roster scans never interleave SDK calls.</summary>
public sealed class DeviceLocks
{
    private readonly ConcurrentDictionary<string, SemaphoreSlim> _locks = new(StringComparer.Ordinal);

    public SemaphoreSlim For(string deviceId) => _locks.GetOrAdd(deviceId, _ => new SemaphoreSlim(1, 1));
}

/// <summary>
/// What the gateway last saw (or wrote) for a user on a device. Values are as the device reports
/// them (used to detect edits made on the device); NameAt / AccessAt / FaceAt are the change times
/// of the <see cref="LocalMember"/> versions this device holds (used to bring it up to date).
/// </summary>
public sealed record KnownUser(
    string DeviceUserId,
    string? Name,
    bool Frozen,
    string? ValidFrom,
    string? ValidTo,
    string? FaceSha256,
    DateTimeOffset? FaceCheckedAt,
    DateTimeOffset FirstSeenAt,
    bool DeviceCreated = false,
    DateTimeOffset? NameAt = null,
    DateTimeOffset? AccessAt = null,
    DateTimeOffset? FaceAt = null)
{
    public static string? Date(DateTimeOffset? value) => value?.UtcDateTime.ToString("yyyy-MM-dd");

    public static DateTimeOffset? ParseDate(string? yyyyMmDd) =>
        DateTime.TryParseExact(yyyyMmDd, "yyyy-MM-dd", System.Globalization.CultureInfo.InvariantCulture,
            System.Globalization.DateTimeStyles.AssumeUniversal | System.Globalization.DateTimeStyles.AdjustToUniversal,
            out var d)
            ? new DateTimeOffset(DateTime.SpecifyKind(d, DateTimeKind.Utc))
            : null;

    public static KnownUser From(DeviceUserSnapshot user, KnownUser? previous) =>
        (previous ?? new KnownUser(user.DeviceUserId, null, false, null, null, null, null, DateTimeOffset.UtcNow)) with
        {
            Name = user.Name,
            Frozen = user.Frozen,
            ValidFrom = Date(user.ValidFrom),
            ValidTo = Date(user.ValidTo)
        };

    public bool SameProfile(DeviceUserSnapshot user) => Diff(user) == ProfileDiff.None;

    public ProfileDiff Diff(DeviceUserSnapshot user) =>
        new(
            !string.Equals(Name ?? "", user.Name ?? "", StringComparison.Ordinal),
            Frozen != user.Frozen,
            ValidFrom != Date(user.ValidFrom) || ValidTo != Date(user.ValidTo));
}

/// <summary>Which profile fields changed on the device since the gateway last saw or wrote them.</summary>
public readonly record struct ProfileDiff(bool Name, bool Frozen, bool Validity)
{
    public static readonly ProfileDiff None = new(false, false, false);
    public static readonly ProfileDiff All = new(true, true, true);

    public bool Any => Name || Frozen || Validity;
}

/// <summary>
/// Per-device roster snapshot used for change detection and echo suppression. Updated after every
/// successful server push (so our own writes are never reported back as device edits) and after
/// every detected device change. Persisted as JSON under ProgramData so restarts keep the baseline.
/// </summary>
public sealed class RosterStateStore
{
    private readonly string? _directory;
    private readonly ConcurrentDictionary<string, DeviceRoster> _devices = new(StringComparer.Ordinal);
    private readonly object _io = new();

    /// <param name="directory">Folder for JSON files; null keeps state in memory only (tests).</param>
    public RosterStateStore(string? directory)
    {
        _directory = directory;
        if (_directory != null)
        {
            Directory.CreateDirectory(_directory);
        }
    }

    public static string DefaultDirectory() =>
        Path.Combine(Config.GatewayConfigStore.DefaultConfigDirectory(), "roster");

    public bool HasBaseline(string deviceId) => Load(deviceId).Baseline;

    public KnownUser? Find(string deviceId, string deviceUserId) =>
        Load(deviceId).Users.TryGetValue(deviceUserId, out var u) ? u : null;

    public IReadOnlyCollection<KnownUser> All(string deviceId) => Load(deviceId).Users.Values.ToArray();

    public void Upsert(string deviceId, KnownUser user)
    {
        var roster = Load(deviceId);
        roster.Users[user.DeviceUserId] = user;
        Save(deviceId, roster);
    }

    public void RecordProfile(string deviceId, DeviceUserSnapshot user) =>
        Upsert(deviceId, KnownUser.From(user, Find(deviceId, user.DeviceUserId)));

    public void RecordFace(string deviceId, string deviceUserId, string? faceSha256)
    {
        var existing = Find(deviceId, deviceUserId)
                       ?? new KnownUser(deviceUserId, null, false, null, null, null, null, DateTimeOffset.UtcNow);
        Upsert(deviceId, existing with { FaceSha256 = faceSha256, FaceCheckedAt = DateTimeOffset.UtcNow });
    }

    public void Remove(string deviceId, string deviceUserId)
    {
        var roster = Load(deviceId);
        if (roster.Users.Remove(deviceUserId))
        {
            Save(deviceId, roster);
        }
    }

    public void MarkBaseline(string deviceId)
    {
        var roster = Load(deviceId);
        roster.Baseline = true;
        Save(deviceId, roster);
    }

    private DeviceRoster Load(string deviceId) =>
        _devices.GetOrAdd(deviceId, id =>
        {
            var path = PathFor(id);
            if (path != null && File.Exists(path))
            {
                try
                {
                    var loaded = JsonSerializer.Deserialize<DeviceRoster>(File.ReadAllText(path));
                    if (loaded != null)
                    {
                        loaded.Users = new Dictionary<string, KnownUser>(loaded.Users, StringComparer.Ordinal);
                        return loaded;
                    }
                }
                catch
                {
                    // Corrupt file: start a fresh baseline rather than flooding the backend.
                }
            }

            return new DeviceRoster();
        });

    private void Save(string deviceId, DeviceRoster roster)
    {
        var path = PathFor(deviceId);
        if (path == null)
        {
            return;
        }

        lock (_io)
        {
            var tmp = path + ".tmp";
            File.WriteAllText(tmp, JsonSerializer.Serialize(roster));
            File.Move(tmp, path, overwrite: true);
        }
    }

    private string? PathFor(string deviceId)
    {
        if (_directory == null)
        {
            return null;
        }

        var safe = string.Concat(deviceId.Select(c => char.IsLetterOrDigit(c) || c == '-' ? c : '_'));
        return Path.Combine(_directory, safe + ".json");
    }

    private sealed class DeviceRoster
    {
        public bool Baseline { get; set; }

        public Dictionary<string, KnownUser> Users { get; set; } = new(StringComparer.Ordinal);
    }
}
