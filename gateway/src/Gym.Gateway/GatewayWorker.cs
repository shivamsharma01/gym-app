using System.Collections.Concurrent;
using System.Threading.Channels;
using Gym.Gateway.Adapters;
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
    private readonly DeviceLocks _locks = new();
    private readonly RosterStateStore _roster = new(RosterStateStore.DefaultDirectory());
    private readonly ConcurrentDictionary<string, Channel<GatewayEnvelope>> _inbox = new(StringComparer.Ordinal);
    private DeviceChangeWatcher? _watcher;
    private CommandDispatcher? _dispatcher;
    private CancellationToken _stop;

    public GatewayWorker(
        GatewayOptions options,
        BackendLink link,
        ILogger<GatewayWorker> log,
        ILoggerFactory logFactory)
    {
        _options = options;
        _link = link;
        _log = log;
        _logFactory = logFactory;
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        foreach (var device in _options.Devices.Where(d => !string.IsNullOrWhiteSpace(d.DeviceId)))
        {
            var adapter = DeviceAdapterFactory.Create(_options.Adapter);
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
        _stop = stoppingToken;
        _dispatcher = new CommandDispatcher(
            _adapters, _logFactory.CreateLogger<CommandDispatcher>(), _link, _roster, _locks,
            (deviceId, userId) => _watcher!.ReportUserAsync(deviceId, userId, stoppingToken),
            _watcher);

        var sendLoop = SendLoopAsync(_link, stoppingToken);
        var wsLoop = _link.RunWebSocketAsync(AcceptCommand, stoppingToken);
        var heartbeat = HeartbeatLoopAsync(_link, stoppingToken);
        var poll = PollLoopAsync(_link, stoppingToken);
        var watch = _watcher.RunAsync(TimeSpan.FromSeconds(Math.Max(15, _options.RosterPollSeconds)), stoppingToken);
        var clock = TimeSyncLoopAsync(stoppingToken);

        await _outbound.Writer.WriteAsync(new OutboundMessage(
            ProtocolTypes.RegisterGateway,
            null,
            new { agentVersion = "gym-gateway-0.5" },
            null), stoppingToken).ConfigureAwait(false);

        await Task.WhenAll(sendLoop, wsLoop, heartbeat, poll, watch, clock).ConfigureAwait(false);
    }

    /// <summary>
    /// Keeps device clocks aligned (on start, then daily) so "latest change wins" compares
    /// device and server timestamps fairly.
    /// </summary>
    private async Task TimeSyncLoopAsync(CancellationToken stoppingToken)
    {
        while (!stoppingToken.IsCancellationRequested)
        {
            foreach (var (deviceId, adapter) in _adapters)
            {
                var gate = _locks.For(deviceId);
                await gate.WaitAsync(stoppingToken).ConfigureAwait(false);
                try
                {
                    var result = adapter.SynchronizeTime(DateTimeOffset.UtcNow);
                    if (!result.Ok)
                    {
                        _log.LogWarning("Time sync failed for {DeviceId}: {Error}", deviceId, result.Error);
                    }
                }
                catch (Exception ex)
                {
                    _log.LogWarning(ex, "Time sync failed for {DeviceId}", deviceId);
                }
                finally
                {
                    gate.Release();
                }
            }

            try
            {
                await Task.Delay(TimeSpan.FromHours(24), stoppingToken).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                break;
            }
        }
    }

    public override async Task StopAsync(CancellationToken cancellationToken)
    {
        foreach (var adapter in _adapters.Values)
        {
            adapter.Dispose();
        }

        _outbound.Writer.TryComplete();
        await base.StopAsync(cancellationToken).ConfigureAwait(false);
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
        _log.LogInformation("Command {Type} device={DeviceId} corr={Corr}",
            command.Type, command.DeviceId, command.CorrelationId);
        var outcome = await dispatcher.DispatchAsync(command).ConfigureAwait(false);
        await PublishOutcome(link, command, outcome).ConfigureAwait(false);
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
                                connectionState = health.ConnectionState,
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
    private Task AcceptCommand(GatewayEnvelope command)
    {
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

        return Task.CompletedTask;
    }

    private async Task DrainAsync(ChannelReader<GatewayEnvelope> reader)
    {
        await foreach (var command in reader.ReadAllAsync(_stop).ConfigureAwait(false))
        {
            var dispatcher = _dispatcher;
            if (dispatcher == null)
            {
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
            null));

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
