using System.Collections.Concurrent;
using System.Diagnostics;
using System.Security.Cryptography;
using System.Text.Json;
using Gym.Gateway.Adapters;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;

namespace Gym.Gateway;

/// <summary>REST transfer of face images (never inside WebSocket frames).</summary>
public interface IFaceTransfer
{
    Task<FaceDownload> DownloadFaceAsync(string memberId, int version, CancellationToken cancellationToken);

    /// <summary>Uploads an image read from a device. Never throws for network failures.</summary>
    Task<FaceUpload> UploadFaceAsync(byte[] jpegBytes, CancellationToken cancellationToken);

    /// <summary>
    /// Uploads several device photos in one HTTP request. Implementations may fall back to
    /// UploadFaceAsync; the backend implementation uses multipart/form-data.
    /// </summary>
    async Task<IReadOnlyList<FaceUpload>> UploadFacesAsync(
        IReadOnlyList<FaceUploadItem> items,
        CancellationToken cancellationToken)
    {
        var results = new List<FaceUpload>(items.Count);
        foreach (var item in items)
        {
            results.Add(await UploadFaceAsync(item.Bytes, cancellationToken).ConfigureAwait(false));
        }

        return results;
    }
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

public sealed record FaceUploadItem(
    string DeviceUserId,
    string Sha256,
    byte[] Bytes);

public static class FaceHash
{
    public static string Sha256Hex(byte[] bytes) => Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();
}

/// <summary>
/// One lock per device so command dispatch and roster scans never interleave SDK calls. Each holder
/// names its task, so logs can say what a reader is busy with and who had to wait for it.
/// </summary>
public sealed class DeviceLocks
{
    private static readonly TimeSpan NoticeableWait = TimeSpan.FromSeconds(2);
    private static readonly TimeSpan LongTask = TimeSpan.FromSeconds(10);

    private readonly ConcurrentDictionary<string, SemaphoreSlim> _locks = new(StringComparer.Ordinal);
    private readonly ConcurrentDictionary<string, (string Task, DateTimeOffset Since)> _busy = new(StringComparer.Ordinal);
    private readonly ILogger _log;

    public DeviceLocks(ILogger? log = null)
    {
        _log = log ?? NullLogger.Instance;
    }

    public SemaphoreSlim For(string deviceId) => _locks.GetOrAdd(deviceId, _ => new SemaphoreSlim(1, 1));

    /// <summary>Waits for the reader and marks it busy with <paramref name="task"/> until the lease is disposed.</summary>
    public async Task<IDisposable> AcquireAsync(string deviceId, string task, CancellationToken cancellationToken = default)
    {
        var gate = For(deviceId);
        if (!await gate.WaitAsync(0, cancellationToken).ConfigureAwait(false))
        {
            var holder = BusyWith(deviceId) ?? "another task";
            var waited = Stopwatch.StartNew();
            await gate.WaitAsync(cancellationToken).ConfigureAwait(false);
            if (waited.Elapsed >= NoticeableWait)
            {
                _log.LogInformation("Reader {DeviceId}: {Task} waited {Seconds} s because the reader was busy with {Holder}",
                    deviceId, task, (long)waited.Elapsed.TotalSeconds, holder);
            }
        }

        _busy[deviceId] = (task, DateTimeOffset.UtcNow);
        return new Lease(this, deviceId, gate);
    }

    /// <summary>What the reader is doing right now and for how long, or null when it is idle.</summary>
    public string? BusyWith(string deviceId) =>
        _busy.TryGetValue(deviceId, out var busy)
            ? $"{busy.Task} (for {(long)(DateTimeOffset.UtcNow - busy.Since).TotalSeconds} s)"
            : null;

    private void Release(string deviceId, SemaphoreSlim gate)
    {
        if (_busy.TryRemove(deviceId, out var busy))
        {
            var took = DateTimeOffset.UtcNow - busy.Since;
            if (took >= LongTask)
            {
                _log.LogInformation("Reader {DeviceId}: finished {Task} after {Seconds} s", deviceId, busy.Task,
                    (long)took.TotalSeconds);
            }
        }

        gate.Release();
    }

    private sealed class Lease(DeviceLocks owner, string deviceId, SemaphoreSlim gate) : IDisposable
    {
        private int _released;

        public void Dispose()
        {
            if (Interlocked.Exchange(ref _released, 1) == 0)
            {
                owner.Release(deviceId, gate);
            }
        }
    }
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
    DateTimeOffset? FaceAt = null,
    string? Authority = null)
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
            ValidTo = Date(user.ValidTo),
            Authority = user.Authority ?? previous?.Authority ?? "USER"
        };

    public bool SameProfile(DeviceUserSnapshot user) => Diff(user) == ProfileDiff.None;

    public ProfileDiff Diff(DeviceUserSnapshot user) =>
        new(
            !string.Equals(Name ?? "", user.Name ?? "", StringComparison.Ordinal),
            Frozen != user.Frozen,
            ValidFrom != Date(user.ValidFrom) || ValidTo != Date(user.ValidTo),
            !string.Equals(Authority ?? "USER", user.Authority ?? "USER", StringComparison.OrdinalIgnoreCase));
}

