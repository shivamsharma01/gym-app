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
    private readonly RosterStateStore _roster = new(RosterStateStore.DefaultDirectory());
    private readonly ConcurrentDictionary<string, Channel<GatewayEnvelope>> _inbox = new(StringComparer.Ordinal);
    private DeviceChangeWatcher? _watcher;
    private CommandDispatcher? _dispatcher;
    private readonly DesiredRevisionHub _desiredRevisions;
    private readonly IReaderAdapterFactory? _readerFactory;
    private readonly IDesiredStateClient _desired;
    private readonly string _readerJournalDirectory;
    private readonly List<ReaderWorker> _readerWorkers = [];
    private bool _readersAttached;
    private long _commandsReceived;
    private DateTimeOffset _lastCommandAt;
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
        string? journalDirectory)
    {
        _options = options;
        _link = link;
        _log = log;
        _logFactory = logFactory;
        _locks = new DeviceLocks(logFactory.CreateLogger<DeviceLocks>());
        _desiredRevisions = new DesiredRevisionHub(logFactory.CreateLogger<DesiredRevisionHub>());
        _readerFactory = readers;
        _desired = desired ?? DesiredStateClient.Create(options.BackendUrl, options.Token);
        _readerJournalDirectory = string.IsNullOrWhiteSpace(journalDirectory)
            ? Path.Combine(GatewayConfigStore.DefaultConfigDirectory(), "reader-journal")
            : journalDirectory;
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        _stop = stoppingToken;
        foreach (var device in _options.Devices.Where(d => !string.IsNullOrWhiteSpace(d.DeviceId)))
        {
            var adapter = new TimedDeviceAdapter(
                DeviceAdapterFactory.Create(_options.Adapter), _logFactory.CreateLogger<TimedDeviceAdapter>());
            var status = adapter.Connect(new DeviceConnectionConfig(
                device.DeviceId, device.Ip, device.Port, device.Username, device.Password,
                _options.NativeDirectory));
            adapter.RegisterEventListener(new ForwardingListener(
                device.DeviceId, _outbound.Writer, (id, user) => _watcher?.Trigger(id, user)));
            _adapters[device.DeviceId] = adapter;
            _log.LogInformation(
                "Device {DeviceId} adapter={Adapter} state={State} error={Error}",
                device.DeviceId, _options.Adapter, status.ConnectionState, status.Error);
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

        _watcher = new DeviceChangeWatcher(
            _adapters,
            _roster,
            _locks,
            _link,
            PublishDeviceChangeAsync,
            _logFactory.CreateLogger<DeviceChangeWatcher>(),
            TimeSpan.FromMinutes(Math.Max(1, _options.FaceSweepMinutes)),
            store: new LocalMemberStore(LocalMemberStore.DefaultDirectory()));
        _dispatcher = new CommandDispatcher(
            _adapters, _logFactory.CreateLogger<CommandDispatcher>(), _link, _roster, _locks,
            (deviceId, userId) => _watcher!.ReportUserAsync(deviceId, userId, stoppingToken),
            _watcher);
        AttachReaders();

        var sendLoop = SendLoopAsync(_link, stoppingToken);
        var wsLoop = _link.RunWebSocketAsync(AcceptCommand, _desiredRevisions.HandleAsync, stoppingToken);
        var heartbeat = HeartbeatLoopAsync(_link, stoppingToken);
        var poll = PollLoopAsync(_link, stoppingToken);
        var watch = _watcher.RunAsync(TimeSpan.FromSeconds(Math.Max(15, _options.RosterPollSeconds)), stoppingToken);
        var clock = TimeSyncLoopAsync(stoppingToken);
        var statusLog = StatusLoopAsync(stoppingToken);
        var readerHealth = ReaderHealthLoopAsync(stoppingToken);

        await _outbound.Writer.WriteAsync(new OutboundMessage(
            ProtocolTypes.RegisterGateway,
            null,
            new { agentVersion = "gym-gateway-0.5" },
            null), stoppingToken).ConfigureAwait(false);

        await Task.WhenAll(sendLoop, wsLoop, heartbeat, poll, watch, clock, statusLog, readerHealth).ConfigureAwait(false);
    }

    /// <summary>Logs in again to readers whose session broke, each when its pause has passed.</summary>
    private async Task ReaderHealthLoopAsync(CancellationToken stoppingToken)
    {
        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                await Task.Delay(TimeSpan.FromSeconds(5), stoppingToken).ConfigureAwait(false);
                foreach (var (deviceId, adapter) in _adapters)
                {
                    if (adapter is not TimedDeviceAdapter { ReconnectDue: true } reader)
                    {
                        continue;
                    }

                    _log.LogInformation("Reader {DeviceId}: reconnecting ({Detail})", deviceId, reader.HealthDetail);
                    DeviceConnectionStatus status;
                    using (await _locks.AcquireAsync(deviceId, "reconnecting the reader", stoppingToken).ConfigureAwait(false))
                    {
                        status = reader.Reconnect();
                    }

                    await EnqueueStatus(deviceId, status).ConfigureAwait(false);
                }
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
                _watcher?.LogStatus(ServerLinkStatus());
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
    /// Keeps device clocks aligned (on start, then daily) so "latest change wins" compares
    /// device and server timestamps fairly.
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
            var result = adapter.SynchronizeTime(DateTimeOffset.UtcNow);
            if (result.Ok)
            {
                _log.LogInformation("Reader {DeviceId}: clock set to gateway time", deviceId);
                return true;
            }

            _log.LogWarning("Time sync failed for {DeviceId}: {Error}", deviceId, result.Error);
        }
        catch (Exception ex)
        {
            _log.LogWarning(ex, "Time sync failed for {DeviceId}", deviceId);
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
    /// with no adapter is left unwired, and a desired revision for it is not acknowledged.
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

        foreach (var deviceId in _options.Devices.Select(device => device.DeviceId).Where(id => !string.IsNullOrWhiteSpace(id)))
        {
            var reader = _readerFactory.Open(deviceId, OnlineAdapter(deviceId));
            if (reader == null)
            {
                _log.LogInformation(
                    "Reader {DeviceId} has no worker; desired revisions will not be acknowledged",
                    deviceId);
                continue;
            }

            var journal = Path.Combine(_readerJournalDirectory, JournalFile(deviceId));
            var worker = new ReaderWorker(deviceId, reader, journal);
            _readerWorkers.Add(worker);
            var path = new DesiredRevisionPath(deviceId, worker, reader, _desired);
            _desiredRevisions.Attach(deviceId, path.HandleAsync);
        }
    }

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

    private static string JournalFile(string deviceId)
    {
        var invalid = Path.GetInvalidFileNameChars();
        var name = new string(deviceId.Select(ch => invalid.Contains(ch) ? '_' : ch).ToArray());
        return name + ".sqlite";
    }

    private void DisposeReaders()
    {
        foreach (var worker in _readerWorkers)
        {
            worker.Dispose();
        }

        _readerWorkers.Clear();
    }

    /// <summary>
    /// Device changes go straight to the durable outbox (persisted before this returns), so the
    /// watcher can drop its own queued copy; delivery failures are retried by the outbox.
    /// </summary>
    private async Task PublishDeviceChangeAsync(string deviceId, object payload)
    {
        var envelope = GatewayEnvelope.Create(_options.Id, ProtocolTypes.DeviceUserChanged, payload, deviceId);
        try
        {
            await _link.SendAsync(envelope, CancellationToken.None).ConfigureAwait(false);
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException
                                       or System.Net.WebSockets.WebSocketException)
        {
            _log.LogInformation("Device change for {DeviceId} queued; server unreachable ({Message})", deviceId, ex.Message);
        }
    }

    private async Task HandleCommandAsync(BackendLink link, CommandDispatcher dispatcher, GatewayEnvelope command)
    {
        var userId = CommandDispatcher.Text(command.Payload, "deviceUserId");
        _log.LogDebug("Command start {Type} device={DeviceId} user={User} corr={Corr}",
            command.Type, command.DeviceId, userId, command.CorrelationId);
        var timer = Stopwatch.StartNew();
        var outcome = await dispatcher.DispatchAsync(command).ConfigureAwait(false);
        timer.Stop();
        LogOutcome(command, userId, outcome, timer.ElapsedMilliseconds);
        await PublishOutcome(link, command, outcome).ConfigureAwait(false);
    }

    /// <summary>One line per command: what ran, on which reader and user, the result and how long it took.</summary>
    private void LogOutcome(GatewayEnvelope command, string? userId, DispatchOutcome outcome, long elapsedMs)
    {
        if (outcome.ResultType == ProtocolTypes.ReconciliationResult)
        {
            _log.LogInformation("Command {Type} device={DeviceId} corr={Corr} -> reconcile sent ({Events} event(s)) in {Ms}ms",
                command.Type, command.DeviceId, command.CorrelationId, outcome.Events?.Count ?? 0, elapsedMs);
            return;
        }

        var result = JsonSerializer.SerializeToElement(outcome.Payload, JsonOptions.Outbound);
        var error = CommandDispatcher.Text(result, "error");
        var skipped = CommandDispatcher.Bool(result, "skipped") == true;
        if (!outcome.Ok)
        {
            _log.LogWarning("Command {Type} device={DeviceId} user={User} corr={Corr} -> FAILED in {Ms}ms: {Error}",
                command.Type, command.DeviceId, userId, command.CorrelationId, elapsedMs, error);
        }
        else if (skipped)
        {
            _log.LogInformation("Command {Type} device={DeviceId} user={User} corr={Corr} -> skipped in {Ms}ms: {Reason}",
                command.Type, command.DeviceId, userId, command.CorrelationId, elapsedMs,
                CommandDispatcher.Text(result, "reason"));
        }
        else
        {
            _log.LogInformation("Command {Type} device={DeviceId} user={User} corr={Corr} -> ok in {Ms}ms",
                command.Type, command.DeviceId, userId, command.CorrelationId, elapsedMs);
        }
    }

    private async Task PublishOutcome(BackendLink link, GatewayEnvelope command, DispatchOutcome outcome)
    {
        await link.SendAsync(
            GatewayEnvelope.Create(_options.Id, outcome.ResultType, outcome.Payload, command.DeviceId, command.CorrelationId),
            CancellationToken.None).ConfigureAwait(false);

        if (outcome.ResultType == ProtocolTypes.ReconciliationResult)
        {
            // Complete the outbox command — RECONCILIATION_RESULT alone left commands DISPATCHED forever.
            await link.SendAsync(
                GatewayEnvelope.Create(
                    _options.Id,
                    ProtocolTypes.SyncResult,
                    new { ok = true },
                    command.DeviceId,
                    command.CorrelationId),
                CancellationToken.None).ConfigureAwait(false);
        }
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
                _log.LogWarning(ex, "Heartbeat failed");
            }
        }
    }

    /// <summary>
    /// Queues a command and returns immediately. Each reader has its own worker, so the two doors
    /// run at the same time and a slow SDK call does not stop the socket.
    /// </summary>
    private string ServerLinkStatus()
    {
        var link = _link.WebSocketLive ? "connected (live)" : "not on live connection (polling over REST)";
        var received = Interlocked.Read(ref _commandsReceived);
        var last = _lastCommandAt;
        return received == 0
            ? link + ", no commands received from the server since start"
            : $"{link}, {received} command(s) received from the server since start, last {(long)(DateTimeOffset.UtcNow - last).TotalSeconds} s ago";
    }

    private Task AcceptCommand(GatewayEnvelope command)
    {
        Interlocked.Increment(ref _commandsReceived);
        _lastCommandAt = DateTimeOffset.UtcNow;
        _log.LogInformation("Server command received: {Type} device={DeviceId} user={User} corr={Corr}",
            command.Type, command.DeviceId, CommandDispatcher.Text(command.Payload, "deviceUserId"), command.CorrelationId);
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
                _log.LogWarning(ex, "REST poll failed");
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
        private readonly Action<string, string?> _userChanged;

        public ForwardingListener(string deviceId, ChannelWriter<OutboundMessage> writer, Action<string, string?> userChanged)
        {
            _deviceId = deviceId;
            _writer = writer;
            _userChanged = userChanged;
        }

        public void OnNormalizedEvent(NormalizedDeviceEvent evt)
        {
            if (evt.Kind == "USER_CHANGED")
            {
                _userChanged(_deviceId, evt.DeviceUserId);
                return;
            }

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
                return;
            }

            _writer.TryWrite(new OutboundMessage(
                ProtocolTypes.DeviceEvent,
                _deviceId,
                new
                {
                    deviceUserId = evt.DeviceUserId,
                    occurredAt = Format(evt.OccurredAt),
                    method = evt.Method,
                    granted = evt.Granted,
                    recNo = evt.RecNo,
                    errorCode = evt.ErrorCode,
                    denyReason = MapDeny(evt.ErrorCode, evt.Granted)
                },
                null));
        }

        private static string? MapDeny(int? errorCode, bool granted)
        {
            if (granted || errorCode is null or 0)
            {
                return null;
            }

            return errorCode.Value switch
            {
                0x10 => "UNAUTHORIZED",
                0x14 => "VALIDITY_PERIOD",
                0x20 or 0x21 => "PERIOD_ERROR",
                0x23 => "OVERDUE",
                _ => "ERR_0x" + errorCode.Value.ToString("X")
            };
        }

        private static string Format(DateTimeOffset value) =>
            value.UtcDateTime.ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'");
    }

    private sealed record OutboundMessage(string Type, string? DeviceId, object Payload, string? CorrelationId);
}
