using System.Collections.Concurrent;
using System.Diagnostics;
using System.Text.Json;
using System.Threading.Channels;
using Gym.Gateway.Adapters;
using Gym.Gateway.Config;
using Gym.Gateway.Execution;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace Gym.Gateway;

public sealed class GatewayWorker : BackgroundService
{
    private readonly GatewayOptions _options;
    private readonly ILogger<GatewayWorker> _log;
    private readonly ILoggerFactory _logFactory;
    private readonly BackendLink _link;
    private readonly Dictionary<string, IDeviceAdapter> _adapters = new(StringComparer.Ordinal);
    private readonly Channel<OutboundMessage> _outbound = Channel.CreateUnbounded<OutboundMessage>(
        new UnboundedChannelOptions { SingleReader = true });
    private readonly DeviceLocks _locks;
    private readonly ConcurrentDictionary<string, Channel<GatewayEnvelope>> _inbox = new(StringComparer.Ordinal);
    private CommandDispatcher? _dispatcher;
    private readonly DesiredRevisionHub _desiredRevisions;
    private readonly ReaderLabels _readerLabels = new();
    private readonly IReaderAdapterFactory? _readerFactory;
    private readonly IDesiredStateClient _desired;
    private readonly IAttendanceClient _attendance;
    private readonly string _readerJournalDirectory;
    private readonly List<ReaderWorker> _readerWorkers = [];
    private readonly List<AttendancePoll> _attendancePolls = [];
    private readonly ConcurrentDictionary<string, byte> _attendanceSkipped = new(StringComparer.Ordinal);
    private readonly HashSet<string> _attachedReaders = new(StringComparer.Ordinal);
    private readonly object _readersGate = new();
    private readonly TimeSpan? _listAcceptanceTimeout;
    private bool _readersAttached;
    private CancellationToken _stop;

    public GatewayWorker(
        GatewayOptions options,
        BackendLink link,
        ILogger<GatewayWorker> log,
        ILoggerFactory logFactory,
        IReaderAdapterFactory readers)
        : this(options, link, log, logFactory, readers, desired: null, journalDirectory: null)
    {
    }

