using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;

namespace Gym.Gateway.Execution;

public sealed record DesiredPull(long DesiredRevision, long AppliedRevision, IReadOnlyList<DesiredPullItem> Items);

public sealed record DesiredPullItem(
    long Revision,
    string DeviceUserId,
    string Name,
    string? NameEx,
    int UserStatus,
    string ValidFrom,
    string ValidTo,
    string Authority,
    int DoorNum,
    int TimeSectionNum,
    byte[] Face,
    bool Present,
    bool KeepDeviceUserId = false,
    bool FacePresent = true)
{
    public DesiredMember ToMember() => new(
        Revision,
        DeviceUserId,
        Name,
        NameEx,
        UserStatus,
        ReaderLocalTime.Parse(ValidFrom),
        ReaderLocalTime.Parse(ValidTo),
        Authority,
        DoorNum,
        TimeSectionNum,
        Face,
        KeepDeviceUserId,
        FacePresent);
}

public interface IDesiredStateClient
{
    Task<DesiredPull> PullAsync(string deviceId, long after, CancellationToken cancellationToken);

    Task AcknowledgeAsync(DesiredAcknowledgement ack, CancellationToken cancellationToken);

    Task ReportOccupiedAsync(string deviceId, long revision, string deviceUserId, CancellationToken cancellationToken);
}

public sealed record DesiredAcknowledgement(
    string DeviceId,
    long Revision,
    string DeviceUserId,
    string Name,
    string? NameEx,
    int UserStatus,
    string ValidFrom,
    string ValidTo,
    string FaceSha256,
    bool Present = true,
    string? FailCode = null);

/// <summary>
/// Pulls a desired member and posts the read-back acknowledgement or an occupied id.
/// The next device user id is never chosen here.
/// </summary>
public sealed class DesiredStateClient : IDesiredStateClient
{
    private static readonly JsonSerializerOptions Json = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase
    };

    private readonly HttpClient _http;
    private readonly string _base;

    public DesiredStateClient(HttpClient http, string baseUrl)
    {
        _http = http ?? throw new ArgumentNullException(nameof(http));
        _base = (baseUrl ?? throw new ArgumentNullException(nameof(baseUrl))).TrimEnd('/');
    }

    public int AcknowledgementPosts { get; private set; }

    public IReadOnlyList<string> OccupiedIds => _occupiedIds;

    public string LastPullBody { get; private set; } = "";

    public string PullTranscript { get; private set; } = "";

    private readonly List<string> _occupiedIds = [];

    public static DesiredStateClient Create(string baseUrl, string token) =>
        new(CreateHttp(token), baseUrl);

    public static HttpClient CreateHttp(string token)
    {
        var http = new HttpClient { Timeout = TimeSpan.FromSeconds(20) };
        http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", token);
        http.DefaultRequestHeaders.Accept.ParseAdd("application/json");
        return http;
    }

    public async Task<DesiredPull> PullAsync(string deviceId, long after, CancellationToken cancellationToken)
    {
        var url = $"{_base}/internal/gateway/desired?deviceId={Uri.EscapeDataString(deviceId)}&after={after}&limit=20";
        using var response = await _http.GetAsync(url, cancellationToken).ConfigureAwait(false);
        var body = await response.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
        LastPullBody = body;
        PullTranscript += body;
        if (!response.IsSuccessStatusCode)
        {
            throw new InvalidOperationException($"Desired pull failed HTTP {(int)response.StatusCode}: {body}");
        }

        return ParsePull(body);
    }

    public async Task AcknowledgeAsync(DesiredAcknowledgement ack, CancellationToken cancellationToken)
    {
        var json = JsonSerializer.Serialize(new
        {
            deviceId = ack.DeviceId,
            revision = ack.Revision,
            deviceUserId = ack.DeviceUserId,
            name = ack.Name,
            nameEx = ack.NameEx,
            userStatus = ack.UserStatus,
            validFrom = ack.ValidFrom,
            validTo = ack.ValidTo,
            faceSha256 = ack.FaceSha256,
            present = ack.Present,
            failCode = ack.FailCode
        }, Json);
        using var response = await PostAsync("/internal/gateway/desired/ack", json, cancellationToken).ConfigureAwait(false);
        var body = await response.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
        if (!response.IsSuccessStatusCode)
        {
            throw new InvalidOperationException($"Desired ack failed HTTP {(int)response.StatusCode}: {body}");
        }

        AcknowledgementPosts++;
    }

    public async Task ReportOccupiedAsync(string deviceId, long revision, string deviceUserId, CancellationToken cancellationToken)
    {
        var json = JsonSerializer.Serialize(new
        {
            deviceId,
            revision,
            deviceUserId
        }, Json);
        using var response = await PostAsync("/internal/gateway/desired/occupied", json, cancellationToken).ConfigureAwait(false);
        var body = await response.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
        if (!response.IsSuccessStatusCode)
        {
            throw new InvalidOperationException($"Occupied report failed HTTP {(int)response.StatusCode}: {body}");
        }

        _occupiedIds.Add(deviceUserId);
    }

    private async Task<HttpResponseMessage> PostAsync(string path, string json, CancellationToken cancellationToken)
    {
        using var content = new StringContent(json, Encoding.UTF8, "application/json");
        return await _http.PostAsync(_base + path, content, cancellationToken).ConfigureAwait(false);
    }

    private static DesiredPull ParsePull(string json)
    {
        using var document = JsonDocument.Parse(json);
        var root = document.RootElement;
        var items = new List<DesiredPullItem>();
        if (root.TryGetProperty("items", out var array) && array.ValueKind == JsonValueKind.Array)
        {
            foreach (var item in array.EnumerateArray())
            {
                items.Add(ParseItem(item));
            }
        }

        return new DesiredPull(
            Long(root, "desiredRevision"),
            Long(root, "appliedRevision"),
            items);
    }

    private static DesiredPullItem ParseItem(JsonElement item)
    {
        var present = !item.TryGetProperty("present", out var presentValue)
            || presentValue.ValueKind != JsonValueKind.False;
        var facePresent = !item.TryGetProperty("facePresent", out var facePresentValue)
            || facePresentValue.ValueKind != JsonValueKind.False;
        var faceText = Optional(item, "faceBase64");
        var face = string.IsNullOrEmpty(faceText) ? [] : Convert.FromBase64String(faceText);
        if (present && facePresent && face.Length == 0)
        {
            throw new InvalidOperationException("Desired member has no face");
        }

        return new DesiredPullItem(
            Long(item, "revision"),
            Required(item, "deviceUserId"),
            Required(item, "name"),
            Optional(item, "nameEx"),
            item.GetProperty("userStatus").GetInt32(),
            Required(item, "validFrom"),
            Required(item, "validTo"),
            Required(item, "authority"),
            item.GetProperty("doorNum").GetInt32(),
            item.GetProperty("timeSectionNum").GetInt32(),
            face,
            present,
            item.TryGetProperty("keepDeviceUserId", out var keep) && keep.ValueKind == JsonValueKind.True,
            facePresent);
    }

    private static string Required(JsonElement item, string name)
    {
        var value = Optional(item, name);
        if (string.IsNullOrWhiteSpace(value))
        {
            throw new InvalidOperationException("Desired member is missing " + name);
        }

        return value;
    }

    private static string? Optional(JsonElement item, string name)
    {
        if (!item.TryGetProperty(name, out var value) || value.ValueKind is JsonValueKind.Null or JsonValueKind.Undefined)
        {
            return null;
        }

        return value.GetString();
    }

    private static long Long(JsonElement item, string name) => item.GetProperty(name).GetInt64();
}
