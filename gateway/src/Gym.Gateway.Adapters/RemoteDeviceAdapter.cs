using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Gym.Gateway.Adapters;

/// <summary>
/// Simulator-only adapter (Adapter = "Remote"): talks to a virtual device over the HTTP protocol of
/// the removed Python device simulator. It replaces
/// <see cref="TrueFaceDeviceAdapter"/> behind the same <see cref="IDeviceAdapter"/> contract, so the
/// rest of the gateway (local state, change detection, outbox) is exercised as in production; the
/// NetSDK mapping and its callbacks are not.
/// <para>
/// Connection handling mirrors what the gateway sees from the SDK: a device that stops answering
/// (transport error, timeout or HTTP 503) is reported OFFLINE with a STATUS event, commands fail fast
/// while it is offline, and a background loop reconnects and reports it back ONLINE. Live events
/// arrive through long polling.
/// </para>
/// </summary>
public sealed class RemoteDeviceAdapter : IDeviceAdapter
{
    private static readonly JsonSerializerOptions JsonOpts = new()
    {
        PropertyNameCaseInsensitive = true,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull
    };

    private readonly HttpClient _http;
    private readonly HttpClient _pollHttp;
    private readonly TimeSpan _minRetryDelay;
    private readonly TimeSpan _maxRetryDelay;
    private readonly CancellationTokenSource _cts = new();
    private DeviceConnectionConfig? _config;
    private IDeviceEventListener? _listener;
    private Task? _loop;
    private int _online;
    private DateTimeOffset? _lastSeen;
    private string _baseUrl = "";

    public RemoteDeviceAdapter(HttpClient? http = null, TimeSpan? minRetryDelay = null, TimeSpan? maxRetryDelay = null)
    {
        _http = http ?? new HttpClient { Timeout = TimeSpan.FromSeconds(10) };
        _pollHttp = new HttpClient { Timeout = TimeSpan.FromSeconds(35) };
        // Free ngrok HTTP tunnels answer with an HTML warning page unless this header is set.
        _http.DefaultRequestHeaders.TryAddWithoutValidation("ngrok-skip-browser-warning", "1");
        _pollHttp.DefaultRequestHeaders.TryAddWithoutValidation("ngrok-skip-browser-warning", "1");
        _minRetryDelay = minRetryDelay ?? TimeSpan.FromSeconds(2);
        _maxRetryDelay = maxRetryDelay ?? TimeSpan.FromSeconds(30);
    }

    public string DeviceId => _config?.DeviceId ?? "";

    private bool IsOnline => Volatile.Read(ref _online) == 1;

    public DeviceConnectionStatus Connect(DeviceConnectionConfig config)
    {
        _config = config;
        _baseUrl = DeviceBaseUrl(config.Ip, config.Port);
        var error = ProbeAsync(_cts.Token).GetAwaiter().GetResult();
        if (error == null)
        {
            Touch();
            Volatile.Write(ref _online, 1);
        }

        // Keeps polling events while online and keeps retrying while offline.
        _loop ??= Task.Run(() => RunAsync(_cts.Token));
        return error == null
            ? DeviceConnectionStatus.Online()
            : DeviceConnectionStatus.Failed($"Cannot reach virtual device at {_baseUrl}: {error} (retrying in background)");
    }

    public void Disconnect()
    {
        Volatile.Write(ref _online, 0);
        _cts.Cancel();
    }

    public DeviceInfoSnapshot GetDeviceInfo()
    {
        var info = Read<RemoteDeviceInfoResponse>("/device/info");
        return info == null
            ? new DeviceInfoSnapshot(null, 0, 0, 0, 0, 0)
            : new DeviceInfoSnapshot(info.SerialNumber, info.DeviceType, info.ChannelCount, info.AlarmInCount,
                info.AlarmOutCount, info.DiskCount);
    }

    public DeviceHealth GetHealth() =>
        new(IsOnline ? "ONLINE" : "OFFLINE", _lastSeen, "remote-simulator");

    public DeviceCommandResult CreateUser(DeviceUserMutation mutation) =>
        Command(t => _http.PostAsJsonAsync($"{_baseUrl}/device/users", mutation, JsonOpts, t));

    public DeviceCommandResult UpdateUser(DeviceUserMutation mutation) =>
        Command(t => _http.PutAsJsonAsync(UserUrl(mutation.DeviceUserId), mutation, JsonOpts, t));