    internal GatewayWorker(
        GatewayOptions options,
        BackendLink link,
        ILogger<GatewayWorker> log,
        ILoggerFactory logFactory,
        IReaderAdapterFactory? readers,
        IDesiredStateClient? desired,
        string? journalDirectory,
        IAttendanceClient? attendance = null,
        DeviceLocks? locks = null,
        TimeSpan? listAcceptanceTimeout = null)
    {
        _options = options;
        _link = link;
        _log = log;
        _logFactory = logFactory;
        _locks = locks ?? new DeviceLocks(logFactory.CreateLogger<DeviceLocks>());
        SeedReaderLabels();
        _desiredRevisions = new DesiredRevisionHub(logFactory.CreateLogger<DesiredRevisionHub>(), _readerLabels);
        _readerFactory = readers;
        _desired = desired ?? DesiredStateClient.Create(options.BackendUrl, options.Token);
        _attendance = attendance ?? AttendanceClient.Create(options.BackendUrl, options.Token);
        _readerJournalDirectory = string.IsNullOrWhiteSpace(journalDirectory)
            ? Path.Combine(GatewayConfigStore.DefaultConfigDirectory(), "reader-journal")
            : journalDirectory;
        _listAcceptanceTimeout = listAcceptanceTimeout;
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        _stop = stoppingToken;
        await RefreshReaderNamesAsync(stoppingToken).ConfigureAwait(false);
        foreach (var device in _options.Devices.Where(d => !string.IsNullOrWhiteSpace(d.DeviceId)))
        {
            var adapter = new TimedDeviceAdapter(
                DeviceAdapterFactory.Create(_options.Adapter), _logFactory.CreateLogger<TimedDeviceAdapter>());
            var status = adapter.Connect(new DeviceConnectionConfig(
                device.DeviceId, device.Ip, device.Port, device.Username, device.Password,
                _options.NativeDirectory));
            adapter.RegisterEventListener(new ForwardingListener(device.DeviceId, _outbound.Writer));
            _adapters[device.DeviceId] = adapter;
            _log.LogInformation(
                "Reader {Reader} adapter={Adapter} state={State} error={Error}",
                _readerLabels.Of(device.DeviceId), _options.Adapter, status.ConnectionState, status.Error);
            await EnqueueStatus(device.DeviceId, status).ConfigureAwait(false);
            if (status.Ok)
            {
                var info = adapter.GetDeviceInfo();
                await _outbound.Writer.WriteAsync(new OutboundMessage(
                    ProtocolTypes.DeviceMetadata,
                    device.DeviceId,
                    new
                    {
                        connectionState = status.ConnectionState,
                        serial = info.SerialNumber,
                        model = "TrueFace3000",
                        channelCount = info.ChannelCount
                    },
                    null), stoppingToken).ConfigureAwait(false);
            }
        }

        _dispatcher = new CommandDispatcher(
            _adapters, _logFactory.CreateLogger<CommandDispatcher>(), _locks, _desiredRevisions.ScanNowAsync);
        AttachReaders();

        var sendLoop = SendLoopAsync(_link, stoppingToken);
        var wsLoop = _link.RunWebSocketAsync(
            AcceptCommand, _desiredRevisions.HandleAsync, ReconnectReadersAsync, stoppingToken);
        var heartbeat = HeartbeatLoopAsync(_link, stoppingToken);
        var poll = PollLoopAsync(_link, stoppingToken);
        var observe = ObserveReadersAsync(stoppingToken);
        var retry = RetryReadersAsync(stoppingToken);
        var attendance = AttendanceLoopAsync(stoppingToken);
        var clock = TimeSyncLoopAsync(stoppingToken);
        var statusLog = StatusLoopAsync(stoppingToken);
        var readerHealth = ReaderHealthLoopAsync(stoppingToken);

        await _outbound.Writer.WriteAsync(new OutboundMessage(
            ProtocolTypes.RegisterGateway,
            null,
            new { agentVersion = "gym-gateway-0.5" },
            null), stoppingToken).ConfigureAwait(false);

        await Task.WhenAll(sendLoop, wsLoop, heartbeat, poll, observe, retry, attendance, clock, statusLog, readerHealth)
            .ConfigureAwait(false);
    }

