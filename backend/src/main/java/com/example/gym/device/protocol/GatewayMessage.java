package com.example.gym.device.protocol;

import java.time.Instant;
import tools.jackson.databind.JsonNode;

/**
 * Common envelope for every gateway ↔ backend message (§37). Command handling keyed on
 * {@code messageId}/{@code correlationId} is idempotent, so reconnect replays are safe.
 */
public record GatewayMessage(
        String messageId,
        Instant timestamp,
        String gatewayId,
        String deviceId,
        String type,
        String correlationId,
        JsonNode payload) {
}
