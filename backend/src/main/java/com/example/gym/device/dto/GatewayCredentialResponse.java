package com.example.gym.device.dto;

import java.time.Instant;

/** Operational credential returned once after enroll or rotate. Never log the credential. */
public record GatewayCredentialResponse(
        String gatewayId,
        String credential,
        Instant expiresAt) {
}