    public DeviceCommandResult DisableUser(string deviceUserId) =>
        UpdateUser(new DeviceUserMutation(deviceUserId, Enabled: false));

    public DeviceCommandResult EnableUser(string deviceUserId) =>
        UpdateUser(new DeviceUserMutation(deviceUserId, Enabled: true));

    public DeviceCommandResult DeleteUser(string deviceUserId) =>
        Command(t => _http.DeleteAsync(UserUrl(deviceUserId), t));

    public DeviceCommandResult UpdateValidity(DeviceUserMutation mutation) => UpdateUser(mutation);

    public IReadOnlyList<DeviceUserSnapshot> ListUsers() =>
        Read<List<RemoteUserDto>>("/device/users")?.Select(ToSnapshot).ToArray() ?? [];

    public DeviceUserSnapshot? GetUser(string deviceUserId)
    {
        var user = Read<RemoteUserDto>($"/device/users/{Uri.EscapeDataString(deviceUserId)}");
        return user == null ? null : ToSnapshot(user);
    }

    public DeviceCommandResult UpsertFace(string deviceUserId, byte[] jpegBytes)
    {
        if (jpegBytes == null || jpegBytes.Length == 0)
        {
            return DeviceCommandResult.Fail("No face image");
        }

        return Command(t =>
        {
            var content = new ByteArrayContent(jpegBytes);
            content.Headers.ContentType = new MediaTypeHeaderValue("image/jpeg");
            return _http.PutAsync(UserUrl(deviceUserId) + "/face", content, t);
        });
    }

    public DeviceFaceRead GetFace(string deviceUserId)
    {
        using var resp = Send(t => _http.GetAsync(UserUrl(deviceUserId) + "/face", t), out var error);
        if (resp == null)
        {
            return DeviceFaceRead.Fail(error);
        }

        if (resp.StatusCode == HttpStatusCode.NotFound)
        {
            return DeviceFaceRead.None();
        }

        if (!resp.IsSuccessStatusCode)
        {
            return DeviceFaceRead.Fail($"HTTP {(int)resp.StatusCode}");
        }

        var bytes = resp.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult();
        return DeviceFaceRead.Found(bytes, resp.Content.Headers.LastModified);
    }

    public DeviceCommandResult DeleteFace(string deviceUserId) =>
        Command(t => _http.DeleteAsync(UserUrl(deviceUserId) + "/face", t));

    public FaceProbeResult ProbeRemoteFaceInsert(string deviceUserId, byte[] jpegBytes)
    {
        var result = UpsertFace(deviceUserId, jpegBytes);
        return new FaceProbeResult(result.Ok, result.Ok ? 0 : -1, result.Ok ? "0x00000000" : "0x10030110", null, result.Error ?? "OK");
    }

    public IReadOnlyList<DeviceAttendanceRecord> FetchAttendance(DateTimeOffset? fromUtc, DateTimeOffset? toUtc)
    {
        var query = new List<string>();
        if (fromUtc.HasValue)
        {
            query.Add($"from={Uri.EscapeDataString(fromUtc.Value.ToString("O"))}");
        }

        if (toUtc.HasValue)
        {
            query.Add($"to={Uri.EscapeDataString(toUtc.Value.ToString("O"))}");
        }

        var path = "/device/attendance" + (query.Count > 0 ? "?" + string.Join("&", query) : "");
        return Read<List<RemoteAttendanceDto>>(path)?.Select(r => new DeviceAttendanceRecord(
            r.DeviceUserId, r.OccurredAt, r.Method ?? "FACE", r.Granted, r.RecNo, r.ErrorCode)).ToArray() ?? [];
    }

    public void RegisterEventListener(IDeviceEventListener listener) => _listener = listener;

    public DeviceCommandResult OpenDoor() => Command(t => _http.PostAsync($"{_baseUrl}/device/open-door", null, t));

    public DeviceCommandResult CloseDoor() => Command(t => _http.PostAsync($"{_baseUrl}/device/close-door", null, t));

    public DeviceCommandResult SynchronizeTime(DateTimeOffset utcNow) =>
        Command(t => _http.PostAsJsonAsync($"{_baseUrl}/device/sync-time", new { utcNow }, JsonOpts, t));

