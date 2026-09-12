namespace TrueFaceWindowsPOC;

/// <summary>In-memory undo log for POC-created device users. Never touches gym MySQL.</summary>
public sealed class SessionUndo
{
    private readonly List<string> _createdUserIds = [];
    private readonly object _gate = new();

    public IReadOnlyList<string> CreatedUserIds
    {
        get
        {
            lock (_gate)
            {
                return _createdUserIds.ToArray();
            }
        }
    }

    public void RememberCreate(string deviceUserId)
    {
        lock (_gate)
        {
            if (!_createdUserIds.Contains(deviceUserId, StringComparer.Ordinal))
            {
                _createdUserIds.Add(deviceUserId);
            }
        }
    }

    public void Forget(string deviceUserId)
    {
        lock (_gate)
        {
            _createdUserIds.RemoveAll(id => string.Equals(id, deviceUserId, StringComparison.Ordinal));
        }
    }

    public static HashSet<string> SnapshotIds(IEnumerable<string> ids) =>
        new(ids.Where(id => !string.IsNullOrWhiteSpace(id)), StringComparer.Ordinal);

    public static bool SnapshotsEqual(IReadOnlyCollection<string> before, IReadOnlyCollection<string> after) =>
        SnapshotIds(before).SetEquals(SnapshotIds(after));

    public static IReadOnlyList<string> SymmetricDifference(
        IReadOnlyCollection<string> before,
        IReadOnlyCollection<string> after)
    {
        var a = SnapshotIds(before);
        var b = SnapshotIds(after);
        return a.Except(b).Concat(b.Except(a)).OrderBy(x => x, StringComparer.Ordinal).ToArray();
    }

    public static string NewPocUserId(DateTimeOffset utcNow)
    {
        var stamp = utcNow.UtcDateTime.ToString("yyyyMMdd");
        var suffix = Guid.NewGuid().ToString("N")[..4].ToUpperInvariant();
        return $"POC-{stamp}-{suffix}";
    }
}
