using System.Text.Json;
using System.Text.Json.Nodes;

namespace Gym.Gateway;

/// <summary>
/// The gateway's own view of every member it has seen change, with a change time per field group
/// (name, access = frozen + validity, face, deletion). Device edits and server commands are merged
/// with the same rule the server uses: a change is applied only if it is newer than what is stored
/// for that group; a deletion wins only over older changes. Devices are then brought in line with
/// this state, so devices on one gateway stay in sync even when the server is unreachable.
/// A null change time means "not known" (e.g. users that predate this store); such values are
/// never pushed to other devices.
/// </summary>
public sealed record LocalMember
{
    public required string DeviceUserId { get; init; }
    public string? Name { get; init; }
    /// <summary>"ADMIN" or "USER"; null when not known yet (never written to devices).</summary>
    public string? Authority { get; init; }
    /// <summary>Change time of the profile group: name and authority (the server stamps both together).</summary>
    public DateTimeOffset? NameAt { get; init; }
    public bool Frozen { get; init; }
    /// <summary>yyyy-MM-dd</summary>
    public string? ValidFrom { get; init; }
    public string? ValidTo { get; init; }
    public DateTimeOffset? AccessAt { get; init; }
    /// <summary>sha256 of the desired face image; null with a FaceAt means "no face".</summary>
    public string? FaceSha256 { get; init; }
    public DateTimeOffset? FaceAt { get; init; }
    public bool Deleted { get; init; }
    public DateTimeOffset? DeletedAt { get; init; }

    public DateTimeOffset? LatestChange =>
        new[] { NameAt, AccessAt, FaceAt }.Where(t => t != null).Max();
}

/// <summary>An incoming change to one member, from a device edit or a server command.</summary>
public sealed record MemberChange(string DeviceUserId, DateTimeOffset At, bool FromServer, string Source)
{
    public bool Delete { get; init; }
    public bool SetName { get; init; }
    public string? Name { get; init; }
    public bool SetAuthority { get; init; }
    public string? Authority { get; init; }
    public bool SetFrozen { get; init; }
    public bool Frozen { get; init; }
    public bool SetValidity { get; init; }
    public string? ValidFrom { get; init; }
    public string? ValidTo { get; init; }
    public bool SetFace { get; init; }
    public string? FaceSha256 { get; init; }

    public bool SetsAccess => SetFrozen || SetValidity;
}

public sealed record MergeResult(bool Name, bool Access, bool Face, bool Deleted, IReadOnlyList<string> Ignored)
{
    public static readonly MergeResult None = new(false, false, false, false, []);

    public bool Any => Name || Access || Face || Deleted;
}

/// <summary>A device change waiting to be delivered to the server (face uploaded from the cache first).</summary>
public sealed record PendingReport(string Id, string DeviceId, string DeviceUserId, JsonObject Payload, string? FaceSha256);

public sealed class LocalMemberStore
{
    private readonly string? _directory;
    private readonly object _sync = new();
    private readonly Dictionary<string, byte[]> _memoryFaces = new(StringComparer.Ordinal);
    private State _state;

    /// <param name="directory">Folder for members.json and cached faces; null keeps everything in memory (tests).</param>
    public LocalMemberStore(string? directory)
    {
        _directory = directory;
        _state = new State();
        if (_directory == null)
        {
            return;
        }

        Directory.CreateDirectory(FacesDirectory!);
        var path = StatePath!;
        if (File.Exists(path))
        {
            try
            {
                _state = JsonSerializer.Deserialize<State>(File.ReadAllText(path)) ?? new State();
                _state.Members = new Dictionary<string, LocalMember>(_state.Members, StringComparer.Ordinal);
            }
            catch
            {
                // A corrupt file must not stop door access; start empty (server stays authoritative).
                _state = new State();
            }
        }
    }

    public static string DefaultDirectory() =>
        Path.Combine(Config.GatewayConfigStore.DefaultConfigDirectory(), "members");

    public LocalMember? Find(string deviceUserId)
    {
        lock (_sync)
        {
            return _state.Members.TryGetValue(deviceUserId, out var m) ? m : null;
        }
    }

    public IReadOnlyList<LocalMember> All()
    {
        lock (_sync)
        {
            return _state.Members.Values.ToArray();
        }
    }

