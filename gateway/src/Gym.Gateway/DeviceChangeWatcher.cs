using System.Collections.Concurrent;
using System.Diagnostics;
using System.Text.Json.Nodes;
using System.Threading.Channels;
using Gym.Gateway.Adapters;
using Microsoft.Extensions.Logging;

namespace Gym.Gateway;

/// <summary>Applies timestamped member commands from the server through the gateway's local state.</summary>
public interface ILocalMemberSync
{
    /// <summary>Returns null when the command carries no change time (handled the legacy way).</summary>
    Task<DispatchOutcome?> TryApplyAsync(GatewayEnvelope command, byte[]? face, CancellationToken cancellationToken);
}

/// <summary>
/// Keeps every device on this gateway in sync with each other and with the server:
/// <list type="bullet">
///   <item>Detects users created, changed or deleted directly on a device (device alarm triggers an
///         immediate scan; a periodic roster diff catches firmware that sends no events).</item>
///   <item>Merges each change into <see cref="LocalMemberStore"/> (latest change wins per field group)
///         and copies that edit to the other devices, even when the server is unreachable.
///         The first read of a reader only records who is already there. It does not rewrite them,
///         and it does not copy that roster onto another reader.</item>
///   <item>Queues the change for the server (DEVICE_USER_CHANGED; face uploaded from the local cache
///         first). The queue is on disk, so outages and restarts do not lose changes.</item>
///   <item>Applies timestamped server commands the same way; a command older than a local change
///         is skipped and logged.</item>
/// </list>
/// The first scan of a device only records a baseline (existing users reach the server through
/// reconcile). Before writing to a device the gateway re-reads it, so an edit made there that has
/// not been detected yet is merged first rather than overwritten.
/// </summary>
public sealed class DeviceChangeWatcher : ILocalMemberSync
{
    private readonly IReadOnlyDictionary<string, IDeviceAdapter> _adapters;
    private readonly RosterStateStore _roster;
    private readonly DeviceLocks _locks;
    private readonly IFaceTransfer _faces;
    private readonly Func<string, object, Task> _publish;
    private readonly ILogger _log;
    private readonly LocalMemberStore _store;
    private readonly TimeSpan _faceSweepInterval;
    private readonly TimeSpan _newUserFaceWatch;
    private readonly Channel<(string DeviceId, string? UserId)> _triggers =
        Channel.CreateUnbounded<(string, string?)>();
    private readonly SemaphoreSlim _flushGate = new(1, 1);

    /// <summary>More disappearances than this (and than 20% of the roster) in one scan are not reported.</summary>
    private const int MaxUnguardedDeletes = 5;

    /// <summary>Face reads per scan, so a full photo pass does not hold the reader while commands wait.</summary>
    private const int FaceReadsPerScan = 40;

    /// <summary>Pause between face batches. Short enough that a full photo import does not sit idle for the roster poll.</summary>
    private static readonly TimeSpan FaceBatchGap = TimeSpan.FromSeconds(2);

    /// <summary>Face JPEGs uploaded to the server at the same time while flushing reports.</summary>
    private const int ParallelFaceUploads = 4;

    /// <summary>Users a reader missed while it was offline or a write failed. Applied on its next scan.</summary>
    private readonly ConcurrentDictionary<string, HashSet<string>> _catchUp = new(StringComparer.Ordinal);

    public DeviceChangeWatcher(
        IReadOnlyDictionary<string, IDeviceAdapter> adapters,
        RosterStateStore roster,
        DeviceLocks locks,
        IFaceTransfer faces,
        Func<string, object, Task> publish,
        ILogger log,
        TimeSpan? faceSweepInterval = null,
        TimeSpan? newUserFaceWatch = null,
        LocalMemberStore? store = null)
    {
        _adapters = adapters;
        _roster = roster;
        _locks = locks;
        _faces = faces;
        _publish = publish;
        _log = log;
        _store = store ?? new LocalMemberStore(null);
        _faceSweepInterval = faceSweepInterval ?? TimeSpan.FromMinutes(30);
        _newUserFaceWatch = newUserFaceWatch ?? TimeSpan.FromMinutes(10);
    }

    /// <summary>Called from device event callbacks; never blocks.</summary>
    public void Trigger(string deviceId, string? deviceUserId)
    {
        _log.LogDebug("Reader {DeviceId} announced a user change (user={User}); scanning shortly", deviceId, deviceUserId);
        _triggers.Writer.TryWrite((deviceId, deviceUserId));
    }

