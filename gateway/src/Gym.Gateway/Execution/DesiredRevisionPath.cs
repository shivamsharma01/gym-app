using System.Diagnostics;
using System.Security.Cryptography;
using Gym.Gateway;
using Gym.Gateway.Adapters;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;

namespace Gym.Gateway.Execution;

/// <summary>
/// Pulls one reader's desired member, writes it through <see cref="ReaderWorker"/>, and posts the
/// acknowledgement only after the user and face read back. An occupied id is reported as that id;
/// a record other than the expected one is reported as what the reader shows, and not written.
/// A trusted list uploads reader-created ids and the whole list with its announced total. It does
/// not copy anyone to another reader.
/// </summary>
/// <remarks>
/// Desired state is applied only after the server has processed the reader's latest observation:
/// after a start or a reconnect, and after every observation upload, the path waits until the
/// server confirms those messages were processed. A failed revision does not stop newer ones: it is
/// retried with backoff, and dropped only when the server no longer desires it. One pass, or one
/// scan's read of the reader, runs at a time per reader. Each scan is numbered in the reader's
/// journal before the reader is read, and every upload, read-back and occupant report carries the
/// number, so the server can tell an observation taken before a verified write from one taken after.
/// </remarks>
public sealed class DesiredRevisionPath
{
    private const int MaxPasses = 5;
    private const int MaxPages = 50;

    private readonly string _deviceId;
    private readonly string _readerLabel;
    private readonly ReaderWorker _worker;
    private readonly IReaderAdapter _reader;
    private readonly IDesiredStateClient _client;
    private readonly IReaderObservationUpload? _observations;
    private readonly ReaderScanMemory _memory;
    private readonly DesiredPathOptions _options;
    private readonly ILogger _log;
    private readonly SemaphoreSlim _pass = new(1, 1);
    private readonly object _gateLock = new();
    private ObservationGate _gate;
    private IReadOnlyList<IReadOnlyList<string>> _fence = [];
    private DateTimeOffset _fencedAt;
    private DateTimeOffset _nextFenceWarn = DateTimeOffset.MinValue;
    private DateTimeOffset _nextObserve = DateTimeOffset.MinValue;
    private DateTimeOffset _nextFailureLog = DateTimeOffset.MinValue;
    private long _observation;
    private ObservationCheck _lastCheck;
    private bool _checkFailed;
    private long _applyFrom;
    private long _applyTarget;
    private long _applyAt;
    private int _written;

    /// <summary>One plain sentence for the minute status line.</summary>
    public string StatusText()
    {
        lock (_gateLock)
        {
            return _readerLabel + ": " + DescribeLocked();
        }
    }

    private string DescribeLocked()
    {
        var waited = _fencedAt == default ? 0 : Math.Max(0, (int)(_worker.Now - _fencedAt).TotalMinutes);
        if (_checkFailed && _gate == ObservationGate.Fenced)
        {
            return "could not ask the server whether the reader list is saved. Member sync stays paused and this is tried again. Nothing to do in the app.";
        }

        return _gate switch
        {
            ObservationGate.Observing =>
                "reading the reader and sending its people to the server. Member sync stays paused until the server has saved that list.",
            ObservationGate.Fenced when _lastCheck.Asked > 0 =>
                $"member sync is paused. The server has saved {_lastCheck.Saved} of {_lastCheck.Asked} updates from the reader list ({waited} min so far). Nothing to do in the app.",
            ObservationGate.Fenced =>
                $"member sync is paused. Waiting for the server to finish saving the reader list ({waited} min so far). Nothing to do in the app.",
            ObservationGate.Open when _applyTarget > _applyFrom =>
                $"writing members onto the reader. {_written} written, now at revision {_applyAt} of {_applyTarget}.",
            ObservationGate.Open =>
                "the reader list is saved. Checking whether any members still need to be written.",
            _ => "member sync is paused. The reader list has not been sent to the server yet."
        };
    }

    private void NoteCheck(ObservationCheck check)
    {
        var changed = false;
        lock (_gateLock)
        {
            _checkFailed = false;
            changed = check.Processed != _lastCheck.Processed
                || check.Saved != _lastCheck.Saved
                || check.Waiting != _lastCheck.Waiting;
            _lastCheck = check;
        }

        if (!changed || check.Asked == 0)
        {
            return;
        }

        if (check.Processed)
        {
            _log.LogInformation(
                "Reader {Reader}: the server has saved the reader list ({Saved} updates). Writing members onto the reader now.",
                _readerLabel, check.Saved);
            return;
        }

        _log.LogInformation(
            "Reader {Reader}: the server has saved {Saved} of {Asked} updates from the reader list. Member sync stays paused until all of them are saved. Nothing to do in the app.",
            _readerLabel, check.Saved, check.Asked);
    }

    private void NoteFailure(Exception ex)
    {
        lock (_gateLock)
        {
            _checkFailed = true;
        }

        if (_worker.Now < _nextFailureLog)
        {
            return;
        }

        _nextFailureLog = _worker.Now + TimeSpan.FromMinutes(1);
        _log.LogWarning(
            ex,
            "Reader {Reader}: could not ask the server whether the reader list is saved. Member sync stays paused and this is tried again. Nothing to do in the app.",
            _readerLabel);
    }

    private void NoteWriting(long from, long target, int written, long at)
    {
        lock (_gateLock)
        {
            _applyFrom = from;
            _applyTarget = target;
            _applyAt = at;
            _written = written;
        }
    }

    public DesiredRevisionPath(
        string deviceId,
        ReaderWorker worker,
        IReaderAdapter reader,
        IDesiredStateClient client,
        IReaderObservationUpload? observations = null,
        ReaderScanMemory? memory = null,
        DesiredPathOptions? options = null)
    {
        if (string.IsNullOrWhiteSpace(deviceId))
        {
            throw new ArgumentException("A device id is required.", nameof(deviceId));
        }

        _deviceId = deviceId;
        _worker = worker ?? throw new ArgumentNullException(nameof(worker));
        _reader = reader ?? throw new ArgumentNullException(nameof(reader));
        _client = client ?? throw new ArgumentNullException(nameof(client));
        _observations = observations;
        _memory = memory ?? ReaderScanMemory.InMemory();
        _options = options ?? new DesiredPathOptions();
        _log = _options.Log ?? NullLogger.Instance;
        _readerLabel = string.IsNullOrWhiteSpace(_options.ReaderName)
            ? ReaderLabels.ShortId(deviceId)
            : _options.ReaderName.Trim();
        _gate = observations == null ? ObservationGate.Open : ObservationGate.Unobserved;
    }

    /// <summary>Whether desired state may be applied now, before any server check.</summary>
    public ObservationGate Gate
    {
        get
        {
            lock (_gateLock)
            {
                return _gate;
            }
        }
    }