    /// <summary>Logs in again to readers whose session broke, each when its pause has passed.</summary>
    private async Task ReaderHealthLoopAsync(CancellationToken stoppingToken)
    {
        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                await Task.Delay(TimeSpan.FromSeconds(5), stoppingToken).ConfigureAwait(false);
                await CheckReaderHealthAsync(stoppingToken).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                break;
            }
            catch (Exception ex)
            {
                _log.LogWarning(ex, "Reader reconnect check failed");
            }
        }
    }

    /// <summary>One pass of the health loop: every reader whose reconnect is due logs in again.</summary>
    internal async Task CheckReaderHealthAsync(CancellationToken stoppingToken)
    {
        foreach (var (deviceId, adapter) in _adapters)
        {
            if (adapter is not TimedDeviceAdapter { ReconnectDue: true } reader)
            {
                continue;
            }

            _log.LogInformation("Reader {Reader}: reconnecting ({Detail})", _readerLabels.Of(deviceId), reader.HealthDetail);
            DeviceConnectionStatus status;
            using (await _locks.AcquireAsync(deviceId, "reconnecting the reader", stoppingToken).ConfigureAwait(false))
            {
                status = reader.Reconnect();
            }

            await EnqueueStatus(deviceId, status).ConfigureAwait(false);
            if (status.Ok)
            {
                await ReaderBackAsync(deviceId, stoppingToken).ConfigureAwait(false);
            }
        }
    }

    /// <summary>
    /// A reader that logged in again is read and its observation processed before desired state is
    /// applied, as after a server reconnect. A reader that was off when the gateway started gets its
    /// worker now.
    /// </summary>
    internal async Task ReaderBackAsync(string deviceId, CancellationToken cancellationToken)
    {
        if (_readersAttached)
        {
            AttachReader(deviceId);
        }

        try
        {
            await _desiredRevisions.ReconnectAsync(deviceId, cancellationToken).ConfigureAwait(false);
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            _log.LogWarning(ex, "Reader {Reader}: desired state was not applied after the reader came back", _readerLabels.Of(deviceId));
        }
    }

    /// <summary>The server knows ONLINE / OFFLINE / UNKNOWN; a reader with a broken session cannot take work.</summary>
    private static string ServerState(string state) =>
        state == TimedDeviceAdapter.DegradedState ? "OFFLINE" : state;

    /// <summary>Every minute: what the gateway and each reader are doing, so a quiet log still shows progress.</summary>
    private async Task StatusLoopAsync(CancellationToken stoppingToken)
    {
        var interval = TimeSpan.FromSeconds(Math.Max(15, _options.StatusLogSeconds));
        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                await Task.Delay(interval, stoppingToken).ConfigureAwait(false);
                var link = _link.WebSocketLive
                    ? "Gateway is connected."
                    : "Gateway is not on the live connection. It is using a slower backup connection.";
                var statuses = _desiredRevisions.StatusLines();
                if (statuses.Count == 0)
                {
                    _log.LogInformation("{Link} No readers are attached, so member sync is not running.", link);
                }
                else
                {
                    foreach (var status in statuses)
                    {
                        _log.LogInformation("{Link} {Status}", link, status);
                    }
                }
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                break;
            }
            catch (Exception ex)
            {
                _log.LogWarning(ex, "Status log failed");
            }
        }
    }

    /// <summary>
    /// Sets each reader clock to India local time on start, then daily. Punch timestamps stay UTC.
    /// </summary>
    private async Task TimeSyncLoopAsync(CancellationToken stoppingToken)
    {
        var pending = _adapters.Keys.ToHashSet(StringComparer.Ordinal);
        var nextFullSync = DateTimeOffset.UtcNow.AddHours(24);
        while (!stoppingToken.IsCancellationRequested)
        {
            await SyncPendingClocksAsync(pending, stoppingToken).ConfigureAwait(false);

            try
            {
                var wait = pending.Count > 0 ? TimeSpan.FromMinutes(10) : nextFullSync - DateTimeOffset.UtcNow;
                await Task.Delay(wait > TimeSpan.Zero ? wait : TimeSpan.Zero, stoppingToken).ConfigureAwait(false);
                if (DateTimeOffset.UtcNow >= nextFullSync)
                {
                    pending.UnionWith(_adapters.Keys);
                    nextFullSync = DateTimeOffset.UtcNow.AddHours(24);
                }
            }
            catch (OperationCanceledException)
            {
                break;
            }
        }
    }

    private async Task SyncPendingClocksAsync(HashSet<string> pending, CancellationToken stoppingToken)
    {
        foreach (var deviceId in pending.ToList())
        {
            if (await SyncClockAsync(deviceId, _adapters[deviceId], stoppingToken).ConfigureAwait(false))
            {
                pending.Remove(deviceId);
            }
        }

        if (pending.Count > 0)
        {
            _log.LogInformation("Reader clock: {Count} reader(s) not set yet; retrying in 10 min", pending.Count);
        }
    }

    internal Task<bool> SyncReaderClockAsync(string deviceId, IDeviceAdapter adapter, CancellationToken cancellationToken) =>
        SyncClockAsync(deviceId, adapter, cancellationToken);

    private async Task<bool> SyncClockAsync(string deviceId, IDeviceAdapter adapter, CancellationToken stoppingToken)
    {
        if (adapter.GetHealth().ConnectionState != TimedDeviceAdapter.OnlineState)
        {
            return false;
        }

        using var lease = await _locks.AcquireAsync(deviceId, "setting the reader clock", stoppingToken)
            .ConfigureAwait(false);
        try
        {
            var result = adapter.SynchronizeTime(ReaderLocalClock.Now(DateTimeOffset.UtcNow));
            if (result.Ok)
            {
                _log.LogInformation("Reader {Reader}: clock set to gateway time", _readerLabels.Of(deviceId));
                return true;
            }

            _log.LogWarning("Reader {Reader}: time sync failed: {Error}", _readerLabels.Of(deviceId), result.Error);
        }
        catch (Exception ex)
        {
            _log.LogWarning(ex, "Reader {Reader}: time sync failed", _readerLabels.Of(deviceId));
        }

        return false;
    }

    public override async Task StopAsync(CancellationToken cancellationToken)
    {
        foreach (var adapter in _adapters.Values)
        {
            adapter.Dispose();
        }

        DisposeReaders();
        _outbound.Writer.TryComplete();
        await base.StopAsync(cancellationToken).ConfigureAwait(false);
    }

    public override void Dispose()
    {
        DisposeReaders();
        base.Dispose();
    }

    /// <summary>
    /// Binds <see cref="ReaderWorker"/> to each configured reader that has an adapter. A device
    /// with no adapter is left unwired, and a desired revision for it is not acknowledged. A reader
    /// that is not online yet is bound when it comes back.
    /// </summary>
    internal void AttachReaders()
    {
        if (_readersAttached)
        {
            return;
        }

        _readersAttached = true;
        if (_readerFactory == null)
        {
            _log.LogInformation("No reader adapter is configured; desired revisions will not be acknowledged");
            return;
        }

        foreach (var deviceId in _options.Devices
                     .Select(device => device.DeviceId)
                     .Where(deviceId => !string.IsNullOrWhiteSpace(deviceId)))
        {
            AttachReader(deviceId);
        }
    }

    private void AttachReader(string deviceId)
    {
        if (_readerFactory == null)
        {
            return;
        }

        lock (_readersGate)
        {
            if (_attachedReaders.Contains(deviceId))
            {
                return;
            }

            var opened = _readerFactory.Open(deviceId, OnlineAdapter(deviceId));
            if (opened == null)
            {
                _log.LogInformation(
                    "Reader {Reader} has no worker; desired revisions will not be acknowledged until it is online",
                    _readerLabels.Of(deviceId));
                return;
            }

            _attachedReaders.Add(deviceId);
            var readerLabel = _readerLabels.Of(deviceId);
            var reader = new LockedReaderAdapter(deviceId, opened, _locks);
            _attendancePolls.Add(new AttendancePoll(
                deviceId, reader, _attendance, AttendancePollSettings.From(_options),
                _logFactory.CreateLogger<AttendancePoll>(), readerName: () => _readerLabels.Of(deviceId)));
            var journal = Path.Combine(_readerJournalDirectory, JournalFile(deviceId));
            var worker = new ReaderWorker(deviceId, reader, journal, online: () => !KnownOffline(deviceId));
            _readerWorkers.Add(worker);
            if (worker.IsNewJournal)
            {
                var dropped = _link.DropObservations(deviceId);
                if (dropped > 0)
                {
                    _log.LogInformation(
                        "Reader {Reader}: the scan journal is new; {Count} queued observation(s) from before it were dropped and the reader is read again",
                        readerLabel, dropped);
                }
            }

            IReaderObservationUpload observations = new ReaderObservationUpload(
                _options.Id, _link, _logFactory.CreateLogger<ReaderObservationUpload>(),
                acceptanceTimeout: _listAcceptanceTimeout, labels: _readerLabels);
            var memory = new ReaderScanMemory(
                new FaceChecksumCache(Path.ChangeExtension(journal, ".faces.json")),
                new ScanUploadState(TimeSpan.FromMinutes(Math.Max(1, _options.RosterRefreshMinutes))));
            var path = new DesiredRevisionPath(
                deviceId, worker, reader, _desired, observations, memory,
                new DesiredPathOptions
                {
                    Log = _logFactory.CreateLogger<DesiredRevisionPath>(),
                    ReaderName = readerLabel,
                    IsMessagePending = id => _link.IsPending(id),
                    PendingListMessageId = id => _link.PendingListMessageId(id),
                    AcknowledgeMessage = id => _link.Acknowledge(id)
                });
            _desiredRevisions.Attach(
                deviceId,
                path.HandleAsync,
                path.ReconnectAsync,
                ct => path.BootstrapAsync(ct),
                path.ScanNowAsync);
            _desiredRevisions.AttachRetry(deviceId, path.RetryAsync);
            _desiredRevisions.AttachStatus(deviceId, path.StatusText);
            _desiredRevisions.AttachGate(deviceId, () => path.Gate);
            _log.LogInformation("Reader {Reader}: attached for desired state and device→server observations", readerLabel);
        }
    }

    private void SeedReaderLabels()
    {
        foreach (var device in _options.Devices.Where(d => !string.IsNullOrWhiteSpace(d.DeviceId)))
        {
            _readerLabels.Remember(device.DeviceId, device.Name);
        }
    }

    private async Task RefreshReaderNamesAsync(CancellationToken cancellationToken)
    {
        try
        {
            var remote = await new GatewayEnrollmentClient()
                .ListDevicesAsync(_options.BackendUrl, _options.Token, cancellationToken)
                .ConfigureAwait(false);
            foreach (var device in remote)
            {
                if (string.IsNullOrWhiteSpace(device.Id) || string.IsNullOrWhiteSpace(device.Name))
                {
                    continue;
                }

                _readerLabels.Remember(device.Id, device.Name);
                var local = _options.Devices.FirstOrDefault(d =>
                    string.Equals(d.DeviceId, device.Id, StringComparison.Ordinal));
                if (local != null)
                {
                    local.Name = device.Name.Trim();
                }
            }
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            _log.LogInformation(ex, "Reader names were not refreshed from the server; using configured names when present");
        }
    }

    internal bool HasWorker(string deviceId)
    {
        lock (_readersGate)
        {
            return _attachedReaders.Contains(deviceId);
        }
    }

    internal Task ReconnectReadersAsync(CancellationToken cancellationToken) =>
        _desiredRevisions.ReconnectAsync(cancellationToken);

    internal Task ScanReadersAsync(CancellationToken cancellationToken) =>
        _desiredRevisions.ObserveAsync(cancellationToken);

    private async Task ObserveReadersAsync(CancellationToken cancellationToken)
    {
        var interval = TimeSpan.FromSeconds(Math.Max(15, _options.RosterPollSeconds));
        while (!cancellationToken.IsCancellationRequested)
        {
            try
            {
                await Task.Delay(interval, cancellationToken).ConfigureAwait(false);
                await _desiredRevisions.ObserveAsync(cancellationToken).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                break;
            }
        }
    }

    /// <summary>
    /// Retries failed revisions when their backoff passes, unsent acknowledgements, and readers whose
    /// observation the server has not yet processed. A reader with nothing to retry is not touched.
    /// </summary>
    private async Task RetryReadersAsync(CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            try
            {
                await Task.Delay(TimeSpan.FromSeconds(10), cancellationToken).ConfigureAwait(false);
                await _desiredRevisions.RetryAsync(cancellationToken).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                break;
            }
        }
    }

    /// <summary>Reads each reader's attendance window on its own schedule, beside the reader worker.</summary>
    private async Task AttendanceLoopAsync(CancellationToken cancellationToken)
    {
        var interval = TimeSpan.FromSeconds(Math.Clamp(_options.AttendancePollSeconds, 2, 60));
        while (!cancellationToken.IsCancellationRequested)
        {
            try
            {
                await PollAttendanceAsync(cancellationToken).ConfigureAwait(false);
                await Task.Delay(interval, cancellationToken).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                break;
            }
        }
    }

    /// <summary>One window per reader. One reader's failure does not stop the others.</summary>
    internal Task PollAttendanceAsync(CancellationToken cancellationToken) =>
        Task.WhenAll(AttendancePolls().Select(async poll =>
        {
            try
            {
                var gate = _desiredRevisions.GateOf(poll.DeviceId);
                if (gate is ObservationGate.Observing or ObservationGate.Fenced)
                {
                    if (_attendanceSkipped.TryAdd(poll.DeviceId, 0))
                    {
                        _log.LogInformation(
                            "Reader {Reader}: attendance waits while the reader list is still being saved (gate={Gate})",
                            _readerLabels.Of(poll.DeviceId), gate);
                    }

                    return;
                }

                _attendanceSkipped.TryRemove(poll.DeviceId, out _);
                await poll.PollOnceAsync(cancellationToken).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                throw;
            }
            catch (Exception ex)
            {
                _log.LogWarning(ex, "Reader {Reader}: attendance poll failed; the same window is read again next time",
                    _readerLabels.Of(poll.DeviceId));
            }
        }));

    internal Task ReceiveDesiredAsync(DesiredRevisionNotice notice, CancellationToken cancellationToken) =>
        _desiredRevisions.HandleAsync(notice, cancellationToken);

    internal void UseConnectedAdapter(IDeviceAdapter adapter)
    {
        var deviceId = string.IsNullOrWhiteSpace(adapter.DeviceId)
            ? _options.Devices.FirstOrDefault(device => !string.IsNullOrWhiteSpace(device.DeviceId))?.DeviceId
            : adapter.DeviceId;
        if (string.IsNullOrWhiteSpace(deviceId))
        {
            throw new ArgumentException("A connected adapter needs a device id.", nameof(adapter));
        }

        _adapters[deviceId] = adapter;
    }

    private IDeviceAdapter? OnlineAdapter(string deviceId)
    {
        if (!_adapters.TryGetValue(deviceId, out var adapter))
        {
            return null;
        }

        return string.Equals(adapter.GetHealth().ConnectionState, TimedDeviceAdapter.OnlineState, StringComparison.Ordinal)
            ? adapter
            : null;
    }

    private bool KnownOffline(string deviceId) =>
        _adapters.TryGetValue(deviceId, out var adapter)
        && !string.Equals(adapter.GetHealth().ConnectionState, TimedDeviceAdapter.OnlineState, StringComparison.Ordinal);

    private static string JournalFile(string deviceId)
    {
        var invalid = Path.GetInvalidFileNameChars();
        var name = new string(deviceId.Select(ch => invalid.Contains(ch) ? '_' : ch).ToArray());
        return name + ".sqlite";
    }

    private AttendancePoll[] AttendancePolls()
    {
        lock (_readersGate)
        {
            return [.. _attendancePolls];
        }
    }

    private void DisposeReaders()
    {
        lock (_readersGate)
        {
            foreach (var worker in _readerWorkers)
            {
                worker.Dispose();
            }

            _readerWorkers.Clear();
        }
    }

    private async Task HandleCommandAsync(BackendLink link, CommandDispatcher dispatcher, GatewayEnvelope command)
    {
        _log.LogDebug("Command start {Type} device={DeviceId} corr={Corr}",
            command.Type, command.DeviceId, command.CorrelationId);
        var timer = Stopwatch.StartNew();
        var outcome = await dispatcher.DispatchAsync(command).ConfigureAwait(false);
        timer.Stop();
        LogOutcome(command, outcome, timer.ElapsedMilliseconds);
        await PublishOutcome(link, command, outcome).ConfigureAwait(false);
    }

    /// <summary>One line per command: what ran, on which reader, the result and how long it took.</summary>
    private void LogOutcome(GatewayEnvelope command, DispatchOutcome outcome, long elapsedMs)
    {
        if (!outcome.Ok)
        {
            var result = JsonSerializer.SerializeToElement(outcome.Payload, JsonOptions.Outbound);
            _log.LogWarning("Command {Type} device={DeviceId} corr={Corr} -> FAILED in {Ms}ms: {Error}",
                command.Type, command.DeviceId, command.CorrelationId, elapsedMs, CommandDispatcher.Text(result, "error"));
            return;
        }

        _log.LogInformation("Command {Type} device={DeviceId} corr={Corr} -> ok in {Ms}ms",
            command.Type, command.DeviceId, command.CorrelationId, elapsedMs);
    }

    private async Task PublishOutcome(BackendLink link, GatewayEnvelope command, DispatchOutcome outcome)
    {
        await link.SendAsync(
            GatewayEnvelope.Create(_options.Id, outcome.ResultType, outcome.Payload, command.DeviceId, command.CorrelationId),
            CancellationToken.None).ConfigureAwait(false);
    }

    private async Task SendLoopAsync(BackendLink link, CancellationToken stoppingToken)
    {
        await foreach (var message in _outbound.Reader.ReadAllAsync(stoppingToken).ConfigureAwait(false))
        {
            try
            {
                await link.SendAsync(
                    GatewayEnvelope.Create(_options.Id, message.Type, message.Payload, message.DeviceId, message.CorrelationId),
                    stoppingToken).ConfigureAwait(false);
            }
            catch (Exception ex)
            {
                _log.LogWarning(ex, "Failed to send {Type}", message.Type);
            }
        }
    }

    private async Task HeartbeatLoopAsync(BackendLink link, CancellationToken stoppingToken)
    {
        var interval = TimeSpan.FromSeconds(Math.Max(5, _options.HeartbeatSeconds));
        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                await Task.Delay(interval, stoppingToken).ConfigureAwait(false);
                await link.SendAsync(
                    GatewayEnvelope.Create(_options.Id, ProtocolTypes.Heartbeat, new { }),
                    stoppingToken).ConfigureAwait(false);

                foreach (var (deviceId, adapter) in _adapters)
                {
                    var health = adapter.GetHealth();
                    await link.SendAsync(
                        GatewayEnvelope.Create(
                            _options.Id,
                            ProtocolTypes.DeviceStatus,
                            new
                            {
                                connectionState = ServerState(health.ConnectionState),
                                lastSeen = health.LastSeenUtc?.UtcDateTime.ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'")
                            },
                            deviceId),
                        stoppingToken).ConfigureAwait(false);
                }
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                break;
            }
            catch (Exception ex)
            {
                _log.LogWarning("Heartbeat failed: {Detail}", ExceptionBrief.Of(ex));
                // Send can fail while ReceiveAsync is still blocked on a half-closed socket; abort
                // so RunWebSocketAsync exits and reconnects instead of logging this every interval.
                if (link.WebSocketLive)
                {
                    link.RequestReconnect();
                }
            }
        }
    }

    /// <summary>
    /// Queues a command and returns immediately. Each reader has its own worker, so the two doors
    /// run at the same time and a slow SDK call does not stop the socket.
    /// </summary>
    private Task AcceptCommand(GatewayEnvelope command)
    {
        _log.LogInformation("Server command received: {Type} device={DeviceId} corr={Corr}",
            command.Type, command.DeviceId, command.CorrelationId);
        var key = string.IsNullOrWhiteSpace(command.DeviceId) ? "" : command.DeviceId;
        var channel = _inbox.GetOrAdd(key, deviceId =>
        {
            var created = Channel.CreateUnbounded<GatewayEnvelope>(new UnboundedChannelOptions
            {
                SingleReader = true,
                SingleWriter = false
            });
            _ = DrainAsync(created.Reader);
            return created;
        });
        if (!channel.Writer.TryWrite(command))
        {
            _log.LogWarning("Dropped command {Type} for {DeviceId}", command.Type, command.DeviceId);
        }
        else
        {
            // SingleReader channels do not implement Count; reading it throws NotSupportedException
            // and that exception was closing the WebSocket.
            _log.LogDebug("Queued {Type} device={DeviceId} corr={Corr}",
                command.Type, command.DeviceId, command.CorrelationId);
        }

        return Task.CompletedTask;
    }

    private async Task DrainAsync(ChannelReader<GatewayEnvelope> reader)
    {
        await foreach (var command in reader.ReadAllAsync(_stop).ConfigureAwait(false))
        {
            var dispatcher = _dispatcher;
            if (dispatcher == null)
            {
                _log.LogWarning("Command {Type} device={DeviceId} corr={Corr} dropped: it arrived before the gateway finished starting. Use Send again in the app to resend it.",
                    command.Type, command.DeviceId, command.CorrelationId);
                continue;
            }

            try
            {
                await HandleCommandAsync(_link, dispatcher, command).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (_stop.IsCancellationRequested)
            {
                break;
            }
            catch (Exception ex)
            {
                _log.LogError(ex, "Command {Type} failed for {DeviceId}", command.Type, command.DeviceId);
            }
        }
    }

    private async Task PollLoopAsync(BackendLink link, CancellationToken stoppingToken)
    {
        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                await Task.Delay(TimeSpan.FromSeconds(2), stoppingToken).ConfigureAwait(false);
                if (link.WebSocketLive)
                {
                    continue;
                }

                foreach (var command in await link.PollCommandsAsync(stoppingToken).ConfigureAwait(false))
                {
                    await AcceptCommand(command).ConfigureAwait(false);
                }
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                break;
            }
            catch (Exception ex)
            {
                _log.LogWarning("REST poll failed: {Detail}", ExceptionBrief.Of(ex));
            }
        }
    }

    private ValueTask EnqueueStatus(string deviceId, DeviceConnectionStatus status) =>
        _outbound.Writer.WriteAsync(new OutboundMessage(
            ProtocolTypes.DeviceStatus,
            deviceId,
            new { connectionState = status.ConnectionState, error = status.Error },
            null), _stop);

    private sealed class ForwardingListener : IDeviceEventListener
    {
        private readonly string _deviceId;
        private readonly ChannelWriter<OutboundMessage> _writer;

        public ForwardingListener(string deviceId, ChannelWriter<OutboundMessage> writer)
        {
            _deviceId = deviceId;
            _writer = writer;
        }

        /// <summary>Alarms and connection changes are forwarded; attendance comes from the attendance poll.</summary>
        public void OnNormalizedEvent(NormalizedDeviceEvent evt)
        {
            if (evt.Kind == "ALARM")
            {
                _writer.TryWrite(new OutboundMessage(
                    ProtocolTypes.DeviceAlarm,
                    _deviceId,
                    new { type = evt.AlarmType, occurredAt = Format(evt.OccurredAt), details = evt.Details },
                    null));
                return;
            }

            if (evt.Kind == "STATUS")
            {
                _writer.TryWrite(new OutboundMessage(
                    ProtocolTypes.DeviceStatus,
                    _deviceId,
                    new
                    {
                        connectionState = evt.Granted ? "ONLINE" : "OFFLINE",
                        details = evt.Details
                    },
                    null));
            }
        }

        private static string Format(DateTimeOffset value) =>
            value.UtcDateTime.ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'");
    }

    private sealed record OutboundMessage(string Type, string? DeviceId, object Payload, string? CorrelationId);
}
