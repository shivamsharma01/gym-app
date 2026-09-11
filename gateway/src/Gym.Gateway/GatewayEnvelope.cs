using System.Text.Json;
using System.Text.Json.Serialization;

namespace Gym.Gateway;

public sealed class GatewayEnvelope
{
    [JsonPropertyName("messageId")]
    public string MessageId { get; set; } = Guid.NewGuid().ToString();

    [JsonPropertyName("timestamp")]
    public string Timestamp { get; set; } = DateTimeOffset.UtcNow.ToString("o");

    [JsonPropertyName("gatewayId")]
    public string? GatewayId { get; set; }

    [JsonPropertyName("deviceId")]
    public string? DeviceId { get; set; }

    [JsonPropertyName("type")]
    public string Type { get; set; } = "";

    [JsonPropertyName("correlationId")]
    public string? CorrelationId { get; set; }

    [JsonPropertyName("payload")]
    public JsonElement Payload { get; set; }

    public static GatewayEnvelope Create(
        string gatewayId,
        string type,
        object? payload,
        string? deviceId = null,
        string? correlationId = null)
    {
        return new GatewayEnvelope
        {
            MessageId = Guid.NewGuid().ToString(),
            Timestamp = DateTimeOffset.UtcNow.UtcDateTime.ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'"),
            GatewayId = gatewayId,
            DeviceId = deviceId,
            Type = type,
            CorrelationId = correlationId ?? Guid.NewGuid().ToString(),
            Payload = payload == null
                ? default
                : JsonSerializer.SerializeToElement(payload, JsonOptions.Outbound)
        };
    }
}

public static class JsonOptions
{
    public static readonly JsonSerializerOptions Outbound = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull
    };

    public static readonly JsonSerializerOptions Inbound = new()
    {
        PropertyNameCaseInsensitive = true
    };
}

public static class ProtocolTypes
{
    public const string RegisterGateway = "REGISTER_GATEWAY";
    public const string Heartbeat = "HEARTBEAT";
    public const string DeviceStatus = "DEVICE_STATUS";
    public const string DeviceMetadata = "DEVICE_METADATA";
    public const string DeviceEvent = "DEVICE_EVENT";
    public const string DeviceAlarm = "DEVICE_ALARM";
    public const string SyncResult = "SYNC_RESULT";
    public const string ReconciliationResult = "RECONCILIATION_RESULT";
    public const string EnrollmentResult = "ENROLLMENT_RESULT";
    public const string Registered = "REGISTERED";
    public const string Ack = "ACK";
    public const string Error = "ERROR";

    public static bool IsBackendReply(string type) =>
        type is Registered or Ack or Error;
}
