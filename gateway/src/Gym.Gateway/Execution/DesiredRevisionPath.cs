using System.Diagnostics;
using System.Security.Cryptography;
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
    private DateTimeOffset _nextObserve = DateTimeOffset.MinValue;
    private long _observation;

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
            _log.LogWarning("Reader {DeviceId}: user list not usable ({Detail}); nothing is sent or applied from this read",
                _deviceId, observed.Ok ? $"got {observed.Users.Count} of {observed.AnnouncedTotal} users" : observed.Error);
            return null;
        }

        _log.LogInformation("Reader {DeviceId}: read the user list, {Count} users in {Seconds:0.0} s",
            _deviceId, observed.Users.Count, Stopwatch.GetElapsedTime(started).TotalSeconds);
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
        var uploaded = 0;
        var failed = 0;
        _log.LogInformation("Reader {DeviceId}: uploading {Count} photos the server needs", _deviceId, wanted.Count);
        foreach (var hash in wanted)
        {
            if (!holders.TryGetValue(hash, out var deviceUserId))
            {
                continue;
            }

            var bytes = scan.Photos.GetValueOrDefault(hash)
                ?? await SerializedAsync(_ => Task.FromResult(ReadPhoto(deviceUserId, hash)), cancellationToken)
                    .ConfigureAwait(false);
            if (bytes != null && await _observations.UploadFaceAsync(bytes, cancellationToken).ConfigureAwait(false))
            {
                uploaded++;
            }
            else
            {
                failed++;
            }

            if ((uploaded + failed) % 100 == 0)
            {
                _log.LogInformation("Reader {DeviceId}: uploaded {Done}/{Total} photos ({Seconds:0} s)",
                    _deviceId, uploaded + failed, wanted.Count, Stopwatch.GetElapsedTime(started).TotalSeconds);
            }
        }

        _log.LogInformation("Reader {DeviceId}: photos uploaded, {Uploaded} stored, {Failed} retried next scan, in {Seconds:0} s",
            _deviceId, uploaded, failed, Stopwatch.GetElapsedTime(started).TotalSeconds);
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
                perUser.AddRange(await _observations.UploadAsync(_deviceId, scan.Stamp, listed, hashes, cancellationToken)
                    .ConfigureAwait(false));
                uploads.Sent(entries.Keys.ToHashSet(StringComparer.Ordinal), entries);
            }

            if (missing.Count > 0)
            {
                absences.AddRange(await _observations.UploadAbsencesAsync(_deviceId, scan.Stamp, missing, cancellationToken)
                    .ConfigureAwait(false));
            }

            var roster = await UploadListAsync(scan.Stamp, observed.AnnouncedTotal, listed, hashes, awaitAcceptance, cancellationToken)
                .ConfigureAwait(false);
            _log.LogInformation("Reader {DeviceId}: user list ({Count} users) {Outcome}", _deviceId, listed.Count,
                RosterOutcome(roster));
            if (awaitAcceptance && roster.Accepted)
            {
                uploads.Accepted(generation, list, entries);
            }
            else if (awaitAcceptance)
            {
                var unsent = uploads.Unsent(entries);
                if (unsent.Count > 0)
                {
                    perUser.AddRange(await _observations.UploadAsync(
                            _deviceId, scan.Stamp, listed.Where(user => unsent.Contains(user.DeviceUserId)).ToList(), hashes,
                            cancellationToken)
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
            _log.LogWarning(ex, "Reader {DeviceId}: the observation was not uploaded; desired state waits", _deviceId);
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

    /// <summary>
    /// The server has seen the reader once it processed the list, or every per-user message sent
    /// with it. Absences are part of either.
    /// </summary>
    private static List<IReadOnlyList<string>> Alternatives(
        RosterUpload roster, List<string> perUser, List<string> absences)
    {
        var alternatives = new List<IReadOnlyList<string>>();
        if (roster.MessageId != null)
        {
            alternatives.Add([roster.MessageId, .. absences]);
        }

        if (perUser.Count > 0 || (roster.MessageId == null && absences.Count > 0))
        {
            alternatives.Add([.. perUser, .. absences]);
        }

        return alternatives;
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
            var sent = new List<string>(await _observations.UploadAsync(_deviceId, scan.Stamp, listed, scan.Hashes, cancellationToken)
                .ConfigureAwait(false));
            var missing = Missing(listed);
            if (missing.Count > 0)
            {
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
            _log.LogWarning(ex, "Reader {DeviceId}: the observation was not uploaded; desired state waits", _deviceId);
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
    /// reader is read and uploaded first. A failed or unanswered check is not processed; an
    /// observation the server has not processed within <see cref="DesiredPathOptions.FenceTimeout"/>
    /// is sent again on a later pass.
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
            if (_gate == ObservationGate.Fenced && _worker.Now - _fencedAt >= _options.FenceTimeout)
            {
                _log.LogWarning(
                    "Reader {DeviceId}: the server has not processed the reader observation after {Seconds}s; it is read and sent again",
                    _deviceId, (int)_options.FenceTimeout.TotalSeconds);
                _gate = ObservationGate.Unobserved;
            }
        }

        _log.LogInformation("Reader {DeviceId}: desired state waits until the server has processed the reader observation", _deviceId);
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
        foreach (var ids in fence)
        {
            try
            {
                if (await _client.ObservationProcessedAsync(_deviceId, ids, cancellationToken).ConfigureAwait(false))
                {
                    return true;
                }
            }
            catch (Exception ex) when (ex is not OperationCanceledException)
            {
                _log.LogInformation(ex, "Reader {DeviceId}: observation check failed; desired state waits", _deviceId);
            }
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
                "Reader {DeviceId}: no trusted user list ({Error}); desired state waits until one is processed",
                _deviceId, observed.Ok ? "the count does not match the announced total" : observed.Error);
            return false;
        }

        await UploadTrustedListAsync(scan, false, false, cancellationToken).ConfigureAwait(false);
        return Gate is ObservationGate.Fenced or ObservationGate.Open;
    }

    private static List<ReaderUser> Listed(ReaderListResult observed) =>
        observed.Users.Where(user => !string.IsNullOrWhiteSpace(user.DeviceUserId)).ToList();

    /// <summary>
    /// One hash per id. Equal hashes stay separate observations. With the reader's photo checksum
    /// list, a user it does not list has no photo, and only a photo whose checksum is not cached is
    /// downloaded. Without that list every photo is downloaded.
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
        var toDownload = listed.Count(user => checksums.ByUserId.TryGetValue(user.DeviceUserId, out var sum)
            && (sum.Length == 0 || !_memory.Faces.TryGet(sum, out _)));
        var downloads = new DownloadProgress(_log, _deviceId, toDownload);
        foreach (var deviceUserId in listed.Select(user => user.DeviceUserId))
        {
            if (!checksums.ByUserId.TryGetValue(deviceUserId, out var checksum))
            {
                continue;
            }

            if (checksum.Length == 0 || !_memory.Faces.TryGet(checksum, out var hash))
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

            hashes[deviceUserId] = hash;
            if (checksum.Length > 0)
            {
                known[checksum] = hash;
            }
        }

        _memory.Faces.Replace(known);
        downloads.Done(hashes.Count);
        return hashes;
    }

    private Dictionary<string, string> DownloadedFaceHashes(IReadOnlyList<ReaderUser> listed, Dictionary<string, byte[]> photos)
    {
        var hashes = new Dictionary<string, string>(StringComparer.Ordinal);
        var downloads = new DownloadProgress(_log, _deviceId, listed.Count);
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
        private readonly string _deviceId;
        private readonly int _total;
        private readonly long _started = Stopwatch.GetTimestamp();
        private int _done;

        public DownloadProgress(ILogger log, string deviceId, int total)
        {
            _log = log;
            _deviceId = deviceId;
            _total = total;
            if (total > 0)
            {
                _log.LogInformation("Reader {DeviceId}: downloading {Total} photos from the reader", deviceId, total);
            }
        }

        public void One()
        {
            _done++;
            if (_done % Every == 0 && _done < _total)
            {
                _log.LogInformation("Reader {DeviceId}: downloaded {Done}/{Total} photos ({Seconds:0} s)",
                    _deviceId, _done, _total, Stopwatch.GetElapsedTime(_started).TotalSeconds);
            }
        }

        public void Done(int withPhoto)
        {
            if (_total > 0)
            {
                _log.LogInformation("Reader {DeviceId}: photos checked, {WithPhoto} users have a photo; {Done} downloaded in {Seconds:0} s",
                    _deviceId, withPhoto, _done, Stopwatch.GetElapsedTime(_started).TotalSeconds);
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
            "Reader {DeviceId}: desired state was still changing after {Passes} passes; the next notice or retry continues",
            _deviceId, MaxPasses);
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
        var progress = new ApplyProgress(_log, _deviceId, from);
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
        private readonly ILogger _log;
        private readonly string _deviceId;
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

        public ApplyProgress(ILogger log, string deviceId, long from)
        {
            _log = log;
            _deviceId = deviceId;
            _from = from;
        }

        public void Page(int items, long target)
        {
            _target = Math.Max(_target, target);
            if (items > 0 && !_announced)
            {
                _announced = true;
                _log.LogInformation("Reader {DeviceId}: applying changes after revision {From} up to revision {Target}",
                    _deviceId, _from, _target);
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
                        "Reader {DeviceId}: {Action} user {UserId}, read back OK (revision {Revision} of {Target}) in {Ms} ms",
                        _deviceId, item.Present ? "wrote" : "removed", item.DeviceUserId, item.Revision, _target,
                        (long)took.TotalMilliseconds);
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
                    "Reader {DeviceId}: {Checked} changes checked so far (revision {Revision} of {Target}), {Seconds:0} s",
                    _deviceId, _checked, item.Revision, _target, Stopwatch.GetElapsedTime(_started).TotalSeconds);
            }
        }

        public void Finished()
        {
            if (!_announced)
            {
                return;
            }

            _log.LogInformation(
                "Reader {DeviceId}: changes up to revision {Target} done in {Seconds:0} s: {Written} written, {Matched} already matched, {Failed} failed (retried later), {Reported} reported to the server for review, {Waiting} waiting",
                _deviceId, _target, Stopwatch.GetElapsedTime(_started).TotalSeconds, _written, _matched, _failed, _reported,
                _waiting);
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
                "Reader {DeviceId}: revision {Revision} for user {UserId} is no longer desired by the server; its failed attempt ({Error}) is dropped",
                _deviceId, attempt.Revision, attempt.DeviceUserId, attempt.LastError);
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
                "Reader {DeviceId}: revision {Revision} was replaced before its report ({Message})",
                _deviceId, item.Revision, ex.Message);
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            _log.LogWarning(ex,
                "Reader {DeviceId}: revision {Revision} for user {UserId} was not written and the report failed; it is retried",
                _deviceId, item.Revision, item.DeviceUserId);
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
                "Reader {DeviceId}: revision {Revision} for user {UserId} failed attempt {Attempt} ({Error}); next at {Next:o}",
                _deviceId, attempt.Revision, attempt.DeviceUserId, attempt.AttemptCount, attempt.LastError, attempt.NextAttempt);
            return;
        }

        if (attempt.Reported)
        {
            return;
        }

        _log.LogWarning(
            "Reader {DeviceId}: revision {Revision} for user {UserId} is {State} after {Attempt} attempts ({Error}); newer revisions continue",
            _deviceId, attempt.Revision, attempt.DeviceUserId, attempt.State, attempt.AttemptCount, attempt.LastError);
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
            _log.LogWarning(ex, "Reader {DeviceId}: failure of revision {Revision} was not reported; it is reported again later",
                _deviceId, attempt.Revision);
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

            _log.LogWarning("Reader {DeviceId}: revision {Revision} is not acknowledged ({Error}); it is applied again",
                _deviceId, revision, error);
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

        var hash = Convert.ToHexString(SHA256.HashData(face.Bytes)).ToLowerInvariant();
        await _client.AcknowledgeAsync(new DesiredAcknowledgement(
            _deviceId,
            item.Revision,
            read.DeviceUserId,
            read.Name,
            read.NameEx,
            read.UserStatus,
            ReaderLocalTime.Format(read.ValidFrom.Value),
            ReaderLocalTime.Format(read.ValidTo.Value),
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

        await _client.AcknowledgeAsync(new DesiredAcknowledgement(
            _deviceId,
            item.Revision,
            read.DeviceUserId,
            read.Name,
            read.NameEx,
            read.UserStatus,
            ReaderLocalTime.Format(read.ValidFrom.Value),
            ReaderLocalTime.Format(read.ValidTo.Value),
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
/// <see cref="FenceTimeout"/>: how long an observation may wait for the server before the reader is
/// read and sent again. <see cref="ReconnectWait"/>: how long a reconnect checks for that
/// processing before leaving desired state to the next notice or retry. <see cref="ObserveRetry"/>:
/// the least time between two reads made only to observe the reader.
/// </summary>
public sealed record DesiredPathOptions
{
    public ILogger? Log { get; init; }

    public TimeSpan FenceTimeout { get; init; } = TimeSpan.FromMinutes(2);

    public TimeSpan ReconnectWait { get; init; } = TimeSpan.FromSeconds(5);

    public TimeSpan ReconnectPoll { get; init; } = TimeSpan.FromMilliseconds(250);

    public TimeSpan ObserveRetry { get; init; } = TimeSpan.FromSeconds(30);
}