    public MergeResult Merge(MemberChange change)
    {
        lock (_sync)
        {
            var existing = _state.Members.GetValueOrDefault(change.DeviceUserId);
            var m = existing ?? new LocalMember { DeviceUserId = change.DeviceUserId };
            var ignored = new List<string>();
            var at = change.At;
            MergeResult result;

            if (change.Delete)
            {
                var latest = m.LatestChange;
                if (m.Deleted && m.DeletedAt >= at)
                {
                    return MergeResult.None;
                }

                if (latest != null && latest >= at)
                {
                    ignored.Add($"deletion from {change.Source} at {Fmt(at)} ignored: member changed at {Fmt(latest)}");
                    return new MergeResult(false, false, false, false, ignored);
                }

                m = m with { Deleted = true, DeletedAt = at };
                result = new MergeResult(false, false, false, true, ignored);
            }
            else if (m.Deleted && m.DeletedAt >= at)
            {
                ignored.Add($"change from {change.Source} at {Fmt(at)} ignored: member was deleted at {Fmt(m.DeletedAt)}");
                return new MergeResult(false, false, false, false, ignored);
            }
            else
            {
                var name = false;
                var access = false;
                var face = false;
                // A losing change whose value the gateway already holds is not "ignored": the devices
                // end up with exactly that value, so the command counts as applied.
                // Name and authority share one change time; each is judged against the time before this change.
                var profileAt = m.NameAt;
                if (change.SetName)
                {
                    var differs = !string.Equals(m.Name, change.Name, StringComparison.Ordinal);
                    if (Wins(profileAt, at, change.FromServer, differs))
                    {
                        m = m with { Name = change.Name, NameAt = at };
                        name = true;
                    }
                    else if (differs)
                    {
                        ignored.Add($"name '{change.Name}' from {change.Source} at {Fmt(at)} ignored: kept name '{m.Name}' from {Fmt(m.NameAt)}{(m.NameAt == at ? " (same time)" : "")}");
                    }
                }

                if (change.SetAuthority && change.Authority != null)
                {
                    var authority = NormalizeAuthority(change.Authority);
                    var differs = !string.Equals(m.Authority, authority, StringComparison.Ordinal);
                    if (Wins(profileAt, at, change.FromServer, differs))
                    {
                        m = m with { Authority = authority, NameAt = at };
                        name = true;
                    }
                    else if (differs)
                    {
                        ignored.Add($"authority {authority} from {change.Source} at {Fmt(at)} ignored: kept {m.Authority ?? "unknown"} from {Fmt(profileAt)}{(profileAt == at ? " (same time)" : "")}");
                    }
                }

                if (change.SetsAccess)
                {
                    var differs = (change.SetFrozen && m.Frozen != change.Frozen)
                                  || (change.SetValidity && (m.ValidFrom != change.ValidFrom || m.ValidTo != change.ValidTo));
                    if (Wins(m.AccessAt, at, change.FromServer, differs))
                    {
                        m = m with
                        {
                            Frozen = change.SetFrozen ? change.Frozen : m.Frozen,
                            ValidFrom = change.SetValidity ? change.ValidFrom : m.ValidFrom,
                            ValidTo = change.SetValidity ? change.ValidTo : m.ValidTo,
                            AccessAt = at
                        };
                        access = true;
                    }
                    else if (differs)
                    {
                        var incoming = (change.SetFrozen ? $"frozen={change.Frozen} " : "")
                                       + (change.SetValidity ? $"{change.ValidFrom}..{change.ValidTo}" : "");
                        ignored.Add($"access ({incoming.Trim()}) from {change.Source} at {Fmt(at)} ignored: kept frozen={m.Frozen} {m.ValidFrom}..{m.ValidTo} from {Fmt(m.AccessAt)}{(m.AccessAt == at ? " (same time)" : "")}");
                    }
                }

                if (change.SetFace)
                {
                    var differs = !string.Equals(m.FaceSha256, change.FaceSha256, StringComparison.Ordinal);
                    if (Wins(m.FaceAt, at, change.FromServer, differs))
                    {
                        m = m with { FaceSha256 = change.FaceSha256, FaceAt = at };
                        face = true;
                    }
                    else if (differs)
                    {
                        ignored.Add($"face {(change.FaceSha256 == null ? "removal" : "change")} from {change.Source} at {Fmt(at)} ignored: kept face {Short(m.FaceSha256)} from {Fmt(m.FaceAt)}{(m.FaceAt == at ? " (same time)" : "")}");
                    }
                }

                if ((name || access || face) && m.Deleted)
                {
                    m = m with { Deleted = false };
                }

                result = new MergeResult(name, access, face, false, ignored);
            }

            if (result.Any || existing == null)
            {
                var oldFace = existing?.FaceSha256;
                _state.Members[m.DeviceUserId] = m;
                Save();
                if (oldFace != null && oldFace != m.FaceSha256)
                {
                    ReleaseFaceIfUnused(oldFace);
                }
            }

            return result;
        }
    }

