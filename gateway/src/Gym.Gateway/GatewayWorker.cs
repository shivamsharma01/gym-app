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
            adapter.RegisterEventListener(new ForwardingListener(device.DeviceId, _outbound.Writer));
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

        var dispatcher = new CommandDispatcher(_adapters, _logFactory.CreateLogger<CommandDispatcher>());

        var sendLoop = SendLoopAsync(_link, stoppingToken);
        var wsLoop = _link.RunWebSocketAsync(cmd => HandleCommandAsync(_link, dispatcher, cmd), stoppingToken);
        var heartbeat = HeartbeatLoopAsync(_link, stoppingToken);
        var poll = PollLoopAsync(_link, dispatcher, stoppingToken);

        await _outbound.Writer.WriteAsync(new OutboundMessage(
            ProtocolTypes.RegisterGateway,
            null,
            new { agentVersion = "gym-gateway-0.5" },
            null), stoppingToken).ConfigureAwait(false);

        await Task.WhenAll(sendLoop, wsLoop, heartbeat, poll).ConfigureAwait(false);
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

        if (outcome.ResultType == ProtocolTypes.EnrollmentResult)
        {
            await link.SendAsync(
                GatewayEnvelope.Create(
                    _options.Id,
                    ProtocolTypes.SyncResult,
                    new { ok = false, error = "UNVERIFIED: remote face enrollment is not claimed as success" },
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

    private async Task PollLoopAsync(BackendLink link, CommandDispatcher dispatcher, CancellationToken stoppingToken)
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
                    await HandleCommandAsync(link, dispatcher, command).ConfigureAwait(false);
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

        public ForwardingListener(string deviceId, ChannelWriter<OutboundMessage> writer)
        {
            _deviceId = deviceId;
            _writer = writer;
        }

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
                    recNo = evt.RecNo
                },
                null));
        }

        private static string Format(DateTimeOffset value) =>
            value.UtcDateTime.ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'");
    }

    private sealed record OutboundMessage(string Type, string? DeviceId, object Payload, string? CorrelationId);
}