    public Task HandleAsync(DesiredRevisionNotice notice, CancellationToken cancellationToken)
    {
        if (!string.Equals(notice.DeviceId, _deviceId, StringComparison.Ordinal))
        {
            return Task.CompletedTask;
        }

        return SerializedAsync(ct => HandleCoreAsync(false, ct), cancellationToken);
    }

    /// <summary>
    /// Reconnect order after the gateway is authenticated: the reader answers a read, the server
    /// processes it, then pull, apply, verify, and ack. A list that fails, or that the server has
    /// not processed, applies nothing.
    /// </summary>
    public Task ReconnectAsync(CancellationToken cancellationToken) =>
        SerializedAsync(ct =>
        {
            if (_observations == null)
            {
                return _reader.ListUsers().Ok ? HandleCoreAsync(true, ct) : Task.CompletedTask;
            }

            lock (_gateLock)
            {
                _gate = ObservationGate.Unobserved;
                _nextObserve = DateTimeOffset.MinValue;
            }

            return HandleCoreAsync(true, ct);
        }, cancellationToken);

    /// <summary>
    /// The periodic retry: runs a pass when the observation is still waiting for the server, a
    /// failed revision is due, or an acknowledgement is still unsent.
    /// </summary>
    public Task RetryAsync(CancellationToken cancellationToken)
    {
        if (Gate == ObservationGate.Open && !_worker.HasDueAttempt() && _worker.PendingAcks.Count == 0)
        {
            return Task.CompletedTask;
        }

        return SerializedAsync(ct => HandleCoreAsync(false, ct), cancellationToken);
    }

    /// <summary>
    /// One trusted read, then the whole list. The per-user observations follow only when the server
    /// did not acknowledge that list. A short list uploads nothing. Nothing is copied onto another reader.
    /// </summary>
    public Task<bool> BootstrapAsync(CancellationToken cancellationToken) => ScanAsync(false, cancellationToken);

    /// <summary>
    /// A scan staff asked for: the list is sent even when the server already acknowledged the same
    /// one, so the server runs its list handling again. False when the reader gave no trusted list.
    /// </summary>
    public Task<bool> ScanNowAsync(CancellationToken cancellationToken) => ScanAsync(true, cancellationToken);

    private async Task<bool> ScanAsync(bool force, CancellationToken cancellationToken)
    {
        var scan = await SerializedAsync(_ => Task.FromResult(ReadScan(out ReaderListResult _)), cancellationToken)
            .ConfigureAwait(false);
        if (scan == null)
        {
            return false;
        }

        await UploadTrustedListAsync(scan, true, force, cancellationToken).ConfigureAwait(false);
        await UploadWantedPhotosAsync(scan, cancellationToken).ConfigureAwait(false);
        if (scan.Observed.Users.Count == 0)
        {
            await SerializedAsync(ct => HandleCoreAsync(false, ct), cancellationToken).ConfigureAwait(false);
        }

        return true;
    }

    /// <summary>
    /// A trusted list uploads reader-created ids. It does not pull or write desired state.
    /// </summary>
    public async Task ObserveAsync(CancellationToken cancellationToken)
    {
        var scan = await SerializedAsync(_ => Task.FromResult(ReadScan(out ReaderListResult _)), cancellationToken)
            .ConfigureAwait(false);
        if (scan != null)
        {
            await UploadNewPeopleAsync(scan, cancellationToken).ConfigureAwait(false);
            await UploadWantedPhotosAsync(scan, cancellationToken).ConfigureAwait(false);
        }
    }

    /// <summary>
    /// One numbered read of the reader for upload: the list, the photo hashes, and the scan number.
    /// Call inside the pass. The number is taken before the read and desired state starts to wait
    /// before the pass ends, so no write, read-back or pull falls between the read and the upload.
    /// The upload itself runs outside the pass. Null when the reader gave no trusted list; desired
    /// state is then left as it was.
    /// </summary>
    private ReaderScan? ReadScan(out ReaderListResult observed)
    {
        var stamp = _worker.BeginScan();
        var started = Stopwatch.GetTimestamp();
        observed = _reader.ListUsers();
        if (!observed.Ok || !observed.CountMatchesAnnouncedTotal)
        {
            _log.LogWarning("Reader {Reader}: user list not usable ({Detail}); nothing is sent or applied from this read",
                _readerLabel, observed.Ok ? $"got {observed.Users.Count} of {observed.AnnouncedTotal} users" : observed.Error);
            return null;
        }

        _log.LogInformation(
            "Reader {Reader}: stage=enumerate; observed {Count} users in {Seconds:0.0} s (announced={Announced})",
            _readerLabel, observed.Users.Count, Stopwatch.GetElapsedTime(started).TotalSeconds, observed.AnnouncedTotal);
        var before = Gate;
        var observation = _observations == null ? 0 : BeginObservation();
        var listed = Listed(observed);
        var photos = new Dictionary<string, byte[]>(StringComparer.OrdinalIgnoreCase);
        return new ReaderScan(observed, listed, FaceHashes(listed, photos), stamp, observation, before, photos);
    }

    /// <param name="Photos">Photos downloaded by this scan, by SHA-256; others stay on the reader.</param>
    private sealed record ReaderScan(
        ReaderListResult Observed,
        List<ReaderUser> People,
        Dictionary<string, string> Hashes,
        ScanStamp Stamp,
        long Observation,
        ObservationGate Before,
        Dictionary<string, byte[]> Photos);

    /// <summary>
    /// Uploads the photos the server asks for: those of people on this reader nobody has decided on,
    /// which the server needs to create them as members. A photo this scan did not download is read
    /// from the reader again. A photo not uploaded is asked for again on the next scan.
    /// </summary>
    private async Task UploadWantedPhotosAsync(ReaderScan scan, CancellationToken cancellationToken)
    {
        if (_observations == null || scan.Hashes.Count == 0)
        {
            return;
        }

        var wanted = await _observations.WantedFacesAsync(_deviceId, cancellationToken).ConfigureAwait(false);
        if (wanted.Count == 0)
        {
            return;
        }

        var holders = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (var (deviceUserId, hash) in scan.Hashes)
        {
            holders.TryAdd(hash, deviceUserId);
        }

        var started = Stopwatch.GetTimestamp();
        var pending = new List<(string Hash, byte[] Bytes)>();
        foreach (var hash in wanted)
        {
            if (!holders.TryGetValue(hash, out var deviceUserId))
            {
                continue;
            }

            var bytes = scan.Photos.GetValueOrDefault(hash)
                ?? await SerializedAsync(_ => Task.FromResult(ReadPhoto(deviceUserId, hash)), cancellationToken)
                    .ConfigureAwait(false);
            if (bytes != null)
            {
                pending.Add((hash, bytes));
            }
        }

        var uploaded = 0;
        var failed = wanted.Count - pending.Count;
        _log.LogInformation(
            "Reader {Reader}: stage=want-photos; server asked for {Count} photos ({Ready} ready to upload, concurrency=4)",
            _readerLabel, wanted.Count, pending.Count);
        await Parallel.ForEachAsync(
                pending,
                new ParallelOptions { MaxDegreeOfParallelism = 4, CancellationToken = cancellationToken },
                async (item, ct) =>
                {
                    if (await _observations.UploadFaceAsync(item.Bytes, ct).ConfigureAwait(false))
                    {
                        var done = Interlocked.Increment(ref uploaded);
                        if (done % 100 == 0)
                        {
                            _log.LogInformation("Reader {Reader}: uploaded {Done}/{Total} photos ({Seconds:0} s)",
                                _readerLabel, done, wanted.Count, Stopwatch.GetElapsedTime(started).TotalSeconds);
                        }
                    }
                    else
                    {
                        Interlocked.Increment(ref failed);
                    }
                })
            .ConfigureAwait(false);

        _log.LogInformation("Reader {Reader}: photos uploaded, {Uploaded} stored, {Failed} retried next scan, in {Seconds:0} s",
            _readerLabel, uploaded, failed, Stopwatch.GetElapsedTime(started).TotalSeconds);
    }