    // --- face cache ------------------------------------------------------------------------------

    public void PutFace(string sha256, byte[] bytes)
    {
        lock (_sync)
        {
            if (_directory == null)
            {
                _memoryFaces[sha256] = bytes;
                return;
            }

            var path = FacePath(sha256);
            if (!File.Exists(path))
            {
                var tmp = path + ".tmp";
                File.WriteAllBytes(tmp, bytes);
                File.Move(tmp, path, overwrite: true);
            }
        }
    }

    public byte[]? GetFace(string sha256)
    {
        lock (_sync)
        {
            if (_directory == null)
            {
                return _memoryFaces.GetValueOrDefault(sha256);
            }

            var path = FacePath(sha256);
            return File.Exists(path) ? File.ReadAllBytes(path) : null;
        }
    }

    // --- pending reports (device changes not yet handed to the backend outbox) -------------------

    public void AddReport(PendingReport report)
    {
        lock (_sync)
        {
            _state.Reports.Add(report);
            Save();
        }
    }

    public IReadOnlyList<PendingReport> Reports()
    {
        lock (_sync)
        {
            return _state.Reports.ToArray();
        }
    }

    public void CompleteReport(string id)
    {
        lock (_sync)
        {
            var report = _state.Reports.FirstOrDefault(r => r.Id == id);
            if (report == null)
            {
                return;
            }

            _state.Reports.Remove(report);
            Save();
            if (report.FaceSha256 != null)
            {
                ReleaseFaceIfUnused(report.FaceSha256);
            }
        }
    }

    // --- internals -------------------------------------------------------------------------------

    /// <summary>Same rule as the server: strictly newer wins; on a tie only a differing server value wins.</summary>
    private static bool Wins(DateTimeOffset? stored, DateTimeOffset incoming, bool fromServer, bool differs) =>
        stored == null || incoming > stored || (incoming == stored && fromServer && differs);

    public static string NormalizeAuthority(string authority) =>
        string.Equals(authority.Trim(), "ADMIN", StringComparison.OrdinalIgnoreCase) ? "ADMIN" : "USER";

    private static string Fmt(DateTimeOffset? t) => t?.UtcDateTime.ToString("yyyy-MM-dd HH:mm:ss'Z'") ?? "unknown";

    private static string Short(string? sha) => sha == null ? "none" : sha[..Math.Min(8, sha.Length)];

    private void ReleaseFaceIfUnused(string sha)
    {
        if (_state.Members.Values.Any(m => m.FaceSha256 == sha) || _state.Reports.Any(r => r.FaceSha256 == sha))
        {
            return;
        }

        if (_directory == null)
        {
            _memoryFaces.Remove(sha);
            return;
        }

        try
        {
            File.Delete(FacePath(sha));
        }
        catch
        {
            // best effort
        }
    }

    private void Save()
    {
        if (_directory == null)
        {
            return;
        }

        var tmp = StatePath + ".tmp";
        File.WriteAllText(tmp, JsonSerializer.Serialize(_state));
        File.Move(tmp, StatePath!, overwrite: true);
    }

    private string? StatePath => _directory == null ? null : Path.Combine(_directory, "members.json");

    private string? FacesDirectory => _directory == null ? null : Path.Combine(_directory, "faces");

    private string FacePath(string sha) =>
        Path.Combine(FacesDirectory!, string.Concat(sha.Where(char.IsLetterOrDigit)) + ".jpg");

    private sealed class State
    {
        public Dictionary<string, LocalMember> Members { get; set; } = new(StringComparer.Ordinal);

        public List<PendingReport> Reports { get; set; } = [];
    }
}