    public DeviceReconciliationResult Reconcile(DateTimeOffset? fromUtc = null, DateTimeOffset? toUtc = null)
    {
        if (!IsOnline)
        {
            return new DeviceReconciliationResult(false, "Device is offline", [], []);
        }

        var from = fromUtc ?? DateTimeOffset.UtcNow.AddDays(-1);
        var to = toUtc ?? DateTimeOffset.UtcNow.AddHours(1);
        return new DeviceReconciliationResult(true, null, FetchAttendance(from, to), ListUsers());
    }

    public void Dispose()
    {
        Disconnect();
        try
        {
            _loop?.Wait(TimeSpan.FromSeconds(2));
        }
        catch (AggregateException)
        {
            // loop already stopped
        }

        _cts.Dispose();
        _http.Dispose();
        _pollHttp.Dispose();
    }

    // --- requests --------------------------------------------------------------------------------

    /// <summary>
    /// <paramref name="ip"/> may be a host (<c>10.0.0.4</c>, <c>0.tcp.ngrok.io</c>) or a full
    /// ngrok URL (<c>https://name.ngrok-free.app</c>). A URL is used as-is; a host is joined with the port.
    /// Local devices and the simulator speak cleartext HTTP. Switching the default to HTTPS would
    /// stop them connecting. An https URL passed in is kept.
    /// </summary>
#pragma warning disable S5332 // cleartext HTTP is the device protocol; https URLs are accepted as-is
    internal static string DeviceBaseUrl(string ip, int port) =>
        ip.Contains("://", StringComparison.Ordinal) ? ip.TrimEnd('/') : $"http://{ip}:{port}";
#pragma warning restore S5332

    private string UserUrl(string deviceUserId) => $"{_baseUrl}/device/users/{Uri.EscapeDataString(deviceUserId)}";

    /// <summary>
    /// Sends a request to the device. Returns null (with an error) when the device is offline or
    /// does not answer; any HTTP answer other than 503 means the device is reachable.
    /// </summary>
    private HttpResponseMessage? Send(Func<CancellationToken, Task<HttpResponseMessage>> call, out string error)
    {
        if (!IsOnline)
        {
            error = "Device is offline";
            return null;
        }

        try
        {
            var resp = call(_cts.Token).GetAwaiter().GetResult();
            if (resp.StatusCode == HttpStatusCode.ServiceUnavailable)
            {
                resp.Dispose();
                MarkOffline("HTTP 503");
                error = "Device is offline (HTTP 503)";
                return null;
            }

            Touch();
            error = "";
            return resp;
        }
        catch (OperationCanceledException) when (_cts.IsCancellationRequested)
        {
            error = "Adapter disconnected";
            return null;
        }
        catch (TaskCanceledException)
        {
            MarkOffline("timed out");
            error = "Device did not respond in time";
            return null;
        }
        catch (HttpRequestException ex)
        {
            MarkOffline(ex.Message);
            error = ex.Message;
            return null;
        }
    }

    private DeviceCommandResult Command(Func<CancellationToken, Task<HttpResponseMessage>> call)
    {
        using var resp = Send(call, out var error);
        if (resp == null)
        {
            return DeviceCommandResult.Fail(error);
        }

        if (resp.IsSuccessStatusCode)
        {
            return DeviceCommandResult.Success();
        }

        var body = resp.Content.ReadAsStringAsync().GetAwaiter().GetResult();
        return DeviceCommandResult.Fail($"HTTP {(int)resp.StatusCode}: {body}");
    }

    /// <summary>GET and parse JSON; null when offline, not found, failed or unreadable.</summary>
    private T? Read<T>(string path) where T : class
    {
        using var resp = Send(t => _http.GetAsync(_baseUrl + path, t), out _);
        if (resp is not { IsSuccessStatusCode: true })
        {
            return null;
        }

        try
        {
            return resp.Content.ReadFromJsonAsync<T>(JsonOpts).GetAwaiter().GetResult();
        }
        catch (JsonException)
        {
            return null;
        }
    }

    private static DeviceUserSnapshot ToSnapshot(RemoteUserDto u) =>
        new(u.DeviceUserId, u.Name, Frozen: u.Enabled == false, ValidFrom: u.ValidFrom, ValidTo: u.ValidTo, Authority: u.Authority ?? "USER");

    // --- connection state and events ---------------------------------------------------------------

    private void Touch() => _lastSeen = DateTimeOffset.UtcNow;

