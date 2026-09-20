package com.example.gym.device.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body for exchanging a one-time enrollment token for an operational credential. */
public record GatewayEnrollRequest(
        @NotBlank @Size(max = 36) String gatewayId,
        @NotBlank @Size(min = 32, max = 128) String enrollmentToken) {
}
