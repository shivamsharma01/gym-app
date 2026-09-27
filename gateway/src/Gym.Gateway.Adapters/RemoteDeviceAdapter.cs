using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Gym.Gateway.Adapters;

/// <summary>
/// Device adapter that connects over HTTP/REST to a standalone virtual hardware device server.
/// Exposes the exact same IDeviceAdapter contract to the Gateway so the Gateway can run unmodified,
/// communicating over local network (e.g. 127.0.0.1:9001 and 127.0.0.1:9002) to the simulator.
/// Supports long-polling for real-time device callbacks (alarms, punches, local edits).
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
    private readonly CancellationTokenSource _cts = new();
    private DeviceConnectionConfig? _config;
    private IDeviceEventListener? _listener;
    private Task? _eventLoopTask;
    private bool _connected;
    private DateTimeOffset? _lastSeen;
    private string _baseUrl = "";

    public RemoteDeviceAdapter(HttpClient? http = null)
    {
        _http = http ?? new HttpClient { Timeout = TimeSpan.FromSeconds(10) };
    }

    public string DeviceId => _config?.DeviceId ?? "";

    public DeviceConnectionStatus Connect(DeviceConnectionConfig config)
    {
        _config = config;
        _baseUrl = $"http://{config.Ip}:{config.Port}";

        try
        {
            var pingResp = _http.GetAsync($"{_baseUrl}/device/info").GetAwaiter().GetResult();
            if (!pingResp.IsSuccessStatusCode)
            {
                return DeviceConnectionStatus.Failed($"Virtual device returned HTTP {pingResp.StatusCode}");
            }

            _connected = true;
            _lastSeen = DateTimeOffset.UtcNow;

            // Start long-polling event loop for real-time hardware events
            _eventLoopTask = Task.Run(() => EventPollingLoopAsync(_cts.Token));

            return DeviceConnectionStatus.Online();
        }
        catch (Exception ex)
        {
            return DeviceConnectionStatus.Failed($"Cannot connect to virtual device at {_baseUrl}: {ex.Message}");
        }
    }

    public void Disconnect()
    {
        _connected = false;
        _cts.Cancel();
    }

    public DeviceInfoSnapshot GetDeviceInfo()
    {
        if (!_connected)
        {
            return new DeviceInfoSnapshot(null, 0, 0, 0, 0, 0);
        }

        try
        {
            var resp = _http.GetFromJsonAsync<RemoteDeviceInfoResponse>($"{_baseUrl}/device/info", JsonOpts)
                .GetAwaiter().GetResult();
            return new DeviceInfoSnapshot(
                resp?.SerialNumber ?? "SIM-SERIAL",
                resp?.DeviceType ?? 0,
                resp?.ChannelCount ?? 1,
                resp?.AlarmInCount ?? 0,
                resp?.AlarmOutCount ?? 0,
                resp?.DiskCount ?? 0);
        }
        catch
        {
            return new DeviceInfoSnapshot("SIM-SERIAL", 0, 1, 0, 0, 0);
        }
    }

    public DeviceHealth GetHealth() =>
        new(_connected ? "ONLINE" : "OFFLINE", _lastSeen, "remote-simulator");

    public DeviceCommandResult CreateUser(DeviceUserMutation mutation)
    {
        if (!EnsureConnected(out var err))
        {
            return DeviceCommandResult.Fail(err);
        }

        try
        {
            var resp = _http.PostAsJsonAsync($"{_baseUrl}/device/users", mutation, JsonOpts).GetAwaiter().GetResult();
            Touch();
            if (resp.IsSuccessStatusCode)
            {
                return DeviceCommandResult.Success();
            }

            var body = resp.Content.ReadAsStringAsync().GetAwaiter().GetResult();
            return DeviceCommandResult.Fail($"HTTP {resp.StatusCode}: {body}");
        }
        catch (Exception ex)
        {
            return DeviceCommandResult.Fail(ex.Message);
        }
    }

    public DeviceCommandResult UpdateUser(DeviceUserMutation mutation)
    {
        if (!EnsureConnected(out var err))
        {
            return DeviceCommandResult.Fail(err);
        }

        try
        {
            var resp = _http.PutAsJsonAsync($"{_baseUrl}/device/users/{Uri.EscapeDataString(mutation.DeviceUserId)}", mutation, JsonOpts).GetAwaiter().GetResult();
            Touch();
            if (resp.IsSuccessStatusCode)
            {
                return DeviceCommandResult.Success();
            }

            var body = resp.Content.ReadAsStringAsync().GetAwaiter().GetResult();
            return DeviceCommandResult.Fail($"HTTP {resp.StatusCode}: {body}");
        }
        catch (Exception ex)
        {
            return DeviceCommandResult.Fail(ex.Message);
        }
    }

    public DeviceCommandResult DisableUser(string deviceUserId) =>
        UpdateUser(new DeviceUserMutation(deviceUserId, Enabled: false));

    public DeviceCommandResult EnableUser(string deviceUserId) =>
        UpdateUser(new DeviceUserMutation(deviceUserId, Enabled: true));

    public DeviceCommandResult DeleteUser(string deviceUserId)
    {
        if (!EnsureConnected(out var err))
        {
            return DeviceCommandResult.Fail(err);
        }

        try
        {
            var resp = _http.DeleteAsync($"{_baseUrl}/device/users/{Uri.EscapeDataString(deviceUserId)}").GetAwaiter().GetResult();
            Touch();
            if (resp.IsSuccessStatusCode)
            {
                return DeviceCommandResult.Success();
            }

            var body = resp.Content.ReadAsStringAsync().GetAwaiter().GetResult();
            return DeviceCommandResult.Fail($"HTTP {resp.StatusCode}: {body}");
        }
        catch (Exception ex)
        {
            return DeviceCommandResult.Fail(ex.Message);
        }
    }

    public DeviceCommandResult UpdateValidity(DeviceUserMutation mutation) => UpdateUser(mutation);

    public IReadOnlyList<DeviceUserSnapshot> ListUsers()
    {
        if (!EnsureConnected(out _))
        {
            return [];
        }

        try
        {
            var users = _http.GetFromJsonAsync<List<RemoteUserDto>>($"{_baseUrl}/device/users", JsonOpts).GetAwaiter().GetResult();
            Touch();
            return users?.Select(u => new DeviceUserSnapshot(
                u.DeviceUserId,
                u.Name,
                Frozen: u.Enabled == false,
                ValidFrom: u.ValidFrom,
                ValidTo: u.ValidTo)).ToArray() ?? [];
        }
        catch
        {
            return [];
        }
    }

    public DeviceUserSnapshot? GetUser(string deviceUserId)
    {
        if (!EnsureConnected(out _))
        {
            return null;
        }

        try
        {
            var u = _http.GetFromJsonAsync<RemoteUserDto>($"{_baseUrl}/device/users/{Uri.EscapeDataString(deviceUserId)}", JsonOpts).GetAwaiter().GetResult();
            Touch();
            return u == null ? null : new DeviceUserSnapshot(
                u.DeviceUserId,
                u.Name,
                Frozen: u.Enabled == false,
                ValidFrom: u.ValidFrom,
                ValidTo: u.ValidTo);
        }
        catch
        {
            return null;
        }
    }

    public DeviceCommandResult UpsertFace(string deviceUserId, byte[] jpegBytes)
    {
        if (!EnsureConnected(out var err))
        {
            return DeviceCommandResult.Fail(err);
        }

        if (jpegBytes == null || jpegBytes.Length == 0)
        {
            return DeviceCommandResult.Fail("No face image");
        }

        try
        {
            using var content = new ByteArrayContent(jpegBytes);
            content.Headers.ContentType = new MediaTypeHeaderValue("image/jpeg");
            var resp = _http.PutAsync($"{_baseUrl}/device/users/{Uri.EscapeDataString(deviceUserId)}/face", content).GetAwaiter().GetResult();
            Touch();
            if (resp.IsSuccessStatusCode)
            {
                return DeviceCommandResult.Success();
            }

            var body = resp.Content.ReadAsStringAsync().GetAwaiter().GetResult();
            return DeviceCommandResult.Fail($"HTTP {resp.StatusCode}: {body}");
        }
        catch (Exception ex)
        {
            return DeviceCommandResult.Fail(ex.Message);
        }
    }

    public DeviceFaceRead GetFace(string deviceUserId)
    {
        if (!EnsureConnected(out var err))
        {
            return DeviceFaceRead.Fail(err);
        }

        try
        {
            var resp = _http.GetAsync($"{_baseUrl}/device/users/{Uri.EscapeDataString(deviceUserId)}/face").GetAwaiter().GetResult();
            Touch();
            if (resp.StatusCode == System.Net.HttpStatusCode.NotFound)
            {
                return DeviceFaceRead.None();
            }

            if (!resp.IsSuccessStatusCode)
            {
                return DeviceFaceRead.Fail($"HTTP {resp.StatusCode}");
            }

            var bytes = resp.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult();
            var lastMod = resp.Content.Headers.LastModified ?? DateTimeOffset.UtcNow;
            return DeviceFaceRead.Found(bytes, lastMod);
        }
        catch (Exception ex)
        {
            return DeviceFaceRead.Fail(ex.Message);
        }
    }

    public DeviceCommandResult DeleteFace(string deviceUserId)
    {
        if (!EnsureConnected(out var err))
        {
            return DeviceCommandResult.Fail(err);
        }

        try
        {
            var resp = _http.DeleteAsync($"{_baseUrl}/device/users/{Uri.EscapeDataString(deviceUserId)}/face").GetAwaiter().GetResult();
            Touch();
            if (resp.IsSuccessStatusCode)
            {
                return DeviceCommandResult.Success();
            }

            return DeviceCommandResult.Fail($"HTTP {resp.StatusCode}");
        }
        catch (Exception ex)
        {
            return DeviceCommandResult.Fail(ex.Message);
        }
    }

    public FaceProbeResult ProbeRemoteFaceInsert(string deviceUserId, byte[] jpegBytes)
    {
        var result = UpsertFace(deviceUserId, jpegBytes);
        return new FaceProbeResult(result.Ok, result.Ok ? 0 : -1, result.Ok ? "0x00000000" : "0x10030110", null, result.Error ?? "OK");
    }

    public IReadOnlyList<DeviceAttendanceRecord> FetchAttendance(DateTimeOffset? fromUtc, DateTimeOffset? toUtc)
    {
        if (!EnsureConnected(out _))
        {
            return [];
        }

        try
        {
            var url = $"{_baseUrl}/device/attendance";
            if (fromUtc.HasValue || toUtc.HasValue)
            {
                var query = new List<string>();
                if (fromUtc.HasValue) query.Add($"from={Uri.EscapeDataString(fromUtc.Value.ToString("O"))}");
                if (toUtc.HasValue) query.Add($"to={Uri.EscapeDataString(toUtc.Value.ToString("O"))}");
                url += "?" + string.Join("&", query);
            }

            var records = _http.GetFromJsonAsync<List<RemoteAttendanceDto>>(url, JsonOpts).GetAwaiter().GetResult();
            Touch();
            return records?.Select(r => new DeviceAttendanceRecord(
                r.DeviceUserId,
                r.OccurredAt,
                r.Method ?? "FACE",
                r.Granted,
                r.RecNo,
                r.ErrorCode)).ToArray() ?? [];
        }
        catch
        {
            return [];
        }
    }

    public void RegisterEventListener(IDeviceEventListener listener) => _listener = listener;

    public DeviceCommandResult OpenDoor() => PostSimpleAction("open-door");

    public DeviceCommandResult CloseDoor() => PostSimpleAction("close-door");

    public DeviceCommandResult SynchronizeTime(DateTimeOffset utcNow)
    {
        if (!EnsureConnected(out var err))
        {
            return DeviceCommandResult.Fail(err);
        }

        try
        {
            var resp = _http.PostAsJsonAsync($"{_baseUrl}/device/sync-time", new { utcNow }, JsonOpts).GetAwaiter().GetResult();
            Touch();
            return resp.IsSuccessStatusCode ? DeviceCommandResult.Success() : DeviceCommandResult.Fail($"HTTP {resp.StatusCode}");
        }
        catch (Exception ex)
        {
            return DeviceCommandResult.Fail(ex.Message);
        }
    }

    public DeviceReconciliationResult Reconcile(DateTimeOffset? fromUtc = null, DateTimeOffset? toUtc = null)
    {
        if (!EnsureConnected(out var err))
        {
            return new DeviceReconciliationResult(false, err, [], []);
        }

        return new DeviceReconciliationResult(true, null, FetchAttendance(fromUtc, toUtc), ListUsers());
    }

    public void Dispose()
    {
        Disconnect();
        _cts.Dispose();
        _http.Dispose();
    }

    private DeviceCommandResult PostSimpleAction(string action)
    {
        if (!EnsureConnected(out var err))
        {
            return DeviceCommandResult.Fail(err);
        }

        try
        {
            var resp = _http.PostAsync($"{_baseUrl}/device/{action}", null).GetAwaiter().GetResult();
            Touch();
            return resp.IsSuccessStatusCode ? DeviceCommandResult.Success() : DeviceCommandResult.Fail($"HTTP {resp.StatusCode}");
        }
        catch (Exception ex)
        {
            return DeviceCommandResult.Fail(ex.Message);
        }
    }

    private bool EnsureConnected(out string error)
    {
        if (_connected)
        {
            error = "";
            return true;
        }

        error = "Device is not connected";
        return false;
    }

    private void Touch() => _lastSeen = DateTimeOffset.UtcNow;

    private async Task EventPollingLoopAsync(CancellationToken token)
    {
        using var pollClient = new HttpClient { Timeout = TimeSpan.FromSeconds(35) };
        while (!token.IsCancellationRequested && _connected)
        {
            try
            {
                var resp = await pollClient.GetAsync($"{_baseUrl}/device/events/poll", token).ConfigureAwait(false);
                if (resp.IsSuccessStatusCode)
                {
                    var events = await resp.Content.ReadFromJsonAsync<List<RemoteEventDto>>(JsonOpts, token).ConfigureAwait(false);
                    if (events != null)
                    {
                        foreach (var ev in events)
                        {
                            _listener?.OnNormalizedEvent(new NormalizedDeviceEvent(
                                ev.Kind ?? "ACCESS",
                                ev.DeviceUserId,
                                ev.OccurredAt,
                                ev.Method ?? "FACE",
                                ev.Granted,
                                ev.RecNo,
                                ev.AlarmType,
                                ev.Details,
                                ev.ErrorCode));
                        }
                    }
                }
            }
            catch (OperationCanceledException) when (token.IsCancellationRequested)
            {
                break;
            }
            catch
            {
                // Exponential backoff or brief delay on connection drop
                await Task.Delay(2000, token).ConfigureAwait(false);
            }
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
        DateTimeOffset? ValidTo);

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