/// <summary>Which profile fields changed on the device since the gateway last saw or wrote them.</summary>
public readonly record struct ProfileDiff(bool Name, bool Frozen, bool Validity, bool Authority = false)
{
    public static readonly ProfileDiff None = new(false, false, false, false);
    public static readonly ProfileDiff All = new(true, true, true, true);

    public bool Any => Name || Frozen || Validity || Authority;
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
    private readonly Dictionary<string, DeferredSaves> _deferred = new(StringComparer.Ordinal);

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
        roster.FaceCursor = 0;
        Save(deviceId, roster);
    }

    /// <summary>Position in the reader's user list where an unfinished photo pass resumes. 0 means none is running.</summary>
    public int FaceCursor(string deviceId) => Load(deviceId).FaceCursor;

    public DateTimeOffset? LastFaceSweepUtc(string deviceId) => Load(deviceId).LastFaceSweepUtc;

    /// <summary>True while a staff-requested pass that re-reads every photo, known or not, is pending.</summary>
    public bool FaceRefreshRequested(string deviceId) => Load(deviceId).FaceRefreshAll;

    public void RequestFaceRefresh(string deviceId)
    {
        var roster = Load(deviceId);
        roster.FaceRefreshAll = true;
        roster.FaceCursor = 0;
        Save(deviceId, roster);
    }

    public void SetFaceCursor(string deviceId, int cursor)
    {
        var roster = Load(deviceId);
        if (roster.FaceCursor == cursor)
        {
            return;
        }

        roster.FaceCursor = cursor;
        Save(deviceId, roster);
    }

    public void CompleteFaceSweep(string deviceId, DateTimeOffset at)
    {
        var roster = Load(deviceId);
        roster.FaceCursor = 0;
        roster.FaceRefreshAll = false;
        roster.LastFaceSweepUtc = at;
        Save(deviceId, roster);
    }

    /// <summary>
    /// Keeps changes for this reader in memory until the returned scope is disposed, then writes the file once.
    /// A scan that touches hundreds of users otherwise rewrites the whole file for each of them.
    /// </summary>
    public IDisposable DeferSaves(string deviceId)
    {
        lock (_io)
        {
            if (!_deferred.TryGetValue(deviceId, out var state))
            {
                state = new DeferredSaves();
                _deferred[deviceId] = state;
            }

            state.Depth++;
        }

        return new SaveScope(this, deviceId);
    }

    private void EndDeferredSaves(string deviceId)
    {
        bool dirty;
        lock (_io)
        {
            var state = _deferred[deviceId];
            if (--state.Depth > 0)
            {
                return;
            }

            _deferred.Remove(deviceId);
            dirty = state.Dirty;
        }

        if (dirty)
        {
            Save(deviceId, Load(deviceId));
        }
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
            if (_deferred.TryGetValue(deviceId, out var deferred))
            {
                deferred.Dirty = true;
                return;
            }

            var tmp = path + ".tmp";
            File.WriteAllText(tmp, JsonSerializer.Serialize(roster));
            try
            {
                ReplaceRosterFile(tmp, path);
            }
            catch (Exception ex) when (ex is UnauthorizedAccessException or IOException)
            {
                var dest = new FileInfo(path);
                if (dest.Exists && dest.IsReadOnly)
                {
                    dest.IsReadOnly = false;
                }

                try
                {
                    ReplaceRosterFile(tmp, path);
                }
                catch (Exception retry) when (retry is UnauthorizedAccessException or IOException)
                {
                    throw new IOException(
                        $"Could not save roster file '{path}'. Grant the Gym Gateway service permission to modify that folder.",
                        retry);
                }
            }
        }
    }

    private static void ReplaceRosterFile(string tmp, string path)
    {
        File.Move(tmp, path, overwrite: true);
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

    private sealed class DeferredSaves
    {
        public int Depth;
        public bool Dirty;
    }

    private sealed class SaveScope(RosterStateStore owner, string deviceId) : IDisposable
    {
        private int _disposed;

        public void Dispose()
        {
            if (Interlocked.Exchange(ref _disposed, 1) == 0)
            {
                owner.EndDeferredSaves(deviceId);
            }
        }
    }

    private sealed class DeviceRoster
    {
        public bool Baseline { get; set; }

        public int FaceCursor { get; set; }

        public DateTimeOffset? LastFaceSweepUtc { get; set; }

        public bool FaceRefreshAll { get; set; }

        public Dictionary<string, KnownUser> Users { get; set; } = new(StringComparer.Ordinal);
    }
}