    /// <summary>The reader photo of this user when it is still the photo the scan hashed.</summary>
    private byte[]? ReadPhoto(string deviceUserId, string hash)
    {
        var face = _reader.GetFace(deviceUserId);
        return face.Ok && face.Bytes is { Length: > 0 }
               && string.Equals(FaceHash.Sha256Hex(face.Bytes), hash, StringComparison.OrdinalIgnoreCase)
            ? face.Bytes
            : null;
    }

    /// <summary>A read that is not uploaded leaves desired state as it was before the read.</summary>
    private void NotUploaded(ReaderScan scan)
    {
        lock (_gateLock)
        {
            if (_observations != null && scan.Observation == _observation)
            {
                _gate = scan.Before;
            }
        }
    }

    private async Task SerializedAsync(Func<CancellationToken, Task> run, CancellationToken cancellationToken)
    {
        await _pass.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            await run(cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            _pass.Release();
        }
    }

    private async Task<T> SerializedAsync<T>(Func<CancellationToken, Task<T>> run, CancellationToken cancellationToken)
    {
        await _pass.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            return await run(cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            _pass.Release();
        }
    }

    /// <summary>
    /// The absences and the whole list with its announced total, empty or not. The server runs the
    /// same per-user observation for every entry of a list it processes, so the per-user messages
    /// are sent only when that is not confirmed: always on reconnect, before the list, because the
    /// server's ACK cannot be read until the reconnect finishes; on a scan, after the list, when the
    /// list was not acknowledged, and then only users whose entry changed since they were last sent.
    /// A scan that finds exactly what the server last acknowledged sends nothing until the refresh
    /// period passes. An empty list is not treated as a removal. A short or mismatched list uploads
    /// nothing. Whatever is sent becomes the observation desired state waits for.
    /// </summary>
    private async Task UploadTrustedListAsync(
        ReaderScan scan, bool awaitAcceptance, bool force, CancellationToken cancellationToken)
    {
        if (_observations == null)
        {
            return;
        }

        var observed = scan.Observed;
        var uploads = _memory.Uploads;
        var generation = uploads.Generation;
        var listed = scan.People;
        var hashes = scan.Hashes;
        var missing = listed.Count > 0 ? Missing(listed) : [];
        var entries = listed.ToDictionary(
            user => user.DeviceUserId, user => ScanUploadState.Entry(user, hashes), StringComparer.Ordinal);
        var list = ScanUploadState.List(observed.AnnouncedTotal, entries, missing);
        if (awaitAcceptance && !force && uploads.AlreadyAccepted(list))
        {
            _log.LogDebug(
                "Reader {Reader}: stage=device→server; observation unchanged ({Count} users); nothing resent",
                _readerLabel, listed.Count);
            NotUploaded(scan);
            return;
        }

        var observing = scan.Observation;
        var perUser = new List<string>();
        var absences = new List<string>();
        try
        {
            if (!awaitAcceptance)
            {
                uploads.Reconnecting();
            }

            if (!awaitAcceptance && listed.Count > 0)
            {
                _log.LogInformation(
                    "Reader {Reader}: sending all {Count} people to the server after a reconnect. Member sync stays paused until the server has saved them. First people: {Users}",
                    _readerLabel, listed.Count, SummarizeUsers(listed));
                perUser.AddRange(await _observations.UploadAsync(_deviceId, scan.Stamp, listed, hashes, cancellationToken)
                    .ConfigureAwait(false));
                uploads.Sent(entries.Keys.ToHashSet(StringComparer.Ordinal), entries);
            }

            if (missing.Count > 0)
            {
                _log.LogInformation(
                    "Reader {Reader}: stage=device→server; reporting {Count} absence(s) to the server: {Users}",
                    _readerLabel, missing.Count, string.Join(", ", missing.Take(20)));
                absences.AddRange(await _observations.UploadAbsencesAsync(_deviceId, scan.Stamp, missing, cancellationToken)
                    .ConfigureAwait(false));
            }

            var roster = await UploadListAsync(scan.Stamp, observed.AnnouncedTotal, listed, hashes, awaitAcceptance, cancellationToken)
                .ConfigureAwait(false);
            if (awaitAcceptance && !roster.Accepted && roster.MessageId != null)
            {
                roster = await ConfirmListIfSavedAsync(roster, cancellationToken).ConfigureAwait(false);
            }

            _log.LogInformation(
                "Reader {Reader}: stage=device→server; user list ({Count} users) {Outcome}",
                _readerLabel, listed.Count, RosterOutcome(roster));
            if (awaitAcceptance && roster.Accepted)
            {
                uploads.Accepted(generation, list, entries);
            }
            else if (awaitAcceptance && roster.MessageId == null)
            {
                // The list was not delivered. Send people one by one so the server still sees them.
                var unsent = uploads.Unsent(entries);
                if (unsent.Count > 0)
                {
                    var changed = listed.Where(user => unsent.Contains(user.DeviceUserId)).ToList();
                    _log.LogInformation(
                        "Reader {Reader}: stage=device→server; sending {Count} changed user(s) to the server: {Users}",
                        _readerLabel, changed.Count, SummarizeUsers(changed));
                    perUser.AddRange(await _observations.UploadAsync(
                            _deviceId, scan.Stamp, changed, hashes, cancellationToken)
                        .ConfigureAwait(false));
                    uploads.Sent(unsent, entries);
                }
            }

            EndObservation(observing, Alternatives(roster, perUser, absences), roster.Accepted);
        }
        catch (OperationCanceledException)
        {
            EndObservation(observing, [], false);
            throw;
        }
        catch (Exception ex)
        {
            // The people stay on this reader. Nothing is copied to another reader.
            _log.LogWarning(ex, "Reader {Reader}: the observation was not uploaded; desired state waits", _readerLabel);
            EndObservation(observing, [], false);
        }
    }

