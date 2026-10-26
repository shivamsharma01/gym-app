using System.Collections.Concurrent;
using System.Net.Http.Headers;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using Microsoft.Extensions.Logging;

namespace Gym.Gateway;

/// <summary>
/// Outbound backend link: WSS primary, REST poll fallback, reconnect with exponential backoff.
/// Persists envelopes until backend ACK (or REST 2xx with ACK body) so crashes do not drop events.
/// </summary>
public sealed class BackendLink : IAsyncDisposable, IFaceTransfer
{
    private readonly GatewayOptions _options;
    private readonly ILogger<BackendLink> _log;
    private readonly HttpClient _http;
    private readonly DurableOutboundStore _outbox;
    private ClientWebSocket? _socket;
    private int _reconnectAttempt;
    private bool _websocketLive;

    public BackendLink(
        GatewayOptions options,
        ILogger<BackendLink> log,
        DurableOutboundStore outbox,
        HttpClient? http = null)
    {
        _options = options;
        _log = log;
        _outbox = outbox;
        _http = http ?? new HttpClient { Timeout = TimeSpan.FromSeconds(60) };
        _http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", options.Token);
        _http.DefaultRequestHeaders.Accept.ParseAdd("application/json");
    }

    public bool WebSocketLive => _websocketLive;

    /// <summary>See <see cref="DurableOutboundStore.DropObservations"/>.</summary>
    public int DropObservations(string deviceId) => _outbox.DropObservations(deviceId);

    /// <summary>True while this messageId is still queued and not yet acknowledged.</summary>
    public bool IsPending(string messageId) => _outbox.IsPending(messageId);

    /// <summary>Newest complete-list messageId still queued for this reader, if any.</summary>
    public string? PendingListMessageId(string deviceId) => _outbox.TryGetPendingListMessageId(deviceId);

    /// <summary>Marks a queued message acknowledged (e.g. after observed confirms it was saved).</summary>
    public void Acknowledge(string messageId) => _outbox.Acknowledge(messageId);

    public void ApplyCredential(string credential)
    {
        if (string.IsNullOrWhiteSpace(credential))
        {
            throw new ArgumentException("Credential is required", nameof(credential));
        }

        _options.Token = credential;
        _http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", credential);
        RequestReconnect();
    }

    public void RequestReconnect()
    {
        var socket = _socket;
        if (socket == null)
        {
            return;
        }

        try
        {
            if (socket.State == WebSocketState.Open || socket.State == WebSocketState.CloseReceived)
            {
                socket.Abort();
            }
        }
        catch
        {
            // ignore
        }
    }

    public Uri HttpBase => new(_options.BackendUrl.TrimEnd('/') + "/");

    public Uri WebSocketUri
    {
        get
        {
            var http = new Uri(_options.BackendUrl.TrimEnd('/') + "/");
            var builder = new UriBuilder(http)
            {
                Scheme = http.Scheme.Equals("https", StringComparison.OrdinalIgnoreCase) ? "wss" : "ws",
                Path = "/gateway",
                Query = string.Empty
            };
            return builder.Uri;
        }
    }

    private readonly SemaphoreSlim _sendGate = new(1, 1);

    private readonly ConcurrentDictionary<string, Awaited> _awaited = new(StringComparer.Ordinal);

    private sealed record Awaited(string? CorrelationId, TaskCompletionSource<bool> Outcome);

    public async Task SendAsync(GatewayEnvelope envelope, CancellationToken cancellationToken)
    {
        var replaced = _outbox.Persist(envelope);
        if (replaced != null)
        {
            Resolve(replaced, false);
        }

        await DeliverAsync(envelope, cancellationToken).ConfigureAwait(false);
    }

