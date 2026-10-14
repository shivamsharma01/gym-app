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
    bool FacePresent = true,
    ExpectedOccupant? Expected = null,
    string? Invalid = null)
{
    /// <summary>An item whose revision and id are known but whose record cannot be applied as sent.</summary>
    public static DesiredPullItem Unusable(long revision, string deviceUserId, string reason) =>
        new(revision, deviceUserId, "", null, 0, "", "", "", 0, 0, [], true, true, ExpectedOccupant.Absent, reason);

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
        FacePresent,
        Expected);
}

public interface IDesiredStateClient
{
    Task<DesiredPull> PullAsync(string deviceId, long after, CancellationToken cancellationToken);

    Task AcknowledgeAsync(DesiredAcknowledgement ack, CancellationToken cancellationToken);

    Task ReportOccupiedAsync(string deviceId, long revision, string deviceUserId, CancellationToken cancellationToken);

    /// <summary>The id holds something other than the expected record, or no one; nothing was written.</summary>
    Task ReportOccupantAsync(string deviceId, long revision, string deviceUserId, ReaderOccupant occupant,
        CancellationToken cancellationToken);

    /// <summary>
    /// True only when the server has processed every one of these gateway messages for this reader.
    /// Any failure to answer is not processed.
    /// </summary>
    Task<bool> ObservationProcessedAsync(string deviceId, IReadOnlyList<string> messageIds, CancellationToken cancellationToken);

    /// <summary>A revision exhausted its retries or cannot be applied as sent. Nothing about the reader changes.</summary>
    Task ReportFailedAsync(string deviceId, RetryState attempt, CancellationToken cancellationToken);
}

/// <summary>The server answered 409: the revision was replaced, or it does not accept what was sent.</summary>
public sealed class DesiredRefusedException(string message) : InvalidOperationException(message);

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
        ThrowUnlessSuccess(response, "Desired ack", body);
        AcknowledgementPosts++;
    }

    public async Task<bool> ObservationProcessedAsync(
        string deviceId, IReadOnlyList<string> messageIds, CancellationToken cancellationToken)
    {
        var json = JsonSerializer.Serialize(new { deviceId, messageIds }, Json);
        using var response = await PostAsync("/internal/gateway/desired/observed", json, cancellationToken).ConfigureAwait(false);
        var body = await response.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
        if (!response.IsSuccessStatusCode)
        {
            return false;
        }

        using var document = JsonDocument.Parse(body);
        return document.RootElement.TryGetProperty("processed", out var processed)
            && processed.ValueKind == JsonValueKind.True;
    }

    public async Task ReportFailedAsync(string deviceId, RetryState attempt, CancellationToken cancellationToken)
    {
        var json = JsonSerializer.Serialize(new
        {
            deviceId,
            revision = attempt.Revision,
            deviceUserId = attempt.DeviceUserId,
            error = attempt.LastError.Length > 255 ? attempt.LastError[..255] : attempt.LastError,
            attempts = attempt.AttemptCount,
            permanent = attempt.State == AttemptState.Rejected
        }, Json);
        using var response = await PostAsync("/internal/gateway/desired/failed", json, cancellationToken).ConfigureAwait(false);
        var body = await response.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
        ThrowUnlessSuccess(response, "Failure report", body);
    }

    private static void ThrowUnlessSuccess(HttpResponseMessage response, string what, string body)
    {
        if (response.IsSuccessStatusCode)
        {
            return;
        }

        var message = $"{what} failed HTTP {(int)response.StatusCode}: {body}";
        throw response.StatusCode == System.Net.HttpStatusCode.Conflict
            ? new DesiredRefusedException(message)
            : new InvalidOperationException(message);
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
        ThrowUnlessSuccess(response, "Occupied report", body);
        _occupiedIds.Add(deviceUserId);
    }

    public async Task ReportOccupantAsync(string deviceId, long revision, string deviceUserId, ReaderOccupant occupant,
        CancellationToken cancellationToken)
    {
        var user = occupant.User;
        var json = JsonSerializer.Serialize(new
        {
            deviceId,
            revision,
            deviceUserId,
            present = occupant.Present,
            name = user?.Name,
            nameEx = user?.NameEx,
            userStatus = user?.UserStatus,
            validFrom = user?.ValidFrom is { } from ? ReaderLocalTime.Format(from) : null,
            validTo = user?.ValidTo is { } to ? ReaderLocalTime.Format(to) : null,
            authority = user?.Authority,
            doorNum = user?.DoorNum,
            timeSectionNum = user?.TimeSectionNum,
            faceSha256 = occupant.Present ? occupant.FaceSha256 : null
        }, Json);
        using var response = await PostAsync("/internal/gateway/desired/occupant", json, cancellationToken).ConfigureAwait(false);
        var body = await response.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
        ThrowUnlessSuccess(response, "Occupant report", body);
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

    /// <summary>
    /// An item with a revision and an id is always returned, so one bad record cannot stop the
    /// rest of the page; a record that cannot be applied carries why.
    /// </summary>
    private static DesiredPullItem ParseItem(JsonElement item)
    {
        var revision = Long(item, "revision");
        var deviceUserId = Required(item, "deviceUserId");
        try
        {
            var parsed = ParseRecord(item);
            parsed.ToMember();
            return parsed;
        }
        catch (Exception ex) when (ex is InvalidOperationException or FormatException or KeyNotFoundException
                                       or ArgumentException)
        {
            return DesiredPullItem.Unusable(revision, deviceUserId, "the desired record is malformed: " + ex.Message);
        }
    }

    private static DesiredPullItem ParseRecord(JsonElement item)
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
            facePresent,
            ParseExpected(item));
    }

    /// <summary>A missing precondition is no one: the gateway then writes only an empty id.</summary>
    private static ExpectedOccupant ParseExpected(JsonElement item)
    {
        if (!item.TryGetProperty("expected", out var expected) || expected.ValueKind != JsonValueKind.Object)
        {
            return ExpectedOccupant.Absent;
        }

        if (!expected.TryGetProperty("present", out var present) || present.ValueKind != JsonValueKind.True)
        {
            return ExpectedOccupant.Absent;
        }

        return new ExpectedOccupant(
            true,
            Optional(expected, "name"),
            Optional(expected, "nameEx"),
            expected.TryGetProperty("userStatus", out var status) && status.ValueKind == JsonValueKind.Number
                ? status.GetInt32()
                : null,
            Optional(expected, "validFrom"),
            Optional(expected, "validTo"),
            Optional(expected, "authority"),
            Optional(expected, "faceSha256"));
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