    private static string RosterOutcome(RosterUpload roster)
    {
        if (roster.Accepted)
        {
            return "processed by the server";
        }

        return roster.MessageId != null ? "sent to the server" : "not acknowledged; users are sent one by one";
    }

    private static string SummarizeUsers(IEnumerable<ReaderUser> users)
    {
        var parts = users
            .Select(user =>
            {
                var name = string.IsNullOrWhiteSpace(user.Name) ? null : user.Name.Trim();
                return name == null ? user.DeviceUserId : $"{name} ({user.DeviceUserId})";
            })
            .Take(21)
            .ToList();
        if (parts.Count == 0)
        {
            return "(none)";
        }

        var more = parts.Count > 20;
        return string.Join(", ", parts.Take(20)) + (more ? ", …" : "");
    }

    /// <summary>
    /// The server has seen the reader once it processed the list. Per-user and absence ids from the
    /// same scan stay in the outbox for retry and are not a second fence. Without a list, every
    /// delivered per-user and absence id must be saved.
    /// </summary>
    private static List<IReadOnlyList<string>> Alternatives(
        RosterUpload roster, List<string> perUser, List<string> absences)
    {
        var alternatives = new List<IReadOnlyList<string>>();
        if (roster.MessageId != null)
        {
            alternatives.Add([roster.MessageId]);
            return alternatives;
        }

        if (perUser.Count > 0 || absences.Count > 0)
        {
            alternatives.Add([.. perUser, .. absences]);
        }

        return alternatives;
    }

    /// <summary>
    /// After the list wait, ask whether the server recorded that list before falling through to
    /// per-user uploads. A late ACK or a terminal refusal both show as saved.
    /// </summary>
    private async Task<RosterUpload> ConfirmListIfSavedAsync(RosterUpload roster, CancellationToken cancellationToken)
    {
        if (roster.MessageId == null)
        {
            return roster;
        }

        try
        {
            var check = await _client.CheckObservationAsync(_deviceId, [roster.MessageId], cancellationToken)
                .ConfigureAwait(false);
            if (check.Processed)
            {
                _log.LogInformation(
                    "Reader {Reader}: the full list was already saved on the server. Member sync can continue.",
                    _readerLabel);
                return new RosterUpload(true, roster.MessageId);
            }
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            _log.LogDebug(ex, "Reader {Reader}: could not confirm whether the list was saved", _readerLabel);
        }

        return roster;
    }

    /// <summary>Any failure of the list counts as not accepted, so the per-user messages still go.</summary>
    private async Task<RosterUpload> UploadListAsync(
        ScanStamp stamp,
        int announcedTotal,
        IReadOnlyList<ReaderUser> listed,
        IReadOnlyDictionary<string, string> hashes,
        bool awaitAcceptance,
        CancellationToken cancellationToken)
    {
        try
        {
            return await _observations!.UploadTrustedRosterAsync(
                    _deviceId, stamp, announcedTotal, listed, hashes, awaitAcceptance, cancellationToken)
                .ConfigureAwait(false);
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            return RosterUpload.NotSent;
        }
    }

    private async Task UploadNewPeopleAsync(ReaderScan scan, CancellationToken cancellationToken)
    {
        var listed = scan.People;
        if (_observations == null || listed.Count == 0)
        {
            NotUploaded(scan);
            return;
        }

        var observing = scan.Observation;
        try
        {
            _log.LogInformation(
                "Reader {Reader}: stage=device→server; uploading {Count} user(s) seen on the reader: {Users}",
                _readerLabel, listed.Count, SummarizeUsers(listed));
            var sent = new List<string>(await _observations.UploadAsync(_deviceId, scan.Stamp, listed, scan.Hashes, cancellationToken)
                .ConfigureAwait(false));
            var missing = Missing(listed);
            if (missing.Count > 0)
            {
                _log.LogInformation(
                    "Reader {Reader}: stage=device→server; reporting {Count} absence(s) to the server: {Users}",
                    _readerLabel, missing.Count, string.Join(", ", missing.Take(20)));
                sent.AddRange(await _observations.UploadAbsencesAsync(_deviceId, scan.Stamp, missing, cancellationToken)
                    .ConfigureAwait(false));
            }

            EndObservation(observing, sent.Count > 0 ? [sent] : [], false);
        }
        catch (OperationCanceledException)
        {
            EndObservation(observing, [], false);
            throw;
        }
        catch (Exception ex)
        {
            // The person stays on this reader. Nothing is copied to another reader.
            _log.LogWarning(ex, "Reader {Reader}: the observation was not uploaded; desired state waits", _readerLabel);
            EndObservation(observing, [], false);
        }
    }

    /// <summary>Desired state waits from the moment an observation starts to go out.</summary>
    private long BeginObservation()
    {
        lock (_gateLock)
        {
            _gate = ObservationGate.Observing;
            return ++_observation;
        }
    }

    /// <summary>
    /// The observation is now what desired state waits for. An acknowledged list was processed.
    /// Nothing sent leaves the reader unobserved. A newer observation that started meanwhile wins.
    /// </summary>
    private void EndObservation(long observation, IReadOnlyList<IReadOnlyList<string>> fence, bool accepted)
    {
        lock (_gateLock)
        {
            if (observation != _observation)
            {
                return;
            }

            _fence = fence;
            _fencedAt = _worker.Now;
            if (accepted)
            {
                _gate = ObservationGate.Open;
            }
            else
            {
                _gate = fence.Count > 0 ? ObservationGate.Fenced : ObservationGate.Unobserved;
            }
        }
    }

    /// <summary>
    /// True once the server confirmed it processed the reader's latest observation. An unobserved
    /// reader is read and uploaded first. A failed or unanswered check is not processed. While fenced,
    /// the same observation is probed again; only after <see cref="DesiredPathOptions.FenceResendAfter"/>
    /// is the reader re-read and the observation resent.
    /// </summary>
    private async Task<bool> ObservationProcessedAsync(bool reconnecting, CancellationToken cancellationToken)
    {
        if (Gate == ObservationGate.Unobserved && !await ObserveForGateAsync(cancellationToken).ConfigureAwait(false))
        {
            return false;
        }

        var checks = reconnecting && _options.ReconnectPoll > TimeSpan.Zero
            ? 1 + (int)(_options.ReconnectWait / _options.ReconnectPoll)
            : 1;
        var processed = await CheckProcessedAsync(checks, cancellationToken).ConfigureAwait(false);
        if (processed != null)
        {
            return processed.Value;
        }

        lock (_gateLock)
        {
            if (_gate == ObservationGate.Fenced)
            {
                var age = _worker.Now - _fencedAt;
                // Only while this fence (the list itself, or every per-user id when there is no list)
                // is still missing. A saved list opens the gate above and never reaches here.
                if (age >= _options.FenceResendAfter)
                {
                    _log.LogWarning(
                        "Reader {Reader}: the server still has not saved the reader list after {Minutes} min. Reading the reader again and sending the list once more. Member sync stays paused.",
                        _readerLabel, Math.Max(1, (int)age.TotalMinutes));
                    _gate = ObservationGate.Unobserved;
                }
                else if (age >= _options.FenceTimeout && _worker.Now >= _nextFenceWarn)
                {
                    _log.LogWarning(
                        "Reader {Reader}: the server still has not saved the reader list after {Minutes} min. Asking again. Member sync stays paused. Nothing to do in the app.",
                        _readerLabel, Math.Max(1, (int)age.TotalMinutes));
                    _nextFenceWarn = _worker.Now + _options.FenceTimeout;
                }
            }
        }

        return false;
    }