    public async Task RunAsync(TimeSpan pollInterval, CancellationToken cancellationToken)
    {
        var nextPoll = DateTimeOffset.UtcNow;
        while (!cancellationToken.IsCancellationRequested)
        {
            try
            {
                var faceImportPending = _adapters.Keys.Any(FaceImportInProgress);
                var wait = nextPoll - DateTimeOffset.UtcNow;
                if (faceImportPending && wait > FaceBatchGap)
                {
                    wait = FaceBatchGap;
                }

                var focus = new Dictionary<string, HashSet<string>>(StringComparer.Ordinal);
                if (wait > TimeSpan.Zero)
                {
                    using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
                    timeout.CancelAfter(wait);
                    try
                    {
                        var first = await _triggers.Reader.ReadAsync(timeout.Token).ConfigureAwait(false);
                        // Debounce: enrolment raises several events in a row.
                        await Task.Delay(TimeSpan.FromSeconds(3), cancellationToken).ConfigureAwait(false);
                        AddFocus(focus, first);
                        while (_triggers.Reader.TryRead(out var more))
                        {
                            AddFocus(focus, more);
                        }
                    }
                    catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
                    {
                        // poll interval elapsed
                    }
                }

                var periodic = DateTimeOffset.UtcNow >= nextPoll;
                var scanStarted = DateTimeOffset.UtcNow;
                var deviceIds = periodic
                    ? _adapters.Keys.ToList()
                    : focus.Keys.Concat(_adapters.Keys.Where(FaceImportInProgress))
                        .Distinct(StringComparer.Ordinal).ToList();
                // One reader at a time. The other readers are still checked for name and access edits.
                var faceDevice = NextFaceImportDevice();
                foreach (var deviceId in deviceIds)
                {
                    var sweep = faceDevice != null && deviceId == faceDevice && DueForFaceSweep(deviceId);
                    var focused = focus.TryGetValue(deviceId, out var users);
                    await ScanDeviceAsync(deviceId, users, sweep, cancellationToken, listUsers: periodic || focused)
                        .ConfigureAwait(false);
                }

                await FlushReportsAsync(cancellationToken).ConfigureAwait(false);
                if (periodic)
                {
                    // A slow reader would otherwise be listed back to back and have no time for doors and events.
                    var took = DateTimeOffset.UtcNow - scanStarted;
                    nextPoll = DateTimeOffset.UtcNow + (took * 2 > pollInterval ? took * 2 : pollInterval);
                }
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                break;
            }
            catch (Exception ex)
            {
                _log.LogWarning(ex, "Device change scan failed");
                await Task.Delay(TimeSpan.FromSeconds(5), cancellationToken).ConfigureAwait(false);
            }
        }
    }

    /// <summary>
    /// Scans one device: detects local edits, merges them, brings the device up to date with the
    /// local state, then updates the other devices. Returns the number of reports sent to the server.
    /// </summary>
    public async Task<int> ScanDeviceAsync(
        string deviceId,
        IReadOnlyCollection<string>? faceFocus,
        bool faceSweep,
        CancellationToken cancellationToken,
        bool listUsers = true)
    {
        if (!_adapters.TryGetValue(deviceId, out var adapter))
        {
            return 0;
        }

        var fanOut = new FanOut();
        var gate = _locks.For(deviceId);
        await gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            if (adapter.GetHealth().ConnectionState == "ONLINE")
            {
                using var saves = _roster.DeferSaves(deviceId);
                var hadBaseline = _roster.HasBaseline(deviceId);
                // Listing a large reader takes many seconds, so photo-only batches skip it.
                var listed = !listUsers || ScanLocked(deviceId, adapter, faceFocus, fanOut);
                if (faceSweep && listed && hadBaseline)
                {
                    FaceBatchLocked(deviceId, adapter, fanOut);
                }
            }
            else
            {
                _log.LogDebug("Skipped change scan of {DeviceId}: reader is not online", deviceId);
            }
        }
        finally
        {
            gate.Release();
        }

