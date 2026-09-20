using System.Net.Http.Headers;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using Microsoft.Extensions.Logging;

namespace Gym.Gateway;

/// <summary>
/// Outbound backend link: WSS primary, REST poll fallback, reconnect with exponential backoff.
/// </summary>
public sealed class BackendLink : IAsyncDisposable
{
    private readonly GatewayOptions _options;
    private readonly ILogger<BackendLink> _log;
    private readonly HttpClient _http;
    private ClientWebSocket? _socket;
    private int _reconnectAttempt;
    private bool _websocketLive;

    public BackendLink(GatewayOptions options, ILogger<BackendLink> log, HttpClient? http = null)
    {
        _options = options;
        _log = log;
        _http = http ?? new HttpClient { Timeout = TimeSpan.FromSeconds(20) };
        _http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", options.Token);
        _http.DefaultRequestHeaders.Accept.ParseAdd("application/json");
    }

    public bool WebSocketLive => _websocketLive;

    /// <summary>
    /// Swap the operational credential (after rotate). Updates Bearer headers and forces a WS reconnect
    /// so the next handshake uses the new token (and promotes pending on the backend).
    /// </summary>
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

    /// <summary>Close the current WebSocket so <see cref="RunWebSocketAsync"/> reconnects with the current token.</summary>
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
            // ignore — receive loop will tear down
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
                Query = "token=" + Uri.EscapeDataString(_options.Token)
            };
            return builder.Uri;
        }
    }

    public async Task SendAsync(GatewayEnvelope envelope, CancellationToken cancellationToken)
    {
        var json = JsonSerializer.Serialize(envelope, JsonOptions.Outbound);
        if (_websocketLive && _socket is { State: WebSocketState.Open })
        {
            var bytes = Encoding.UTF8.GetBytes(json);
            await _socket.SendAsync(bytes, WebSocketMessageType.Text, true, cancellationToken).ConfigureAwait(false);
            return;
        }

        using var content = new StringContent(json, Encoding.UTF8, "application/json");
        var response = await _http.PostAsync(new Uri(HttpBase, "internal/gateway/messages"), content, cancellationToken)
            .ConfigureAwait(false);
        if (!response.IsSuccessStatusCode)
        {
            var body = await response.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
            _log.LogWarning("REST ingest {Type} failed HTTP {Status}: {Body}", envelope.Type, (int)response.StatusCode, body);
        }
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

    public async Task RunWebSocketAsync(Func<GatewayEnvelope, Task> onMessage, CancellationToken cancellationToken)
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
                await ReceiveLoopAsync(socket, onMessage, cancellationToken).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                break;
            }
            catch (Exception ex)
            {
                _log.LogWarning(ex, "WebSocket session ended");
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

    private async Task ReceiveLoopAsync(ClientWebSocket socket, Func<GatewayEnvelope, Task> onMessage, CancellationToken cancellationToken)
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
                continue;
            }

            await onMessage(envelope).ConfigureAwait(false);
        }
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
        var builder = new UriBuilder(uri) { Query = "token=***" };
        return builder.Uri.ToString();
    }
}