    /// <summary>
    /// Asks the server up to <paramref name="checks"/> times, a poll apart. Null when the
    /// observation is still waiting for the server after the last check.
    /// </summary>
    private async Task<bool?> CheckProcessedAsync(int checks, CancellationToken cancellationToken)
    {
        for (var check = 1; check <= checks; check++)
        {
            if (check > 1)
            {
                await Task.Delay(_options.ReconnectPoll, cancellationToken).ConfigureAwait(false);
            }

            ObservationGate gate;
            IReadOnlyList<IReadOnlyList<string>> fence;
            long observation;
            lock (_gateLock)
            {
                gate = _gate;
                fence = _fence;
                observation = _observation;
            }

            if (gate == ObservationGate.Open)
            {
                return true;
            }

            if (gate != ObservationGate.Fenced)
            {
                return false;
            }

            if (await AnyProcessedAsync(fence, cancellationToken).ConfigureAwait(false))
            {
                lock (_gateLock)
                {
                    if (observation == _observation)
                    {
                        _gate = ObservationGate.Open;
                    }

                    return _gate == ObservationGate.Open;
                }
            }
        }

        return null;
    }

    private async Task<bool> AnyProcessedAsync(IReadOnlyList<IReadOnlyList<string>> fence, CancellationToken cancellationToken)
    {
        ObservationCheck best = default;
        var answered = false;
        foreach (var ids in fence)
        {
            try
            {
                var check = await _client.CheckObservationAsync(_deviceId, ids, cancellationToken).ConfigureAwait(false);
                answered = true;
                if (check.Saved >= best.Saved)
                {
                    best = check;
                }

                if (check.Processed)
                {
                    NoteCheck(check);
                    return true;
                }
            }
            catch (Exception ex) when (ex is not OperationCanceledException)
            {
                NoteFailure(ex);
            }
        }

        if (answered)
        {
            NoteCheck(best);
        }

        return false;
    }

    /// <summary>Reads and uploads the reader as a reconnect does, at most once per retry period.</summary>
    private async Task<bool> ObserveForGateAsync(CancellationToken cancellationToken)
    {
        lock (_gateLock)
        {
            if (_worker.Now < _nextObserve)
            {
                return false;
            }

            _nextObserve = _worker.Now + _options.ObserveRetry;
        }

        var scan = ReadScan(out var observed);
        if (scan == null)
        {
            _log.LogWarning(
                "Reader {Reader}: no trusted user list ({Error}); desired state waits until one is processed",
                _readerLabel, observed.Ok ? "the count does not match the announced total" : observed.Error);
            return false;
        }

        await UploadTrustedListAsync(scan, false, false, cancellationToken).ConfigureAwait(false);
        return Gate is ObservationGate.Fenced or ObservationGate.Open;
    }

    private static List<ReaderUser> Listed(ReaderListResult observed) =>
        observed.Users.Where(user => !string.IsNullOrWhiteSpace(user.DeviceUserId)).ToList();

    /// <summary>
    /// One hash per id. Equal hashes stay separate observations. With the reader's photo checksum
    /// list, a user it does not list has no photo. Cached checksums skip download for the first
    /// user that claims them; another user with the same checksum is always re-read. Without that
    /// list every photo is downloaded.
    /// </summary>
    private Dictionary<string, string> FaceHashes(IReadOnlyList<ReaderUser> listed, Dictionary<string, byte[]> photos)
    {
        var checksums = _reader.ListFaceChecksums();
        if (!checksums.Ok)
        {
            return DownloadedFaceHashes(listed, photos);
        }

        var hashes = new Dictionary<string, string>(StringComparer.Ordinal);
        var known = new Dictionary<string, string>(StringComparer.Ordinal);
        var claimedChecksum = new HashSet<string>(StringComparer.Ordinal);
        var toDownload = 0;
        foreach (var user in listed)
        {
            if (!checksums.ByUserId.TryGetValue(user.DeviceUserId, out var sum))
            {
                continue;
            }

            if (sum.Length == 0 || !_memory.Faces.TryGet(sum, out _))
            {
                toDownload++;
                if (sum.Length > 0)
                {
                    claimedChecksum.Add(sum);
                }
            }
            else if (!claimedChecksum.Add(sum))
            {
                toDownload++;
            }
        }

        claimedChecksum.Clear();
        var downloads = new DownloadProgress(_log, _readerLabel, toDownload);
        foreach (var deviceUserId in listed.Select(user => user.DeviceUserId))
        {
            if (!checksums.ByUserId.TryGetValue(deviceUserId, out var checksum))
            {
                continue;
            }

            string? hash = null;
            var cacheHit = checksum.Length > 0 && _memory.Faces.TryGet(checksum, out hash);
            var sharedChecksum = checksum.Length > 0 && !claimedChecksum.Add(checksum);
            if (!cacheHit || sharedChecksum || checksum.Length == 0)
            {
                var face = _reader.GetFace(deviceUserId);
                downloads.One();
                if (!face.Ok || face.Bytes is not { Length: > 0 })
                {
                    continue;
                }

                hash = FaceHash.Sha256Hex(face.Bytes);
                photos[hash] = face.Bytes;
            }

            if (hash == null)
            {
                continue;
            }

            hashes[deviceUserId] = hash;
            if (checksum.Length > 0)
            {
                if (known.TryGetValue(checksum, out var prior) && !string.Equals(prior, hash, StringComparison.OrdinalIgnoreCase))
                {
                    known.Remove(checksum);
                }
                else
                {
                    known[checksum] = hash;
                }
            }
        }

        _memory.Faces.Replace(known);
        downloads.Done(hashes.Count);
        return hashes;
    }