        await FanOutAsync(deviceId, fanOut, cancellationToken).ConfigureAwait(false);
        return await FlushReportsAsync(cancellationToken).ConfigureAwait(false);
    }

    /// <summary>
    /// Server-requested report (REPORT_DEVICE_USER): reads the user's profile and photo fresh from the
    /// reader and always answers. A difference from what the gateway last saw is handled as a device edit;
    /// otherwise the current state is sent with the time it was last changed, so a stale reader never
    /// overrides a newer server copy.
    /// </summary>
    public async Task<(bool Ok, string? Error)> ReportUserAsync(
        string deviceId, string deviceUserId, CancellationToken cancellationToken)
    {
        if (!_adapters.TryGetValue(deviceId, out var adapter))
        {
            return (false, "Unknown deviceId");
        }

        var fanOut = new FanOut();
        var gate = _locks.For(deviceId);
        await gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            var user = adapter.GetUser(deviceUserId);
            if (user == null)
            {
                return (false, "User not found on device");
            }

            var face = adapter.GetFace(deviceUserId);
            if (!face.Ok)
            {
                return (false, face.Error ?? "face read failed");
            }

            var now = DateTimeOffset.UtcNow;
            var known = _roster.Find(deviceId, deviceUserId);
            var sha = Sha(face.Photo);
            var changed = known == null || known.Diff(user).Any || sha != known.FaceSha256;
            Observe(deviceId, user, known, face, now, fanOut);
            if (!changed)
            {
                if (sha != null)
                {
                    _store.PutFace(sha, face.Photo!);
                }

                var at = PlausibleDeviceTime(face.UpdatedAtUtc, now) ?? known!.FaceAt ?? known.FirstSeenAt;
                QueueReport(deviceId, user, at, deleted: false, isNew: false, ProfileDiff.None,
                    faceChanged: sha != null, sha, initialSample: true);
            }
        }
        finally
        {
            gate.Release();
        }

        await FanOutAsync(deviceId, fanOut, cancellationToken).ConfigureAwait(false);
        await FlushReportsAsync(cancellationToken).ConfigureAwait(false);
        return (true, null);
    }

    public async Task<DispatchOutcome?> TryApplyAsync(
        GatewayEnvelope command, byte[]? face, CancellationToken cancellationToken)
    {
        var deviceId = command.DeviceId;
        var change = ToChange(command, face);
        if (change == null || deviceId == null || !_adapters.TryGetValue(deviceId, out var adapter))
        {
            return null;
        }

        var userId = change.DeviceUserId;
        var fanOut = new FanOut();
        MergeResult result;
        string? error;
        var gate = _locks.For(deviceId);
        await gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            if (_roster.HasBaseline(deviceId))
            {
                DetectUser(deviceId, adapter, userId, readFace: change.SetFace, fanOut);
            }

            result = _store.Merge(change);
            LogIgnored(result, $"server {command.Type}");
            _log.LogDebug(
                "Server {Type} user={User} on {DeviceId}: name={Name} access={Access} face={Face} deleted={Deleted} ignored={Ignored}",
                command.Type, userId, deviceId, result.Name, result.Access, result.Face, result.Deleted, result.Ignored.Count);
            var m = _store.Find(userId);
            error = m == null ? null : ConvergeUser(deviceId, adapter, m, _roster.Find(deviceId, userId));
        }
        finally
        {
            gate.Release();
        }

        if (result.Any)
        {
            fanOut.Add(userId, result.Face);
        }

        await FanOutAsync(deviceId, fanOut, cancellationToken).ConfigureAwait(false);
        await FlushReportsAsync(cancellationToken).ConfigureAwait(false);

        if (error != null)
        {
            return DispatchOutcome.SyncFail(error);
        }

        if (!result.Any && result.Ignored.Count > 0)
        {
            return DispatchOutcome.SyncOk(new { ok = true, skipped = true, reason = string.Join("; ", result.Ignored) });
        }

        return command.Type == "UPSERT_FACE"
            ? DispatchOutcome.SyncOk(new { ok = true, faceVersion = CommandDispatcher.Int(command.Payload, "faceVersion") })
            : DispatchOutcome.SyncOk();
    }

    /// <summary>Hands queued device changes to the backend outbox (in order); stops at the first transient failure.</summary>
    public async Task<int> FlushReportsAsync(CancellationToken cancellationToken)
    {
        await _flushGate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            var sent = 0;
            var reports = _store.Reports();
            var paused = false;
            for (var windowStart = 0; windowStart < reports.Count && !paused; windowStart += ParallelFaceUploads)
            {
                // Photos in one window upload together; reports are still published strictly in queue order.
                var window = reports.Skip(windowStart).Take(ParallelFaceUploads).ToArray();
                var uploads = await Task.WhenAll(window.Select(r => UploadReportFaceAsync(r, cancellationToken)))
                    .ConfigureAwait(false);
                for (var i = 0; i < window.Length; i++)
                {
                    var report = window[i];
                    var (upload, missing) = uploads[i];
                    var payload = (JsonObject)report.Payload.DeepClone();
                    if (upload != null)
                    {
                        if (upload.Rejected)
                        {
                            _log.LogWarning("Device {DeviceId} user {User}: face image {Reason}; syncing the other changes "
                                + "without it", report.DeviceId, report.DeviceUserId,
                                missing ? "missing from the local cache" : "rejected by the server");
                            payload["faceChanged"] = false;
                            payload["faceRemoved"] = false;
                            payload["faceSha256"] = null;
                        }
                        else if (upload.UploadId == null)
                        {
                            // Server unreachable: keep this and later reports (order matters), retry next scan.
                            _log.LogInformation("Paused sending device changes: server unreachable; {Count} report(s) kept for the next scan",
                                reports.Count - sent);
                            paused = true;
                            break;
                        }
                        else
                        {
                            payload["faceUploadId"] = upload.UploadId;
                        }
                    }

                    await PublishReportAsync(report, payload).ConfigureAwait(false);
                    sent++;
                }
            }

            if (sent > 0)
            {
                var left = _store.Reports().Count;
                _log.LogInformation(left == 0
                    ? "Server sync: sent {Sent} change(s). Nothing left in the queue."
                    : "Server sync: sent {Sent} change(s). {Left} change(s) still queued for the server.",
                    sent, left);
            }

            return sent;
        }
        finally
        {
            _flushGate.Release();
        }
    }

    private async Task<(FaceUpload? Upload, bool Missing)> UploadReportFaceAsync(
        PendingReport report, CancellationToken cancellationToken)
    {
        if (report.FaceSha256 == null)
        {
            return (null, false);
        }

        var bytes = _store.GetFace(report.FaceSha256);
        if (bytes == null)
        {
            return (new FaceUpload(null, Rejected: true), true);
        }

        var timer = Stopwatch.StartNew();
        var upload = await _faces.UploadFaceAsync(bytes, cancellationToken).ConfigureAwait(false);
        _log.LogDebug("Face upload for {DeviceId} user {User}: {Kb} KB in {ElapsedMs} ms (ok={Ok})",
            report.DeviceId, report.DeviceUserId, bytes.Length / 1024, (long)timer.Elapsed.TotalMilliseconds,
            upload.UploadId != null);
        return (upload, false);
    }

    private async Task PublishReportAsync(PendingReport report, JsonObject payload)
    {
        await _publish(report.DeviceId, payload).ConfigureAwait(false);
        _store.CompleteReport(report.Id);
        _log.LogInformation(
            "Reported reader change to server: device={DeviceId} user={User} new={IsNew} deleted={Deleted} "
            + "name={Name} access={Access} face={Face} changedAt={ChangedAt}",
            report.DeviceId, report.DeviceUserId, Flag(payload, "isNew"), Flag(payload, "deleted"),
            Flag(payload, "nameChanged"), Flag(payload, "frozenChanged") || Flag(payload, "validityChanged"),
            Flag(payload, "faceChanged") || Flag(payload, "faceRemoved"), payload["deviceChangedAt"]?.ToString());
    }

    private static bool Flag(JsonObject payload, string name) =>
        payload[name] is JsonValue value && value.TryGetValue<bool>(out var flag) && flag;

    // --- scanning --------------------------------------------------------------------------------

    /// <summary>
    /// Lists the reader's users and handles name, access and deletion edits, plus photos of users that were
    /// edited, are new, or were just enrolled. Returns false when the list could not be trusted.
    /// </summary>
    private bool ScanLocked(string deviceId, IDeviceAdapter adapter, IReadOnlyCollection<string>? faceFocus,
        FanOut fanOut)
    {
        var baseline = _roster.HasBaseline(deviceId);
        var users = adapter.ListUsers();
        var now = DateTimeOffset.UtcNow;
        if (users.Count == 0 && (baseline || OtherReadersOrMembersKnown(deviceId)))
        {
            _log.LogWarning(
                "Device {DeviceId}: reader returned no users. Treated as a failed read: nobody is deleted and photo import waits; the next scan retries.",
                deviceId);
            return false;
        }

        if (baseline)
        {
            var knownCount = _roster.All(deviceId).Count;
            var missing = knownCount - users.Count;
            if (missing > Math.Max(MaxUnguardedDeletes, knownCount / 5))
            {
                _log.LogWarning(
                    "Device {DeviceId}: reader listed {Listed} of {Known} users. This scan is incomplete, so face import waits and nobody is deleted. Leave the gateway running; the next scan retries.",
                    deviceId, users.Count, knownCount);
                return false;
            }
        }

        foreach (var user in users)
        {
            var known = _roster.Find(deviceId, user.DeviceUserId);
            if (!baseline)
            {
                // The first pass records names and dates only. Photos follow in short batches.
                _roster.Upsert(deviceId, KnownUser.From(user, known));
                continue;
            }

            var awaitingFace = known is { FaceSha256: null, DeviceCreated: true }
                               && now - known.FirstSeenAt < _newUserFaceWatch;
            var focused = faceFocus?.Contains(user.DeviceUserId) ?? false;
            var face = focused || awaitingFace || known == null ? adapter.GetFace(user.DeviceUserId) : null;
            Observe(deviceId, user, known, face, now, fanOut);
        }

        if (!baseline)
        {
            _roster.MarkBaseline(deviceId);
            _log.LogInformation("Recorded roster baseline for {DeviceId}: {Count} user(s)", deviceId, users.Count);
            return true;
        }

        DetectDeletions(deviceId, users, now, fanOut);
        CatchUpLocked(deviceId, adapter);
        return true;
    }

    private bool OtherReadersOrMembersKnown(string deviceId) =>
        _store.All().Count > 0
        || _adapters.Keys.Any(id => !string.Equals(id, deviceId, StringComparison.Ordinal) && _roster.All(id).Count > 0);

    /// <summary>
    /// Reads the next batch of photos the gateway has never seen (or every photo during a Sync Now refresh).
    /// Works from the users already recorded for this reader, so a batch does not list the whole reader again.
    /// </summary>
    private void FaceBatchLocked(string deviceId, IDeviceAdapter adapter, FanOut fanOut)
    {
        var timer = Stopwatch.StartNew();
        var now = DateTimeOffset.UtcNow;
        var refreshAll = _roster.FaceRefreshRequested(deviceId);
        var known = _roster.All(deviceId).OrderBy(u => u.DeviceUserId, StringComparer.Ordinal).ToList();
        var start = _roster.FaceCursor(deviceId);
        if (start >= known.Count)
        {
            start = 0;
        }

        var faceReads = 0;
        var photosMissing = 0;
        var resumeAt = -1;
        for (var position = start; position < known.Count; position++)
        {
            var user = known[position];
            // A stored hash or an earlier read means the photo is already known. Later edits arrive as reader events.
            if (!refreshAll && user is not { FaceSha256: null, FaceCheckedAt: null })
            {
                continue;
            }

            photosMissing++;
            if (faceReads >= FaceReadsPerScan)
            {
                if (resumeAt < 0)
                {
                    resumeAt = position;
                }

                continue;
            }

            faceReads++;
            var face = adapter.GetFace(user.DeviceUserId);
            // No photo where one was recorded may mean the user was deleted; the next roster scan decides that.
            if (face is { Ok: true, Photo: null } && user.FaceSha256 != null && adapter.GetUser(user.DeviceUserId) == null)
            {
                continue;
            }

            Observe(deviceId, SnapshotOf(user), user, face, now, fanOut);
        }

        var moreFaces = resumeAt >= 0;
        if (moreFaces)
        {
            _roster.SetFaceCursor(deviceId, resumeAt);
        }
        else
        {
            _roster.CompleteFaceSweep(deviceId, now);
        }

        if (faceReads == 0 && start == 0)
        {
            return;
        }

        if (faceReads > 0)
        {
            _log.LogInformation(
                "Face batch timing {DeviceId}: {Read} photo read(s) in {ElapsedMs} ms (about {AvgMs} ms each)",
                deviceId, faceReads, (long)timer.Elapsed.TotalMilliseconds, (long)(timer.Elapsed.TotalMilliseconds / faceReads));
        }

        var leftOnReader = Math.Max(0, photosMissing - faceReads);
        var otherReaders = _adapters.Keys.Count(id => !string.Equals(id, deviceId, StringComparison.Ordinal)
                                                      && DueForFaceSweep(id) && _roster.FaceCursor(id) == 0);
        if (moreFaces)
        {
            _log.LogInformation(
                "Face {Mode} {DeviceId}: read {Read} photo(s), {Left} left to read on this reader. {Others} other reader(s) wait until this one finishes.",
                refreshAll ? "refresh" : "import", deviceId, faceReads, leftOnReader, otherReaders);
        }
        else if (otherReaders > 0)
        {
            _log.LogInformation(
                "Face import {DeviceId} finished ({Total} users). {Others} other reader(s) left to import.",
                deviceId, known.Count, otherReaders);
        }
        else
        {
            _log.LogInformation(
                "Face import finished for every reader. Last reader was {DeviceId} ({Total} users).",
                deviceId, known.Count);
        }
    }

    private static DeviceUserSnapshot SnapshotOf(KnownUser user) =>
        new(user.DeviceUserId, user.Name, user.Frozen, KnownUser.ParseDate(user.ValidFrom),
            KnownUser.ParseDate(user.ValidTo), user.Authority);

    private void DetectDeletions(string deviceId, IReadOnlyList<DeviceUserSnapshot> users, DateTimeOffset now,
        FanOut fanOut)
    {
        if (users.Count > 0)
        {
            var present = users.Select(u => u.DeviceUserId).ToHashSet(StringComparer.Ordinal);
            var known = _roster.All(deviceId);
            var gone = known.Where(k => !present.Contains(k.DeviceUserId)).ToList();
            if (gone.Count > Math.Max(MaxUnguardedDeletes, known.Count / 5))
            {
                _log.LogWarning(
                    "Device {DeviceId}: {Gone} of {Known} users disappeared in one scan; not reporting "
                    + "them as deletions (possible device reset or partial read). Delete members in the app "
                    + "if this was intended.", deviceId, gone.Count, known.Count);
            }
            else
            {
                foreach (var g in gone)
                {
                    var result = HandleDetected(deviceId,
                        new DeviceUserSnapshot(g.DeviceUserId, g.Name, g.Frozen), g, null, null,
                        ProfileDiff.None, faceChanged: false, deleted: true, now);
                    if (result.Any)
                    {
                        fanOut.Add(g.DeviceUserId, false);
                    }
                }
            }
        }
    }

    /// <summary>
    /// Writes only the users this reader missed. The first baseline does not call this, and a scan
    /// does not walk the whole local store.
    /// </summary>
    private void CatchUpLocked(string deviceId, IDeviceAdapter adapter)
    {
        if (!_catchUp.TryGetValue(deviceId, out var pending))
        {
            return;
        }

        List<string> ids;
        lock (pending)
        {
            if (pending.Count == 0)
            {
                return;
            }

            ids = pending.ToList();
        }

        foreach (var id in ids)
        {
            var member = _store.Find(id);
            if (member == null)
            {
                lock (pending)
                {
                    pending.Remove(id);
                }

                continue;
            }

            var error = ConvergeUser(deviceId, adapter, member, _roster.Find(deviceId, id));
            if (error == null)
            {
                lock (pending)
                {
                    pending.Remove(id);
                }
            }
            else
            {
                _log.LogWarning("Could not update {User} on {DeviceId}: {Error} (will retry)", id, deviceId, error);
            }
        }
    }

    private void RememberCatchUp(string deviceId, IEnumerable<string> userIds)
    {
        var pending = _catchUp.GetOrAdd(deviceId, _ => new HashSet<string>(StringComparer.Ordinal));
        lock (pending)
        {
            foreach (var id in userIds.Where(id => !string.IsNullOrWhiteSpace(id)))
            {
                pending.Add(id);
            }
        }
    }

    /// <summary>Re-reads one user before the gateway writes it, so an undetected local edit is merged first.</summary>
    private void DetectUser(string deviceId, IDeviceAdapter adapter, string userId, bool readFace, FanOut fanOut)
    {
        var user = adapter.GetUser(userId);
        if (user == null)
        {
            // A single missing read is not trusted as a deletion; the full scan decides that.
            return;
        }

        var face = readFace ? adapter.GetFace(userId) : null;
        Observe(deviceId, user, _roster.Find(deviceId, userId), face, DateTimeOffset.UtcNow, fanOut);
    }

    private void Observe(string deviceId, DeviceUserSnapshot user, KnownUser? known, DeviceFaceRead? face,
        DateTimeOffset now, FanOut fanOut)
    {
        var diff = known == null ? ProfileDiff.All : known.Diff(user);
        var faceSha = known?.FaceSha256;
        var faceChanged = false;
        if (face is { Ok: true })
        {
            faceSha = Sha(face.Photo);
            faceChanged = known == null ? faceSha != null : faceSha != known.FaceSha256;
        }

        if (known == null || diff.Any || faceChanged)
        {
            var firstFaceSample = known is { FaceCheckedAt: null, FaceSha256: null } && faceChanged && !diff.Any;
            var result = HandleDetected(deviceId, user, known, face, faceSha, diff, faceChanged, deleted: false, now,
                firstFaceSample);
            // The first time a photo is read off a user who was already on the reader is a sample,
            // not an edit, so it is not copied onto the other reader.
            if (result.Any && !firstFaceSample)
            {
                fanOut.Add(user.DeviceUserId, result.Face);
            }
        }
        else if (face is { Ok: true })
        {
            _roster.Upsert(deviceId, known with { FaceCheckedAt = now });
        }
    }

    /// <summary>A device edit: merge into local state, record what the device holds, queue the report.</summary>
    private MergeResult HandleDetected(string deviceId, DeviceUserSnapshot user, KnownUser? known,
        DeviceFaceRead? face, string? faceSha, ProfileDiff diff, bool faceChanged, bool deleted, DateTimeOffset now,
        bool initialSample = false)
    {
        var id = user.DeviceUserId;
        var isNew = !deleted && known == null && _store.Find(id) == null
                    && !_adapters.Keys.Any(d => _roster.Find(d, id) != null);
        // Device face timestamps are trusted when plausible; otherwise the time the gateway saw the change.
        var at = faceChanged ? PlausibleDeviceTime(face?.UpdatedAtUtc, now) ?? now : now;
        if (faceChanged && faceSha != null && face?.Photo != null)
        {
            _store.PutFace(faceSha, face.Photo);
        }

        var result = _store.Merge(new MemberChange(id, at, FromServer: false, Source: "device " + deviceId)
        {
            Delete = deleted,
            SetName = diff.Name,
            Name = user.Name,
            SetFrozen = diff.Frozen,
            Frozen = user.Frozen,
            SetValidity = diff.Validity,
            ValidFrom = KnownUser.Date(user.ValidFrom),
            ValidTo = KnownUser.Date(user.ValidTo),
            SetFace = faceChanged,
            FaceSha256 = faceSha
        });
        LogIgnored(result, "device " + deviceId);

        if (deleted)
        {
            _roster.Remove(deviceId, id);
        }
        else
        {
            // Version stamps only for accepted groups: ignored ones get rewritten from local state.
            _roster.Upsert(deviceId, KnownUser.From(user, known) with
            {
                FaceSha256 = faceSha,
                FaceCheckedAt = face is { Ok: true } ? now : known?.FaceCheckedAt,
                DeviceCreated = isNew || (known?.DeviceCreated ?? false),
                NameAt = result.Name ? at : known?.NameAt,
                AccessAt = result.Access ? at : known?.AccessAt,
                FaceAt = result.Face ? at : known?.FaceAt
            });
        }

        QueueReport(deviceId, user, at, deleted, isNew, diff, faceChanged, faceSha, initialSample);
        _log.LogInformation(
            "Device {DeviceId} user {User} changed locally (new={New} deleted={Deleted} name={Name} "
            + "frozen={Frozen} validity={Validity} face={Face}; applied locally: {Applied})",
            deviceId, id, isNew, deleted, diff.Name, diff.Frozen, diff.Validity, faceChanged, result.Any);
        return result;
    }

    // --- bringing devices up to date --------------------------------------------------------------

    /// <summary>
    /// Writes to one device whatever it is missing from the local state. Returns an error to retry
    /// later, or null. Caller holds the device lock.
    /// </summary>
    private string? ConvergeUser(string deviceId, IDeviceAdapter adapter, LocalMember m, KnownUser? k)
    {
        var id = m.DeviceUserId;
        var now = DateTimeOffset.UtcNow;
        try
        {
            if (m.Deleted)
            {
                if (k == null)
                {
                    return null;
                }

                var removed = adapter.DeleteUser(id);
                if (!removed.Ok)
                {
                    return removed.Error ?? "delete failed";
                }

                _roster.Remove(deviceId, id);
                _log.LogInformation("Removed {User} from {DeviceId} (deleted at {At})", id, deviceId, m.DeletedAt);
                return null;
            }

            if (k == null)
            {
                if (m.NameAt == null && m.AccessAt == null)
                {
                    return m.FaceAt != null ? "user is not on this device yet" : null;
                }

                // Unknown access is created disabled: never grant entry the server has not decided.
                var accessKnown = m.AccessAt != null;
                var created = adapter.CreateUser(new DeviceUserMutation(
                    id,
                    m.Name ?? id,
                    accessKnown && !m.Frozen,
                    accessKnown ? KnownUser.ParseDate(m.ValidFrom) : null,
                    accessKnown ? KnownUser.ParseDate(m.ValidTo) : null));
                if (!created.Ok)
                {
                    return created.Error ?? "create failed";
                }

                var snapshot = adapter.GetUser(id) ?? new DeviceUserSnapshot(id, m.Name ?? id, !(accessKnown && !m.Frozen));
                k = KnownUser.From(snapshot, null) with { NameAt = m.NameAt, AccessAt = m.AccessAt };
                _roster.Upsert(deviceId, k);
                _log.LogInformation("Created {User} on {DeviceId} from local state", id, deviceId);
            }
            else
            {
                var needName = m.NameAt != null && k.NameAt != m.NameAt;
                var needAccess = m.AccessAt != null && k.AccessAt != m.AccessAt;
                if (needName || needAccess)
                {
                    var nameDiffers = needName && !string.Equals(k.Name ?? "", m.Name ?? "", StringComparison.Ordinal);
                    var accessDiffers = needAccess
                                        && (k.Frozen != m.Frozen
                                            || (m.ValidFrom != null && k.ValidFrom != m.ValidFrom)
                                            || (m.ValidTo != null && k.ValidTo != m.ValidTo));
                    if (nameDiffers || accessDiffers)
                    {
                        var updated = adapter.UpdateUser(new DeviceUserMutation(
                            id,
                            nameDiffers ? m.Name : null,
                            accessDiffers ? !m.Frozen : null,
                            accessDiffers ? KnownUser.ParseDate(m.ValidFrom) : null,
                            accessDiffers ? KnownUser.ParseDate(m.ValidTo) : null));
                        if (!updated.Ok)
                        {
                            return updated.Error ?? "update failed";
                        }

                        var snapshot = adapter.GetUser(id);
                        k = snapshot == null ? k : KnownUser.From(snapshot, k);
                        _log.LogInformation("Updated {User} on {DeviceId} from local state (name={Name} access={Access})",
                            id, deviceId, nameDiffers, accessDiffers);
                    }

                    k = k with
                    {
                        NameAt = needName ? m.NameAt : k.NameAt,
                        AccessAt = needAccess ? m.AccessAt : k.AccessAt
                    };
                    _roster.Upsert(deviceId, k);
                }
            }

            if (m.FaceAt != null && k.FaceAt != m.FaceAt)
            {
                if (m.FaceSha256 == null)
                {
                    var hasFace = k.FaceSha256 != null
                                  || (k.FaceCheckedAt == null && adapter.GetFace(id) is { Ok: true, Photo: not null });
                    if (hasFace)
                    {
                        var deleted = adapter.DeleteFace(id);
                        if (!deleted.Ok)
                        {
                            return deleted.Error ?? "face delete failed";
                        }
                    }

                    k = k with { FaceSha256 = null, FaceAt = m.FaceAt, FaceCheckedAt = now };
                }
                else if (k.FaceSha256 == m.FaceSha256)
                {
                    k = k with { FaceAt = m.FaceAt };
                }
                else
                {
                    var bytes = _store.GetFace(m.FaceSha256);
                    if (bytes == null)
                    {
                        return "face image is not in the local cache";
                    }

                    var written = adapter.UpsertFace(id, bytes);
                    if (!written.Ok)
                    {
                        return written.Error ?? "face write failed";
                    }

                    // The device may re-encode the image; remember the bytes it returns, not ours.
                    var read = adapter.GetFace(id);
                    var sha = read is { Ok: true, Photo: not null } ? FaceHash.Sha256Hex(read.Photo) : m.FaceSha256;
                    k = k with { FaceSha256 = sha, FaceAt = m.FaceAt, FaceCheckedAt = now };
                    _log.LogInformation("Wrote face for {User} on {DeviceId} from local state", id, deviceId);
                }

                _roster.Upsert(deviceId, k);
            }

            return null;
        }
        catch (Exception ex)
        {
            return ex.Message;
        }
    }

    /// <summary>Brings the other devices up to date for users whose local state just changed.</summary>
    private async Task FanOutAsync(string sourceDeviceId, FanOut fanOut, CancellationToken cancellationToken)
    {
        if (fanOut.Users.Count == 0)
        {
            return;
        }

        foreach (var (deviceId, adapter) in _adapters)
        {
            if (deviceId == sourceDeviceId)
            {
                continue;
            }

            var further = new FanOut();
            var gate = _locks.For(deviceId);
            await gate.WaitAsync(cancellationToken).ConfigureAwait(false);
            try
            {
                if (adapter.GetHealth().ConnectionState != "ONLINE" || !_roster.HasBaseline(deviceId))
                {
                    RememberCatchUp(deviceId, fanOut.Users);
                    continue;
                }

                using var saves = _roster.DeferSaves(deviceId);
                foreach (var userId in fanOut.Users)
                {
                    DetectUser(deviceId, adapter, userId, fanOut.Faces.Contains(userId), further);
                    var m = _store.Find(userId);
                    var error = m == null ? null : ConvergeUser(deviceId, adapter, m, _roster.Find(deviceId, userId));
                    if (error != null)
                    {
                        RememberCatchUp(deviceId, [userId]);
                        _log.LogWarning("Could not update {User} on {DeviceId}: {Error} (will retry)", userId, deviceId, error);
                    }
                }
            }
            finally
            {
                gate.Release();
            }

            // A concurrent edit found on this device: let the scan loop spread it.
            foreach (var userId in further.Users)
            {
                foreach (var other in _adapters.Keys.Where(d => d != deviceId))
                {
                    Trigger(other, userId);
                }
            }
        }
    }

    // --- helpers ---------------------------------------------------------------------------------

    private MemberChange? ToChange(GatewayEnvelope command, byte[]? face)
    {
        var p = command.Payload;
        var userId = CommandDispatcher.Text(p, "deviceUserId");
        if (string.IsNullOrWhiteSpace(userId))
        {
            return null;
        }

        switch (command.Type)
        {
            case "CREATE_USER":
            case "UPDATE_USER":
            case "UPDATE_ACCESS_POLICY":
            {
                var at = CommandDispatcher.Instant(p, "nameChangedAt");
                return at == null
                    ? null
                    : new MemberChange(userId, at.Value, true, "server")
                    {
                        SetName = CommandDispatcher.Text(p, "name") != null,
                        Name = CommandDispatcher.Text(p, "name")
                    };
            }
            case "UPDATE_VALIDITY":
            case "ENABLE_USER":
            case "DISABLE_USER":
            {
                var at = CommandDispatcher.Instant(p, "accessChangedAt");
                if (at == null)
                {
                    return null;
                }

                var from = KnownUser.Date(CommandDispatcher.Instant(p, "validFrom"));
                var to = KnownUser.Date(CommandDispatcher.Instant(p, "validTo"));
                var frozen = command.Type switch
                {
                    "DISABLE_USER" => true,
                    "ENABLE_USER" => false,
                    _ => !(CommandDispatcher.Bool(p, "enabled") ?? true)
                };
                return new MemberChange(userId, at.Value, true, "server")
                {
                    SetFrozen = true,
                    Frozen = frozen,
                    SetValidity = from != null && to != null,
                    ValidFrom = from,
                    ValidTo = to
                };
            }
            case "UPSERT_FACE":
            case "DELETE_FACE":
            {
                var at = CommandDispatcher.Instant(p, "faceChangedAt");
                if (at == null || (command.Type == "UPSERT_FACE" && face == null))
                {
                    return null;
                }

                string? sha = null;
                if (face != null)
                {
                    sha = FaceHash.Sha256Hex(face);
                    _store.PutFace(sha, face);
                }

                return new MemberChange(userId, at.Value, true, "server") { SetFace = true, FaceSha256 = sha };
            }
            case "REMOVE_USER":
            {
                var at = CommandDispatcher.Instant(p, "deletedAt");
                return at == null ? null : new MemberChange(userId, at.Value, true, "server") { Delete = true };
            }
            default:
                return null;
        }
    }

    private void QueueReport(string deviceId, DeviceUserSnapshot user, DateTimeOffset at, bool deleted, bool isNew,
        ProfileDiff diff, bool faceChanged, string? faceSha, bool initialSample = false)
    {
        var payload = new JsonObject
        {
            ["deviceUserId"] = user.DeviceUserId,
            ["name"] = user.Name,
            ["frozen"] = user.Frozen,
            ["authority"] = user.Authority ?? "USER",
            ["validFrom"] = Iso(user.ValidFrom),
            ["validTo"] = Iso(user.ValidTo),
            ["deviceChangedAt"] = Iso(at),
            ["deleted"] = deleted,
            ["isNew"] = isNew,
            ["profileChanged"] = diff.Any,
            ["nameChanged"] = diff.Name,
            ["frozenChanged"] = diff.Frozen,
            ["validityChanged"] = diff.Validity,
            ["authorityChanged"] = diff.Authority,
            ["faceChanged"] = faceChanged,
            ["faceRemoved"] = faceChanged && faceSha == null,
            ["faceSha256"] = faceChanged ? faceSha : null,
            ["initialSample"] = initialSample
        };
        _store.AddReport(new PendingReport(Guid.NewGuid().ToString("N"), deviceId, user.DeviceUserId, payload,
            faceChanged ? faceSha : null));
    }

    private void LogIgnored(MergeResult result, string source)
    {
        foreach (var reason in result.Ignored)
        {
            _log.LogWarning("Latest change wins: {Reason}", reason);
        }
    }

    private static string? Iso(DateTimeOffset? value) =>
        value?.UtcDateTime.ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'");

    private static string? Sha(byte[]? photo) => photo == null ? null : FaceHash.Sha256Hex(photo);

    private static DateTimeOffset? PlausibleDeviceTime(DateTimeOffset? deviceTime, DateTimeOffset now) =>
        deviceTime is { } t && t.Year >= 2000 && t <= now.AddMinutes(5) ? t : null;

    /// <summary>The reader whose photos are imported now. Others wait so two readers are not read at once.</summary>
    private string? NextFaceImportDevice()
    {
        return _adapters.Keys
            .Where(DueForFaceSweep)
            .OrderBy(id => _roster.FaceCursor(id) > 0 ? 0 : 1)
            .ThenBy(id => id, StringComparer.Ordinal)
            .FirstOrDefault();
    }

    private bool FaceImportInProgress(string deviceId) =>
        _roster.FaceCursor(deviceId) > 0 || _roster.FaceRefreshRequested(deviceId);

    private bool DueForFaceSweep(string deviceId)
    {
        if (FaceImportInProgress(deviceId))
        {
            return true;
        }

        var last = _roster.LastFaceSweepUtc(deviceId);
        return last == null || DateTimeOffset.UtcNow - last.Value >= _faceSweepInterval;
    }

    private static void AddFocus(Dictionary<string, HashSet<string>> focus, (string DeviceId, string? UserId) trigger)
    {
        if (!focus.TryGetValue(trigger.DeviceId, out var set))
        {
            set = new HashSet<string>(StringComparer.Ordinal);
            focus[trigger.DeviceId] = set;
        }

        if (!string.IsNullOrWhiteSpace(trigger.UserId))
        {
            set.Add(trigger.UserId);
        }
    }

    private sealed class FanOut
    {
        public HashSet<string> Users { get; } = new(StringComparer.Ordinal);

        public HashSet<string> Faces { get; } = new(StringComparer.Ordinal);

        public void Add(string userId, bool face)
        {
            Users.Add(userId);
            if (face)
            {
                Faces.Add(userId);
            }
        }
    }
}
