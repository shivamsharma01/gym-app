using System.Text;
using System.Text.Json;
using Gym.Gateway.Adapters;

namespace Gym.Gateway.Execution;

/// <summary>Where a reader's next attendance window starts, and storing the punches of one window.</summary>
public interface IAttendanceClient
{
    /// <summary>The reader's last stored punch time, or null before its first punch.</summary>
    Task<DateTimeOffset?> LastStoredAsync(string deviceId, CancellationToken cancellationToken);

    Task<AttendanceStored> StoreAsync(string deviceId, IReadOnlyList<ReaderPunch> punches, CancellationToken cancellationToken);
}

/// <param name="Stored">Punches the server had not stored before.</param>
/// <param name="LastEventAt">The reader's last stored punch time after this window.</param>
public sealed record AttendanceStored(int Stored, DateTimeOffset? LastEventAt);

public sealed class AttendanceClient : IAttendanceClient
{
    private readonly HttpClient _http;
    private readonly string _base;

    public AttendanceClient(HttpClient http, string baseUrl)
    {
        _http = http ?? throw new ArgumentNullException(nameof(http));
        _base = (baseUrl ?? throw new ArgumentNullException(nameof(baseUrl))).TrimEnd('/');
    }

    public static AttendanceClient Create(string baseUrl, string token) =>
        new(DesiredStateClient.CreateHttp(token), baseUrl);

    public async Task<DateTimeOffset?> LastStoredAsync(string deviceId, CancellationToken cancellationToken)
    {
        var url = $"{_base}/internal/gateway/attendance/cursor?deviceId={Uri.EscapeDataString(deviceId)}";
        using var response = await _http.GetAsync(url, cancellationToken).ConfigureAwait(false);
        var body = await response.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
        if (!response.IsSuccessStatusCode)
        {
            throw new InvalidOperationException($"Attendance cursor failed HTTP {(int)response.StatusCode}: {body}");
        }

        return Parse(body).LastEventAt;
    }

    public async Task<AttendanceStored> StoreAsync(
        string deviceId, IReadOnlyList<ReaderPunch> punches, CancellationToken cancellationToken)
    {
        var json = JsonSerializer.Serialize(new
        {
            deviceId,
            punches = punches.Select(p => new
            {
                deviceUserId = p.DeviceUserId,
                occurredAt = Format(p.OccurredAtUtc),
                method = p.Method,
                granted = p.Granted,
                recNo = p.RecordNumber,
                errorCode = p.ErrorCode
            })
        });
        using var content = new StringContent(json, Encoding.UTF8, "application/json");
        using var response = await _http.PostAsync(_base + "/internal/gateway/attendance", content, cancellationToken)
            .ConfigureAwait(false);
        var body = await response.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
        if (!response.IsSuccessStatusCode)
        {
            throw new InvalidOperationException($"Attendance window failed HTTP {(int)response.StatusCode}: {body}");
        }

        return Parse(body);
    }

    internal static string Format(DateTimeOffset value) =>
        value.UtcDateTime.ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'");

    private static AttendanceStored Parse(string body)
    {
        using var document = JsonDocument.Parse(body);
        var root = document.RootElement;
        var stored = root.TryGetProperty("stored", out var count) && count.ValueKind == JsonValueKind.Number
            ? count.GetInt32()
            : 0;
        DateTimeOffset? last = root.TryGetProperty("lastEventAt", out var at) && at.ValueKind == JsonValueKind.String
            ? DateTimeOffset.Parse(at.GetString()!, System.Globalization.CultureInfo.InvariantCulture)
            : null;
        return new AttendanceStored(stored, last);
    }
}