    private Dictionary<string, string> DownloadedFaceHashes(IReadOnlyList<ReaderUser> listed, Dictionary<string, byte[]> photos)
    {
        var hashes = new Dictionary<string, string>(StringComparer.Ordinal);
        var downloads = new DownloadProgress(_log, _readerLabel, listed.Count);
        foreach (var deviceUserId in listed.Select(user => user.DeviceUserId))
        {
            var face = _reader.GetFace(deviceUserId);
            downloads.One();
            if (face.Ok && face.Bytes is { Length: > 0 })
            {
                var hash = FaceHash.Sha256Hex(face.Bytes);
                hashes[deviceUserId] = hash;
                photos[hash] = face.Bytes;
            }
        }

        downloads.Done(hashes.Count);
        return hashes;
    }

    /// <summary>Photo downloads from the reader can take minutes; progress is logged every 100.</summary>
    private sealed class DownloadProgress
    {
        private const int Every = 100;
        private readonly ILogger _log;
        private readonly string _reader;
        private readonly int _total;
        private readonly long _started = Stopwatch.GetTimestamp();
        private int _done;

        public DownloadProgress(ILogger log, string reader, int total)
        {
            _log = log;
            _reader = reader;
            _total = total;
            if (total > 0)
            {
                _log.LogInformation("Reader {Reader}: downloading {Total} photos from the reader", reader, total);
            }
        }

        public void One()
        {
            _done++;
            if (_done % Every == 0 && _done < _total)
            {
                _log.LogInformation("Reader {Reader}: downloaded {Done}/{Total} photos ({Seconds:0} s)",
                    _reader, _done, _total, Stopwatch.GetElapsedTime(_started).TotalSeconds);
            }
        }

        public void Done(int withPhoto)
        {
            if (_total > 0)
            {
                _log.LogInformation("Reader {Reader}: photos checked, {WithPhoto} users have a photo; {Done} downloaded in {Seconds:0} s",
                    _reader, withPhoto, _done, Stopwatch.GetElapsedTime(_started).TotalSeconds);
            }
        }
    }

    /// <summary>
    /// Mapped ids this gateway wrote that a trusted, non-empty list no longer contains.
    /// An empty list is not evidence that those people were removed.
    /// </summary>
    private List<string> Missing(IReadOnlyList<ReaderUser> listed)
    {
        var present = listed.Select(user => user.DeviceUserId).ToHashSet(StringComparer.Ordinal);
        return _worker.AppliedUserIds().Where(id => !present.Contains(id)).ToList();
    }

    private enum ItemStep
    {
        Continue,
        Reported
    }

    private async Task HandleCoreAsync(bool reconnecting, CancellationToken cancellationToken)
    {
        if (!await ObservationProcessedAsync(reconnecting, cancellationToken).ConfigureAwait(false))
        {
            return;
        }

        await DeliverPendingAcksAsync(cancellationToken).ConfigureAwait(false);
        for (var pass = 0; pass < MaxPasses; pass++)
        {
            if (!await PassAsync(cancellationToken).ConfigureAwait(false))
            {
                return;
            }
        }

        _log.LogInformation(
            "Reader {Reader}: desired state was still changing after {Passes} passes; the next notice or retry continues",
            _readerLabel, MaxPasses);
    }

    /// <summary>
    /// Every desired revision after the cursor, page by page and oldest first. One that fails or
    /// waits does not stop the rest. A failed revision the server no longer lists is dropped. True
    /// when a report changed what the server desires, so another pass is needed.
    /// </summary>
    private async Task<bool> PassAsync(CancellationToken cancellationToken)
    {
        var from = _worker.AppliedRevision;
        var after = from;
        var covered = from;
        var seen = new HashSet<long>();
        var reported = false;
        var progress = new ApplyProgress(this, _log, _readerLabel, from);
        for (var page = 0; page < MaxPages; page++)
        {
            var pull = await _client.PullAsync(_deviceId, after, cancellationToken).ConfigureAwait(false);
            progress.Page(pull.Items.Count, pull.DesiredRevision);
            foreach (var item in pull.Items)
            {
                seen.Add(item.Revision);
                if (await ApplyItemAsync(item, progress, cancellationToken).ConfigureAwait(false) == ItemStep.Reported)
                {
                    reported = true;
                }
            }

            if (pull.Items.Count == 0)
            {
                covered = Math.Max(covered, pull.DesiredRevision);
                break;
            }

            var last = pull.Items.Max(item => item.Revision);
            covered = last;
            if (last >= pull.DesiredRevision)
            {
                covered = pull.DesiredRevision;
                break;
            }

            after = last;
        }

        DropSuperseded(from, covered, seen);
        progress.Finished();
        return reported;
    }

    /// <summary>
    /// What one pass did, for staff watching a long first sync: a start line, one line per write,
    /// a count every 100 changes, and a summary. A pass with nothing to apply logs nothing.
    /// </summary>
    private sealed class ApplyProgress
    {
        private const int Every = 100;
        private readonly DesiredRevisionPath _path;
        private readonly ILogger _log;
        private readonly string _reader;
        private readonly long _from;
        private readonly long _started = Stopwatch.GetTimestamp();
        private long _target;
        private bool _announced;
        private int _checked;
        private int _written;
        private int _matched;
        private int _failed;
        private int _reported;
        private int _waiting;

        public ApplyProgress(DesiredRevisionPath path, ILogger log, string reader, long from)
        {
            _path = path;
            _log = log;
            _reader = reader;
            _from = from;
        }

        public void Page(int items, long target)
        {
            _target = Math.Max(_target, target);
            if (items > 0 && !_announced)
            {
                _announced = true;
                _log.LogInformation(
                    "Reader {Reader}: writing members onto the reader, from revision {From} through revision {Target}.",
                    _reader, _from, _target);
                _path.NoteWriting(_from, _target, _written, _from);
            }
        }

        public void Item(DesiredPullItem item, MemberApplyKind kind, TimeSpan took)
        {
            _checked++;
            switch (kind)
            {
                case MemberApplyKind.Applied:
                    _written++;
                    _log.LogInformation(
                        "Reader {Reader}: member {UserId} is {Action} the reader ({Revision} of {Target}, {Seconds:0} s).",
                        _reader, item.DeviceUserId, item.Present ? "now on" : "removed from", item.Revision, _target,
                        took.TotalSeconds);
                    _path.NoteWriting(_from, _target, _written, item.Revision);
                    break;
                case MemberApplyKind.AlreadyApplied:
                    _matched++;
                    break;
                case MemberApplyKind.Failed:
                    _failed++;
                    break;
                case MemberApplyKind.Occupied:
                case MemberApplyKind.OccupantChanged:
                    _reported++;
                    break;
                default:
                    _waiting++;
                    break;
            }

            if (_checked % Every == 0)
            {
                _log.LogInformation(
                    "Reader {Reader}: {Written} members written, {Matched} already on the reader, {Failed} not written. Now at revision {Revision} of {Target}.",
                    _reader, _written, _matched, _failed, item.Revision, _target);
                _path.NoteWriting(_from, _target, _written, item.Revision);
            }
        }

