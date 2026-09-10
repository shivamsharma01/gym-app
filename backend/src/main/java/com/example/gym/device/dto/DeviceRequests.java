package com.example.gym.device.dto;

import com.example.gym.device.domain.DeviceRole;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request payloads for device/gateway management. */
public final class DeviceRequests {

    private DeviceRequests() {
    }

    public record CreateGateway(@NotBlank @Size(max = 120) String name) {
    }

    public record CreateDevice(
            @NotBlank @Size(max = 120) String name,
            DeviceRole role,
            @Size(max = 120) String host,
            @Min(1) @Max(65535) Integer port,
            @Size(max = 80) String model,
            @Size(max = 80) String serialNumber,
            /** Optional owning gateway (public id). */
            String gatewayId) {
    }

    public record UpdateDevice(
            @NotBlank @Size(max = 120) String name,
            DeviceRole role,
            @Size(max = 120) String host,
            @Min(1) @Max(65535) Integer port,
            @Size(max = 80) String model,
            @Size(max = 80) String serialNumber,
            String gatewayId) {
    }

    public record CreateMapping(
            @NotBlank String memberId,
            @NotBlank @Size(max = 64) String deviceUserId) {
    }

    public record RemoteDoor(
            /** OPEN or CLOSE. */
            @NotBlank String action,
            /** Must be true — high-risk physical operation requires explicit confirmation. */
            boolean confirmed,
            @Size(max = 300) String reason) {
    }
}