    private void MarkOnline()
    {
        Touch();
        if (Interlocked.Exchange(ref _online, 1) == 0)
        {
            Publish(new NormalizedDeviceEvent("STATUS", null, DateTimeOffset.UtcNow, "UNKNOWN", true, null, null, "reconnected"));
        }
    }

    private void MarkOffline(string reason)
    {
        if (Interlocked.Exchange(ref _online, 0) == 1 && !_cts.IsCancellationRequested)
        {
            Publish(new NormalizedDeviceEvent("STATUS", null, DateTimeOffset.UtcNow, "UNKNOWN", false, null, null,
                "disconnected: " + reason));
        }
    }

    private void Publish(NormalizedDeviceEvent evt)
    {
        try
        {
            _listener?.OnNormalizedEvent(evt);
        }
        catch
        {
            // a listener failure must not stop the event loop
        }
    }

    /// <summary>Returns null when the device answers /device/info, otherwise the reason.</summary>
    private async Task<string?> ProbeAsync(CancellationToken token)
    {
        try
        {
            using var resp = await _http.GetAsync($"{_baseUrl}/device/info", token).ConfigureAwait(false);
            return resp.IsSuccessStatusCode ? null : $"HTTP {(int)resp.StatusCode}";
        }
        catch (OperationCanceledException) when (token.IsCancellationRequested)
        {
            return "cancelled";
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException)
        {
            return ex is TaskCanceledException ? "timed out" : ex.Message;
        }
    }

    private async Task RunAsync(CancellationToken token)
    {
        var retryDelay = _minRetryDelay;
        while (!token.IsCancellationRequested)
        {
            if (!IsOnline)
            {
                if (await ProbeAsync(token).ConfigureAwait(false) == null)
                {
                    MarkOnline();
                    retryDelay = _minRetryDelay;
                }
                else
                {
                    await PauseAsync(retryDelay, token).ConfigureAwait(false);
                    retryDelay = TimeSpan.FromTicks(Math.Min(retryDelay.Ticks * 2, _maxRetryDelay.Ticks));
                }

                continue;
            }

            try
            {
                using var resp = await _pollHttp.GetAsync($"{_baseUrl}/device/events/poll", token).ConfigureAwait(false);
                if (resp.StatusCode == HttpStatusCode.ServiceUnavailable)
                {
                    MarkOffline("HTTP 503");
                    continue;
                }

                if (!resp.IsSuccessStatusCode)
                {
                    await PauseAsync(_minRetryDelay, token).ConfigureAwait(false);
                    continue;
                }

                Touch();
                var events = await resp.Content.ReadFromJsonAsync<List<RemoteEventDto>>(JsonOpts, token).ConfigureAwait(false);
                foreach (var ev in events ?? [])
                {
                    Publish(new NormalizedDeviceEvent(
                        ev.Kind ?? "ACCESS", ev.DeviceUserId, ev.OccurredAt, ev.Method ?? "FACE", ev.Granted, ev.RecNo,
                        ev.AlarmType, ev.Details, ev.ErrorCode));
                }
            }
            catch (OperationCanceledException) when (token.IsCancellationRequested)
            {
                break;
            }
            catch (TaskCanceledException)
            {
                MarkOffline("event poll timed out");
            }
            catch (HttpRequestException ex)
            {
                MarkOffline(ex.Message);
            }
            catch (JsonException)
            {
                await PauseAsync(_minRetryDelay, token).ConfigureAwait(false);
            }
        }
    }

    private static async Task PauseAsync(TimeSpan delay, CancellationToken token)
    {
        try
        {
            await Task.Delay(delay, token).ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            // stopping
        }
    }

    private sealed record RemoteDeviceInfoResponse(
        string? SerialNumber,
        int DeviceType,
        int ChannelCount,
        int AlarmInCount,
        int AlarmOutCount,
        int DiskCount);

    private sealed record RemoteUserDto(
        string DeviceUserId,
        string? Name,
        bool? Enabled,
        DateTimeOffset? ValidFrom,
        DateTimeOffset? ValidTo,
        string? Authority);

    private sealed record RemoteAttendanceDto(
        string? DeviceUserId,
        DateTimeOffset OccurredAt,
        string? Method,
        bool Granted,
        long? RecNo,
        int? ErrorCode);

    private sealed record RemoteEventDto(
        string? Kind,
        string? DeviceUserId,
        DateTimeOffset OccurredAt,
        string? Method,
        bool Granted,
        long? RecNo,
        string? AlarmType,
        string? Details,
        int? ErrorCode);
}