        public void Finished()
        {
            if (!_announced)
            {
                return;
            }

            _log.LogInformation(
                "Reader {Reader}: finished this batch in {Seconds:0} s. {Written} written, {Matched} already on the reader, {Failed} not written and will be tried again, {Reported} sent back for review, {Waiting} still waiting. Through revision {Target}.",
                _reader, Stopwatch.GetElapsedTime(_started).TotalSeconds, _written, _matched, _failed, _reported,
                _waiting, _target);
            _path.NoteWriting(_from, _target, _written, _target);
        }
    }

    /// <summary>
    /// A failed revision the server no longer lists was replaced by a newer revision, held for
    /// staff, or dropped. Its attempt is not needed; what the server desires now carries any hold.
    /// </summary>
    private void DropSuperseded(long from, long covered, HashSet<long> seen)
    {
        foreach (var attempt in _worker.Attempts)
        {
            if (attempt.Revision <= from || attempt.Revision > covered || seen.Contains(attempt.Revision))
            {
                continue;
            }

            _log.LogInformation(
                "Reader {Reader}: revision {Revision} for user {UserId} is no longer desired by the server; its failed attempt ({Error}) is dropped",
                _readerLabel, attempt.Revision, attempt.DeviceUserId, attempt.LastError);
            _worker.Settle(attempt.Revision);
        }
    }

    private async Task<ItemStep> ApplyItemAsync(
        DesiredPullItem item, ApplyProgress progress, CancellationToken cancellationToken)
    {
        if (_worker.IsVerified(item.Revision))
        {
            return ItemStep.Continue;
        }

        if (item.Invalid != null)
        {
            progress.Item(item, MemberApplyKind.Failed, TimeSpan.Zero);
            await FailedAsync(_worker.Attempt(item.Revision) is { State: AttemptState.Rejected } rejected
                    && _worker.Now < rejected.NextAttempt
                ? rejected
                : _worker.Reject(item.Revision, item.DeviceUserId, item.Invalid), cancellationToken).ConfigureAwait(false);
            return ItemStep.Continue;
        }

        var started = Stopwatch.GetTimestamp();
        var result = item.Present
            ? _worker.ApplyMember(item.ToMember())
            : _worker.ApplyRemoval(item.Revision, item.DeviceUserId, item.Expected);
        progress.Item(item, result.Kind, Stopwatch.GetElapsedTime(started));
        switch (result.Kind)
        {
            case MemberApplyKind.OccupantChanged:
                var occupant = (result.Occupant ?? ReaderOccupant.Nobody) with { ReadAfter = _worker.CurrentScan };
                return await ReportAsync(item, ct => _client.ReportOccupantAsync(
                        _deviceId, item.Revision, item.DeviceUserId, occupant, ct),
                    cancellationToken).ConfigureAwait(false);
            case MemberApplyKind.Occupied:
                var occupiedId = result.Detail;
                if (string.IsNullOrWhiteSpace(occupiedId))
                {
                    throw new InvalidOperationException("Occupied result has no device user id");
                }

                return await ReportAsync(item, ct => _client.ReportOccupiedAsync(_deviceId, item.Revision, occupiedId, ct),
                    cancellationToken).ConfigureAwait(false);
            case MemberApplyKind.Applied:
            case MemberApplyKind.AlreadyApplied:
                await DeliverPendingAcksAsync(cancellationToken).ConfigureAwait(false);
                return ItemStep.Continue;
            case MemberApplyKind.Failed:
                await FailedAsync(_worker.Attempt(item.Revision), cancellationToken).ConfigureAwait(false);
                return ItemStep.Continue;
            default:
                return ItemStep.Continue;
        }
    }

    /// <summary>
    /// The server decides what an occupied id or an unexpected occupant means. The attempt stays
    /// until the server has the report; a refused report means the revision is no longer desired.
    /// </summary>
    private async Task<ItemStep> ReportAsync(
        DesiredPullItem item, Func<CancellationToken, Task> report, CancellationToken cancellationToken)
    {
        try
        {
            await report(cancellationToken).ConfigureAwait(false);
        }
        catch (DesiredRefusedException ex)
        {
            _log.LogInformation(ex,
                "Reader {Reader}: revision {Revision} was replaced before its report ({Message})",
                _readerLabel, item.Revision, ex.Message);
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            _log.LogWarning(ex,
                "Reader {Reader}: revision {Revision} for user {UserId} was not written and the report failed; it is retried",
                _readerLabel, item.Revision, item.DeviceUserId);
            return ItemStep.Continue;
        }

        _worker.Settle(item.Revision);
        return ItemStep.Reported;
    }

    /// <summary>
    /// A failure is logged. An exhausted or rejected revision is reported to the server once; it
    /// stays in the journal until it succeeds or the server stops desiring it.
    /// </summary>
    private async Task FailedAsync(RetryState? attempt, CancellationToken cancellationToken)
    {
        if (attempt == null)
        {
            return;
        }

        if (attempt.State == AttemptState.Retry)
        {
            _log.LogInformation(
                "Reader {Reader}: revision {Revision} for user {UserId} failed attempt {Attempt} ({Error}); next at {Next:o}",
                _readerLabel, attempt.Revision, attempt.DeviceUserId, attempt.AttemptCount, attempt.LastError, attempt.NextAttempt);
            return;
        }

        if (attempt.Reported)
        {
            return;
        }

        _log.LogWarning(
            "Reader {Reader}: revision {Revision} for user {UserId} is {State} after {Attempt} attempts ({Error}); newer revisions continue",
            _readerLabel, attempt.Revision, attempt.DeviceUserId, attempt.State, attempt.AttemptCount, attempt.LastError);
        try
        {
            await _client.ReportFailedAsync(_deviceId, attempt, cancellationToken).ConfigureAwait(false);
            _worker.MarkReported(attempt.Revision);
        }
        catch (DesiredRefusedException)
        {
            _worker.Settle(attempt.Revision);
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            _log.LogWarning(ex, "Reader {Reader}: failure of revision {Revision} was not reported; it is reported again later",
                _readerLabel, attempt.Revision);
        }
    }

