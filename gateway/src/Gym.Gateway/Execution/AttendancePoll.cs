using Gym.Gateway.Adapters;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;

namespace Gym.Gateway.Execution;

/// <param name="Overlap">How far before the last stored punch the next window starts.</param>
/// <param name="FirstLookback">
/// How far back the first window of a reader with no stored punch reaches. Zero starts at now,
/// so history already on the reader is left there.
/// </param>
public sealed record AttendancePollSettings(TimeSpan Overlap, TimeSpan FirstLookback)
{
    public static AttendancePollSettings From(GatewayOptions options) => new(
        TimeSpan.FromSeconds(Math.Max(0, options.AttendanceOverlapSeconds)),
        TimeSpan.FromHours(Math.Max(0, options.AttendanceFirstLookbackHours)));
}

/// <param name="Read">Punches the reader returned for the window.</param>
/// <param name="Sent">Punches posted to the server (not yet confirmed stored by this poll).</param>
/// <param name="Stored">Punches the server stored for the first time.</param>
public sealed record AttendancePollResult(
    bool Ok, DateTimeOffset FromUtc, DateTimeOffset ToUtc, int Read, int Sent, int Stored, string? Error);

/// <summary>
/// Reads one time window of one reader's log and posts the punches the server has not confirmed.
/// The window starts at the server's last stored punch time less a small overlap and ends now. A reader
/// with no stored punch starts at now, less the overlap, and does not import older history. A window
/// with nothing new posts nothing. Punches are uploaded oldest first in batches; a failed batch stops
/// the poll, so the server's cursor never passes a punch it has not stored. Only the query holds the
/// reader's connection lock. The log is never cleared, and record numbers are not used to choose what
/// to read. Member state is not touched.
/// </summary>
public sealed class AttendancePoll
{
    /// <summary>The most punches the server accepts in one upload.</summary>
    public const int MaxBatch = 500;

    /// <summary>Punches per upload, small enough that the server stores them well inside the HTTP timeout.</summary>
    public const int UploadBatch = 100;

    private readonly string _deviceId;
    private readonly string _readerLabel;
    private readonly IReaderAdapter _reader;
    private readonly IAttendanceClient _client;
    private readonly AttendancePollSettings _settings;
    private readonly ILogger _log;
    private readonly Func<DateTimeOffset> _clock;
    private readonly Dictionary<string, DateTimeOffset> _confirmed = new(StringComparer.Ordinal);
    private bool _cursorKnown;
    private DateTimeOffset? _lastStored;

    public AttendancePoll(
        string deviceId,
        IReaderAdapter reader,
        IAttendanceClient client,
        AttendancePollSettings settings,
        ILogger? log = null,
        Func<DateTimeOffset>? clock = null,
        string? readerLabel = null)
    {
        _deviceId = deviceId;
        _readerLabel = string.IsNullOrWhiteSpace(readerLabel) ? deviceId : readerLabel.Trim();
        _reader = reader ?? throw new ArgumentNullException(nameof(reader));
        _client = client ?? throw new ArgumentNullException(nameof(client));
        _settings = settings;
        _log = log ?? NullLogger.Instance;
        _clock = clock ?? (() => DateTimeOffset.UtcNow);
    }

    public string DeviceId => _deviceId;

    /// <summary>The reader's last stored punch time as the server last reported it.</summary>
    public DateTimeOffset? LastStored => _lastStored;

    public async Task<AttendancePollResult> PollOnceAsync(CancellationToken cancellationToken)
    {
        if (!_cursorKnown)
        {
            _lastStored = await _client.LastStoredAsync(_deviceId, cancellationToken).ConfigureAwait(false);
            _cursorKnown = true;
        }

        var to = _clock();
        if (_lastStored > to)
        {
            _lastStored = to;
        }

        var from = (_lastStored ?? to - _settings.FirstLookback) - _settings.Overlap;
        var read = await Task.Run(() => _reader.QueryAttendance(from, to), cancellationToken).ConfigureAwait(false);
        if (!read.Ok)
        {
            _log.LogWarning("Reader {Reader}: attendance read failed for {From:o}..{To:o}: {Error}",
                _readerLabel, from, to, read.Error);
            return new AttendancePollResult(false, from, to, 0, 0, 0, read.Error);
        }

        if (read.MalformedSkipped > 0)
        {
            _log.LogWarning(
                "Reader {Reader}: stage=attendance; skipped {Skipped} punch(es) with unreadable time in {From:o}..{To:o}; valid rows continue",
                _readerLabel, read.MalformedSkipped, from, to);
        }

        var fresh = read.Punches
            .Where(p => !_confirmed.ContainsKey(Key(p)))
            .OrderBy(p => p.OccurredAtUtc)
            .ThenBy(p => p.RecordNumber ?? long.MaxValue)
            .ToArray();
        if (fresh.Length == 0)
        {
            return new AttendancePollResult(true, from, to, read.Punches.Count, 0, 0, null);
        }

        var sent = 0;
        var stored = 0;
        foreach (var batch in fresh.Chunk(UploadBatch))
        {
            AttendanceStored result;
            try
            {
                result = await _client.StoreAsync(_deviceId, batch, cancellationToken).ConfigureAwait(false);
            }
            catch (Exception ex) when (ex is not OperationCanceledException || !cancellationToken.IsCancellationRequested)
            {
                _log.LogWarning(ex,
                    "Reader {Reader}: attendance upload failed after {Sent} of {Fresh}; the rest is read again next time",
                    _readerLabel, sent, fresh.Length);
                Forget();
                return new AttendancePollResult(false, from, to, read.Punches.Count, sent, stored, ex.Message);
            }

            Advance(result.LastEventAt, to);
            foreach (var punch in batch)
            {
                _confirmed[Key(punch)] = punch.OccurredAtUtc;
            }

            sent += batch.Length;
            stored += result.Stored;
        }

        Forget();
        _log.LogInformation("Reader {Reader}: attendance {From:o}..{To:o} read {Read}, sent {Sent}, stored {Stored}",
            _readerLabel, from, to, read.Punches.Count, sent, stored);
        return new AttendancePollResult(true, from, to, read.Punches.Count, sent, stored, null);
    }

    /// <summary>
    /// The server's last stored punch time moves the next window forward, never backward, and never
    /// past this window's end: a punch dated ahead of the clock must not make later punches unread.
    /// </summary>
    private void Advance(DateTimeOffset? lastEventAt, DateTimeOffset windowEnd)
    {
        if (lastEventAt is not { } last)
        {
            return;
        }

        var capped = last > windowEnd ? windowEnd : last;
        if (_lastStored is null || capped > _lastStored)
        {
            _lastStored = capped;
        }
    }

    /// <summary>A punch older than the next window's start is not read again.</summary>
    private void Forget()
    {
        if (_lastStored is not { } last)
        {
            return;
        }

        var start = last - _settings.Overlap;
        foreach (var key in _confirmed.Where(e => e.Value < start).Select(e => e.Key).ToList())
        {
            _confirmed.Remove(key);
        }
    }

    private static string Key(ReaderPunch punch) =>
        $"{punch.DeviceUserId}|{punch.OccurredAtUtc.UtcTicks}|{punch.Method}|{punch.Granted}|{punch.RecordNumber}";
}
