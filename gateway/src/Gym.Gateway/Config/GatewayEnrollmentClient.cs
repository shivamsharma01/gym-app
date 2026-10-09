using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json.Serialization;

namespace Gym.Gateway.Config;

/// <summary>HTTP client for enrollment and credential rotation (no secrets logged).</summary>
public sealed class GatewayEnrollmentClient
{
    private readonly HttpClient _http;

    public GatewayEnrollmentClient(HttpClient? http = null)
    {
        _http = http ?? new HttpClient { Timeout = TimeSpan.FromSeconds(30) };
    }

    public async Task<CredentialResult> EnrollAsync(
        string backendUrl,
        string gatewayId,
        string enrollmentToken,
        CancellationToken cancellationToken = default)
    {
        var baseUri = new Uri(backendUrl.TrimEnd('/') + "/");
        using var request = new HttpRequestMessage(HttpMethod.Post, new Uri(baseUri, "internal/gateway/enroll"))
        {
            Content = JsonContent.Create(new { gatewayId, enrollmentToken })
        };
        using var response = await _http.SendAsync(request, cancellationToken).ConfigureAwait(false);
        var body = await response.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
        if (!response.IsSuccessStatusCode)
        {
            throw new InvalidOperationException(ExtractError(body, (int)response.StatusCode));
        }

        var parsed = System.Text.Json.JsonSerializer.Deserialize<CredentialDto>(body, JsonOptions)
            ?? throw new InvalidOperationException("Empty enroll response");
        return new CredentialResult(parsed.GatewayId ?? gatewayId, parsed.Credential!, parsed.ExpiresAt);
    }

    public async Task<CredentialResult> RotateAsync(
        string backendUrl,
        string currentCredential,
        CancellationToken cancellationToken = default)
    {
        var baseUri = new Uri(backendUrl.TrimEnd('/') + "/");
        using var request = new HttpRequestMessage(HttpMethod.Post, new Uri(baseUri, "internal/gateway/credentials/rotate"));
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", currentCredential);
        using var response = await _http.SendAsync(request, cancellationToken).ConfigureAwait(false);
        var body = await response.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
        if (!response.IsSuccessStatusCode)
        {
            throw new InvalidOperationException(ExtractError(body, (int)response.StatusCode));
        }

        var parsed = System.Text.Json.JsonSerializer.Deserialize<CredentialDto>(body, JsonOptions)
            ?? throw new InvalidOperationException("Empty rotate response");
        return new CredentialResult(parsed.GatewayId ?? "", parsed.Credential!, parsed.ExpiresAt);
    }

    public async Task<IReadOnlyList<RemoteDeviceDto>> ListDevicesAsync(
        string backendUrl,
        string credential,
        CancellationToken cancellationToken = default)
    {
        var baseUri = new Uri(backendUrl.TrimEnd('/') + "/");
        using var request = new HttpRequestMessage(HttpMethod.Get, new Uri(baseUri, "internal/gateway/devices"));
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", credential);
        using var response = await _http.SendAsync(request, cancellationToken).ConfigureAwait(false);
        var body = await response.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
        if (!response.IsSuccessStatusCode)
        {
            throw new InvalidOperationException(ExtractError(body, (int)response.StatusCode));
        }

        return System.Text.Json.JsonSerializer.Deserialize<List<RemoteDeviceDto>>(body, JsonOptions) ?? [];
    }

    private static string ExtractError(string body, int status)
    {
        try
        {
            using var doc = System.Text.Json.JsonDocument.Parse(body);
            if (doc.RootElement.TryGetProperty("detail", out var detail))
            {
                return detail.GetString() ?? $"HTTP {status}";
            }
        }
        catch
        {
            // fall through
        }

        return string.IsNullOrWhiteSpace(body) ? $"HTTP {status}" : $"HTTP {status}: {body}";
    }

    private static readonly System.Text.Json.JsonSerializerOptions JsonOptions = new()
    {
        PropertyNameCaseInsensitive = true
    };

    private sealed class CredentialDto
    {
        [JsonPropertyName("gatewayId")]
        public string? GatewayId { get; set; }

        [JsonPropertyName("credential")]
        public string? Credential { get; set; }

        [JsonPropertyName("expiresAt")]
        public DateTimeOffset ExpiresAt { get; set; }
    }
}

public sealed record CredentialResult(string GatewayId, string Credential, DateTimeOffset ExpiresAt);

public sealed class RemoteDeviceDto
{
    public string Id { get; set; } = "";

    public string Name { get; set; } = "";

    public string Role { get; set; } = "";

    public string? Host { get; set; }

    public int? Port { get; set; }
}