    /// <summary>
    /// Sends like <see cref="SendAsync"/>, then waits for the server's ACK naming this message,
    /// which the server sends only after the message was processed and committed. Writing the frame
    /// is not acceptance. A rejection, a newer list of the same reader replacing this one, a failed
    /// delivery, or no ACK within <paramref name="timeout"/> returns false; the message itself stays
    /// in the outbox under the usual retry rules either way.
    /// </summary>
    public async Task<bool> SendAndAwaitAckAsync(
        GatewayEnvelope envelope, TimeSpan timeout, CancellationToken cancellationToken)
    {
        var awaited = new Awaited(
            envelope.CorrelationId, new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously));
        _awaited[envelope.MessageId] = awaited;
        try
        {
            await SendAsync(envelope, cancellationToken).ConfigureAwait(false);
            var finished = await Task.WhenAny(awaited.Outcome.Task, Task.Delay(timeout, cancellationToken))
                .ConfigureAwait(false);
            cancellationToken.ThrowIfCancellationRequested();
            return finished == awaited.Outcome.Task && awaited.Outcome.Task.Result;
        }
        finally
        {
            _awaited.TryRemove(envelope.MessageId, out _);
        }
    }

    private void Resolve(string messageId, bool accepted)
    {
        if (_awaited.TryGetValue(messageId, out var awaited))
        {
            awaited.Outcome.TrySetResult(accepted);
        }
    }

    private void RejectByCorrelation(string? correlationId)
    {
        if (string.IsNullOrWhiteSpace(correlationId))
        {
            return;
        }

        foreach (var awaited in _awaited.Values.Where(
                     awaited => string.Equals(awaited.CorrelationId, correlationId, StringComparison.Ordinal)))
        {
            awaited.Outcome.TrySetResult(false);
        }
    }

    /// <summary>
    /// Replay any envelopes that survived a crash without an ACK. One acknowledged, or replaced by a
    /// newer list of the same reader, while the replay runs is skipped, so an older list is never
    /// sent after the list that replaced it.
    /// </summary>
    public async Task ReplayPendingAsync(CancellationToken cancellationToken)
    {
        var pending = 0;
        var failed = 0;
        Exception? firstFailure = null;
        string? firstMessageId = null;
        foreach (var envelope in _outbox.LoadPending())
        {
            if (!_outbox.IsPending(envelope.MessageId))
            {
                continue;
            }

            pending++;
            try
            {
                await DeliverAsync(envelope, cancellationToken).ConfigureAwait(false);
            }
            catch (Exception ex)
            {
                failed++;
                firstFailure ??= ex;
                firstMessageId ??= envelope.MessageId;
                // Same broken socket fails every remaining row; stop and reconnect rather than
                // dumping hundreds of identical stack traces.
                if (ex is WebSocketException or IOException or ObjectDisposedException)
                {
                    break;
                }
            }
        }

        if (failed > 0)
        {
            _log.LogWarning(
                "Outbox replay failed for {Failed} of {Pending} pending message(s); first messageId={MessageId}. Remains queued until the link is healthy. {Detail}",
                failed,
                pending,
                firstMessageId,
                ExceptionBrief.Of(firstFailure!));
        }
    }

    private async Task DeliverAsync(GatewayEnvelope envelope, CancellationToken cancellationToken)
    {
        await _sendGate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            await DeliverExclusiveAsync(envelope, cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            _sendGate.Release();
        }
    }

    private async Task DeliverExclusiveAsync(GatewayEnvelope envelope, CancellationToken cancellationToken)
    {
        var json = JsonSerializer.Serialize(envelope, JsonOptions.Outbound);
        if (_websocketLive && _socket is { State: WebSocketState.Open })
        {
            var bytes = Encoding.UTF8.GetBytes(json);
            await _socket.SendAsync(bytes, WebSocketMessageType.Text, true, cancellationToken)
                .ConfigureAwait(false);
            // ACK arrives asynchronously via receive loop
            return;
        }

        using var content = new StringContent(json, Encoding.UTF8, "application/json");
        var response = await _http.PostAsync(new Uri(HttpBase, "internal/gateway/messages"), content, cancellationToken)
            .ConfigureAwait(false);
        var body = await response.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
        if (!response.IsSuccessStatusCode)
        {
            _log.LogWarning("REST ingest {Type} failed HTTP {Status}: {Body}", envelope.Type,
                (int)response.StatusCode, body);
            Resolve(envelope.MessageId, false);
            return;
        }

        // ACK and terminal ERROR clear the outbox. A retryable ERROR leaves it queued. An empty
        // 200 body still means the frame was accepted (tests and rare fallbacks).
        if (!TryAcknowledgeFromReply(body))
        {
            _outbox.Acknowledge(envelope.MessageId);
        }

        Resolve(envelope.MessageId, false);
    }

    public async Task<IReadOnlyList<GatewayEnvelope>> PollCommandsAsync(CancellationToken cancellationToken)
    {
        if (_websocketLive)
        {
            return [];
        }

        var response = await _http.GetAsync(new Uri(HttpBase, "internal/gateway/commands"), cancellationToken)
            .ConfigureAwait(false);
        if (!response.IsSuccessStatusCode)
        {
            var body = await response.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
            _log.LogWarning("REST poll failed HTTP {Status}: {Body}", (int)response.StatusCode, body);
            return [];
        }

        var json = await response.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
        var commands = JsonSerializer.Deserialize<List<GatewayEnvelope>>(json, JsonOptions.Inbound);
        return commands ?? [];
    }

    public async Task<FaceDownload> DownloadFaceAsync(string memberId, int version, CancellationToken cancellationToken)
    {
        try
        {
            using var request = new HttpRequestMessage(
                HttpMethod.Get,
                new Uri(HttpBase, $"internal/gateway/faces/{Uri.EscapeDataString(memberId)}/{version}"));
            request.Headers.Accept.Clear();
            request.Headers.Accept.ParseAdd("image/jpeg");
            using var response = await _http.SendAsync(request, cancellationToken).ConfigureAwait(false);
            if (!response.IsSuccessStatusCode)
            {
                return new FaceDownload(false, null, $"HTTP {(int)response.StatusCode}");
            }

            var bytes = await response.Content.ReadAsByteArrayAsync(cancellationToken).ConfigureAwait(false);
            return new FaceDownload(true, bytes, null);
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException)
        {
            return new FaceDownload(false, null, ex.Message);
        }
    }

    public async Task<IReadOnlyList<string>> WantedFacesAsync(string deviceId, CancellationToken cancellationToken)
    {
        try
        {
            using var response = await _http.GetAsync(
                    new Uri(HttpBase, $"internal/gateway/faces/wanted?deviceId={Uri.EscapeDataString(deviceId)}"),
                    cancellationToken)
                .ConfigureAwait(false);
            if (!response.IsSuccessStatusCode)
            {
                _log.LogWarning(
                    "Reader {DeviceId}: stage=want-photos; server want-list failed HTTP {Status}; next action=retry on next scan",
                    deviceId, (int)response.StatusCode);
                return [];
            }

            var body = await response.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
            return JsonSerializer.Deserialize<List<string>>(body) ?? [];
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or JsonException
                                       && !cancellationToken.IsCancellationRequested)
        {
            _log.LogInformation(ex,
                "Reader {DeviceId}: stage=want-photos; want-list deferred ({Error}); next action=retry on next scan",
                deviceId, ex.GetType().Name);
            return [];
        }
    }

    public async Task<FaceUpload> UploadFaceAsync(byte[] jpegBytes, CancellationToken cancellationToken)
    {
        try
        {
            using var content = new ByteArrayContent(jpegBytes);
            content.Headers.ContentType = new MediaTypeHeaderValue("image/jpeg");
            using var response = await _http.PostAsync(new Uri(HttpBase, "internal/gateway/faces"), content, cancellationToken)
                .ConfigureAwait(false);
            var body = await response.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
            if (!response.IsSuccessStatusCode)
            {
                var status = (int)response.StatusCode;
                // 400/413/415: the image itself is unacceptable; retrying cannot help.
                var rejected = status is 400 or 413 or 415 or 422;
                _log.LogWarning("Face upload failed HTTP {Status}{Kind}: {Body}", status,
                    rejected ? " (rejected, not retried)" : " (will retry)", body);
                return rejected ? new FaceUpload(null, Rejected: true) : FaceUpload.Transient;
            }

            using var doc = JsonDocument.Parse(body);
            return new FaceUpload(doc.RootElement.TryGetProperty("uploadId", out var id) ? id.GetString() : null);
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException && !cancellationToken.IsCancellationRequested)
        {
            _log.LogInformation("Face upload deferred, server unreachable: {Message}", ex.Message);
            return FaceUpload.Transient;
        }
    }

    public Task RunWebSocketAsync(Func<GatewayEnvelope, Task> onMessage, CancellationToken cancellationToken) =>
        RunWebSocketAsync(onMessage, null, null, cancellationToken);

    public async Task RunWebSocketAsync(
        Func<GatewayEnvelope, Task> onMessage,
        Func<DesiredRevisionNotice, CancellationToken, Task>? onDesiredRevision,
        Func<CancellationToken, Task>? onReconnect,
        CancellationToken cancellationToken)
    {
        if (!_options.UseWebSocket)
        {
            _log.LogInformation("WebSocket disabled; using REST poll fallback");
            return;
        }

        while (!cancellationToken.IsCancellationRequested)
        {
            ClientWebSocket? socket = null;
            try
            {
                socket = new ClientWebSocket();
                socket.Options.SetRequestHeader("Authorization", "Bearer " + _options.Token);
                _log.LogInformation("Connecting WebSocket {Uri}", Redact(WebSocketUri));
                await socket.ConnectAsync(WebSocketUri, cancellationToken).ConfigureAwait(false);
                _socket = socket;
                _websocketLive = true;
                _reconnectAttempt = 0;
                _log.LogInformation("WebSocket connected");
                await InvokeReconnectAsync(onReconnect, cancellationToken).ConfigureAwait(false);
                await ReplayPendingAsync(cancellationToken).ConfigureAwait(false);
                await ReceiveLoopAsync(socket, onMessage, onDesiredRevision, cancellationToken).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                break;
            }
            catch (Exception ex)
            {
                _log.LogWarning("WebSocket session ended: {Detail}", ExceptionBrief.Of(ex));
            }
            finally
            {
                _websocketLive = false;
                _socket = null;
                if (socket != null)
                {
                    try
                    {
                        socket.Dispose();
                    }
                    catch
                    {
                        // ignore
                    }
                }
            }

            var delay = NextBackoff();
            _log.LogInformation("Reconnecting WebSocket in {Delay}s", delay.TotalSeconds);
            try
            {
                await Task.Delay(delay, cancellationToken).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                break;
            }
        }
    }

    private async Task InvokeReconnectAsync(
        Func<CancellationToken, Task>? onReconnect, CancellationToken cancellationToken)
    {
        if (onReconnect == null)
        {
            return;
        }

        try
        {
            await onReconnect(cancellationToken).ConfigureAwait(false);
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            _log.LogWarning("Reconnect read-before-write failed: {Detail}", ExceptionBrief.Of(ex));
        }
    }

    public async ValueTask DisposeAsync()
    {
        _websocketLive = false;
        if (_socket != null)
        {
            try
            {
                if (_socket.State == WebSocketState.Open)
                {
                    await _socket.CloseAsync(WebSocketCloseStatus.NormalClosure, "shutdown", CancellationToken.None)
                        .ConfigureAwait(false);
                }
            }
            catch
            {
                // ignore
            }

            _socket.Dispose();
        }

        _http.Dispose();
    }

    private async Task ReceiveLoopAsync(
        ClientWebSocket socket,
        Func<GatewayEnvelope, Task> onMessage,
        Func<DesiredRevisionNotice, CancellationToken, Task>? onDesiredRevision,
        CancellationToken cancellationToken)
    {
        var buffer = new byte[64 * 1024];
        while (socket.State == WebSocketState.Open && !cancellationToken.IsCancellationRequested)
        {
            using var ms = new MemoryStream();
            WebSocketReceiveResult result;
            do
            {
                result = await socket.ReceiveAsync(buffer, cancellationToken).ConfigureAwait(false);
                if (result.MessageType == WebSocketMessageType.Close)
                {
                    return;
                }

                ms.Write(buffer, 0, result.Count);
            } while (!result.EndOfMessage);

            var json = Encoding.UTF8.GetString(ms.ToArray());
            try
            {
                if (await InboundDispatch.RouteAsync(
                        json,
                        _ => Task.CompletedTask,
                        onDesiredRevision ?? ((_, _) => Task.CompletedTask),
                        cancellationToken).ConfigureAwait(false))
                {
                    continue;
                }
            }
            catch (Exception ex) when (ex is not OperationCanceledException)
            {
                _log.LogWarning(ex, "Desired revision failed; the WebSocket stays open");
                continue;
            }

            GatewayEnvelope? envelope;
            try
            {
                envelope = JsonSerializer.Deserialize<GatewayEnvelope>(json, JsonOptions.Inbound);
            }
            catch (JsonException ex)
            {
                _log.LogWarning(ex, "Ignoring malformed backend frame");
                continue;
            }

            if (envelope == null || string.IsNullOrWhiteSpace(envelope.Type))
            {
                continue;
            }

            if (ProtocolTypes.IsBackendReply(envelope.Type))
            {
                if (envelope.Type is ProtocolTypes.Ack or ProtocolTypes.Registered)
                {
                    TryAcknowledgeFromEnvelope(envelope);
                }
                else if (envelope.Type == ProtocolTypes.Error)
                {
                    LogServerError(envelope);
                }

                continue;
            }

            _log.LogDebug("Received {Type} device={DeviceId} corr={Corr} over the WebSocket",
                envelope.Type, envelope.DeviceId, envelope.CorrelationId);
            try
            {
                await onMessage(envelope).ConfigureAwait(false);
            }
            catch (Exception ex) when (ex is not OperationCanceledException)
            {
                _log.LogWarning(ex, "Command {Type} failed inside the gateway; the WebSocket stays open", envelope.Type);
            }
        }
    }

    private void TryAcknowledgeFromEnvelope(GatewayEnvelope envelope)
    {
        try
        {
            if (envelope.Payload.ValueKind == JsonValueKind.Object
                && envelope.Payload.TryGetProperty("messageId", out var mid)
                && mid.ValueKind == JsonValueKind.String)
            {
                var id = mid.GetString();
                if (!string.IsNullOrWhiteSpace(id))
                {
                    Resolve(id, envelope.Type == ProtocolTypes.Ack);
                    _outbox.Acknowledge(id);
                }
            }
        }
        catch (Exception ex)
        {
            _log.LogDebug(ex, "Could not parse ACK payload");
        }
    }

    /// <summary>
    /// The server could not process a message. A terminal rejection names the message id and is
    /// dropped from the outbox; a retryable one stays and is resent on reconnect.
    /// </summary>
    private void LogServerError(GatewayEnvelope envelope)
    {
        var error = "unknown";
        string? messageId = null;
        var retryable = true;
        if (envelope.Payload.ValueKind == JsonValueKind.Object)
        {
            if (envelope.Payload.TryGetProperty("error", out var e))
            {
                error = e.ToString();
            }

            if (envelope.Payload.TryGetProperty("messageId", out var mid) && mid.ValueKind == JsonValueKind.String)
            {
                messageId = mid.GetString();
            }

            if (envelope.Payload.TryGetProperty("retryable", out var flag)
                && (flag.ValueKind == JsonValueKind.False || flag.ValueKind == JsonValueKind.True))
            {
                retryable = flag.GetBoolean();
            }
        }

        if (!retryable && !string.IsNullOrWhiteSpace(messageId))
        {
            _log.LogWarning(
                "Server rejected gateway message {MessageId} corr={Corr} permanently: {Error}",
                messageId, envelope.CorrelationId, error);
            _outbox.Acknowledge(messageId);
            Resolve(messageId, false);
            return;
        }

        _log.LogWarning("Server rejected a gateway message corr={Corr}: {Error}", envelope.CorrelationId, error);
        if (!string.IsNullOrWhiteSpace(messageId))
        {
            Resolve(messageId, false);
        }

        RejectByCorrelation(envelope.CorrelationId);
    }

    /// <returns>True when the body was an ACK or ERROR the gateway acted on.</returns>
    private bool TryAcknowledgeFromReply(string body)
    {
        if (string.IsNullOrWhiteSpace(body) || body == "{}")
        {
            return false;
        }

        try
        {
            var envelope = JsonSerializer.Deserialize<GatewayEnvelope>(body, JsonOptions.Inbound);
            if (envelope != null && envelope.Type == ProtocolTypes.Ack)
            {
                TryAcknowledgeFromEnvelope(envelope);
                return true;
            }

            if (envelope != null && envelope.Type == ProtocolTypes.Error)
            {
                LogServerError(envelope);
                return true;
            }
        }
        catch
        {
            // ignore
        }

        return false;
    }

    private TimeSpan NextBackoff()
    {
        _reconnectAttempt++;
        var exp = Math.Min(
            _options.ReconnectMaxSeconds,
            _options.ReconnectBaseSeconds * (1 << Math.Min(_reconnectAttempt - 1, 8)));
        var jitter = Random.Shared.Next(0, 500);
        return TimeSpan.FromSeconds(exp) + TimeSpan.FromMilliseconds(jitter);
    }

    private static string Redact(Uri uri)
    {
        var builder = new UriBuilder(uri) { Query = string.Empty };
        return builder.Uri.ToString();
    }
}