    /// <summary>
    /// A verified revision is acknowledged with what the reader shows now. A revision the server
    /// replaced needs no acknowledgement. One the server refuses, or that no longer reads back, is
    /// not applied after all and is retried.
    /// </summary>
    private async Task DeliverPendingAcksAsync(CancellationToken cancellationToken)
    {
        foreach (var revision in _worker.PendingAcks.Select(pending => pending.Revision).ToArray())
        {
            var page = await _client.PullAsync(_deviceId, revision - 1, cancellationToken).ConfigureAwait(false);
            var item = page.Items.FirstOrDefault(candidate => candidate.Revision == revision);
            if (item == null)
            {
                // The server replaced the row, held it, or dropped it after finding someone else at the id.
                if (page.DesiredRevision >= revision)
                {
                    _worker.MarkAckDelivered(revision);
                    continue;
                }

                throw new InvalidOperationException($"Pending acknowledgement {revision} is not in the desired projection");
            }

            string error;
            try
            {
                await AcknowledgeFromReaderAsync(item, cancellationToken).ConfigureAwait(false);
                _worker.MarkAckDelivered(revision);
                continue;
            }
            catch (ReadBackException ex)
            {
                error = ex.Message;
            }
            catch (DesiredRefusedException ex)
            {
                error = "the server refused the read-back: " + ex.Message;
            }

            _log.LogWarning("Reader {Reader}: revision {Revision} is not acknowledged ({Error}); it is applied again",
                _readerLabel, revision, error);
            await FailedAsync(_worker.Unverify(revision, item.DeviceUserId, error), cancellationToken).ConfigureAwait(false);
        }
    }

    private async Task AcknowledgeFromReaderAsync(DesiredPullItem item, CancellationToken cancellationToken)
    {
        if (!item.Present)
        {
            await AcknowledgeAbsenceAsync(item, cancellationToken).ConfigureAwait(false);
            return;
        }

        if (!item.FacePresent)
        {
            await AcknowledgeFaceClearedAsync(item, cancellationToken).ConfigureAwait(false);
            return;
        }

        var user = _reader.GetUser(item.DeviceUserId);
        var face = _reader.GetFace(item.DeviceUserId);
        if (!user.Ok || user.User == null || !face.Ok || face.Bytes is not { Length: > 0 })
        {
            throw new ReadBackException("Read-back is incomplete; acknowledgement was not sent");
        }

        var read = user.User;
        if (read.ValidFrom == null || read.ValidTo == null || string.IsNullOrWhiteSpace(read.Name))
        {
            throw new ReadBackException("Read-back user is incomplete; acknowledgement was not sent");
        }

        // Local verify already matched the user (day-level dates / TrueFace name form). Ack the desired
        // user fields so string/time drift on the reader cannot 409; face hash stays from the reader.
        if (!_worker.UserMatchesDesired(read, item.ToMember()))
        {
            throw new ReadBackException("Read-back user does not match desired; acknowledgement was not sent");
        }

        var hash = Convert.ToHexString(SHA256.HashData(face.Bytes)).ToLowerInvariant();
        await _client.AcknowledgeAsync(new DesiredAcknowledgement(
            _deviceId,
            item.Revision,
            item.DeviceUserId,
            item.Name,
            item.NameEx,
            item.UserStatus,
            item.ValidFrom,
            item.ValidTo,
            hash) { ReadAfter = _worker.CurrentScan }, cancellationToken).ConfigureAwait(false);
    }

    private async Task AcknowledgeFaceClearedAsync(DesiredPullItem item, CancellationToken cancellationToken)
    {
        var user = _reader.GetUser(item.DeviceUserId);
        var face = _reader.GetFace(item.DeviceUserId);
        if (!user.Ok || user.User == null)
        {
            throw new ReadBackException("User read-back is missing; acknowledgement was not sent");
        }

        if (face.Ok && face.Bytes is { Length: > 0 })
        {
            throw new ReadBackException("Face is still present; acknowledgement was not sent");
        }

        if (face.FailCode == FakeReader.FailNoRecord)
        {
            throw new ReadBackException("User is NO_RECORD; acknowledgement was not sent");
        }

        if (face.FailCode != FakeReader.FailUnknown)
        {
            throw new ReadBackException("Face read-back is not the missing-photo result; acknowledgement was not sent");
        }

        var read = user.User;
        if (read.ValidFrom == null || read.ValidTo == null || string.IsNullOrWhiteSpace(read.Name))
        {
            throw new ReadBackException("Read-back user is incomplete; acknowledgement was not sent");
        }

        if (!_worker.UserMatchesDesired(read, item.ToMember()))
        {
            throw new ReadBackException("Read-back user does not match desired; acknowledgement was not sent");
        }

        await _client.AcknowledgeAsync(new DesiredAcknowledgement(
            _deviceId,
            item.Revision,
            item.DeviceUserId,
            item.Name,
            item.NameEx,
            item.UserStatus,
            item.ValidFrom,
            item.ValidTo,
            "",
            true,
            FakeReader.FailUnknown) { ReadAfter = _worker.CurrentScan }, cancellationToken).ConfigureAwait(false);
    }

    private async Task AcknowledgeAbsenceAsync(DesiredPullItem item, CancellationToken cancellationToken)
    {
        var user = _reader.GetUser(item.DeviceUserId);
        if (user.FailCode != FakeReader.FailNoRecord)
        {
            throw new ReadBackException("User is not NO_RECORD; acknowledgement was not sent");
        }

        await _client.AcknowledgeAsync(new DesiredAcknowledgement(
            _deviceId,
            item.Revision,
            item.DeviceUserId,
            "",
            null,
            0,
            "",
            "",
            "",
            false,
            FakeReader.FailNoRecord) { ReadAfter = _worker.CurrentScan }, cancellationToken).ConfigureAwait(false);
    }

    public sealed class ReadBackException(string message) : Exception(message);
}

/// <summary>
/// <see cref="Open"/>: the server processed the latest observation. <see cref="Unobserved"/>: none
/// since start or reconnect, or the last one was not processed in time. <see cref="Observing"/>:
/// one is being sent. <see cref="Fenced"/>: sent, not yet confirmed processed.
/// </summary>
public enum ObservationGate
{
    Unobserved,
    Observing,
    Fenced,
    Open
}

/// <summary>
/// <see cref="FenceTimeout"/>: how long an observation may wait before a rate-limited warning while
/// the same observation is still probed. <see cref="FenceResendAfter"/>: how long before the reader
/// is re-read and the observation is sent again. <see cref="ReconnectWait"/>: how long a reconnect
/// checks for that processing before leaving desired state to the next notice or retry.
/// <see cref="ObserveRetry"/>: the least time between two reads made only to observe the reader.
/// </summary>
public sealed record DesiredPathOptions
{
    public ILogger? Log { get; init; }

    /// <summary>Staff-facing reader name for logs; falls back to a short device id.</summary>
    public string? ReaderName { get; init; }

    public TimeSpan FenceTimeout { get; init; } = TimeSpan.FromMinutes(5);

    /// <summary>After this, stage=fenced falls back to Unobserved and a full observe resend.</summary>
    public TimeSpan FenceResendAfter { get; init; } = TimeSpan.FromMinutes(15);

    public TimeSpan ReconnectWait { get; init; } = TimeSpan.FromSeconds(5);

    public TimeSpan ReconnectPoll { get; init; } = TimeSpan.FromMilliseconds(250);

    public TimeSpan ObserveRetry { get; init; } = TimeSpan.FromSeconds(30);
}
