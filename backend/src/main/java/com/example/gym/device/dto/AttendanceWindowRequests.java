package com.example.gym.device.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** Punches the gateway read from one reader for one time window, and that reader's cursor. */
public final class AttendanceWindowRequests {

    private AttendanceWindowRequests() {
    }

    public record AttendanceWindow(
            @NotBlank String deviceId,
            @NotNull @Size(max = 500) List<@Valid @NotNull PunchRecord> punches) {
    }

    /** {@code occurredAt} is the reader's stuTime taken as UTC. */
    public record PunchRecord(
            @Size(max = 32) String deviceUserId,
            @NotNull Instant occurredAt,
            @Size(max = 32) String method,
            boolean granted,
            Long recNo,
            Integer errorCode) {
    }

    /** {@code lastEventAt} is the reader's last stored punch time, or null before its first punch. */
    public record AttendanceCursor(String deviceId, Instant lastEventAt, int stored) {
    }
}
