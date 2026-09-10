package com.example.gym.device.dto;

import com.example.gym.device.domain.AttendanceEvent;
import com.example.gym.device.domain.Device;
import com.example.gym.device.domain.DeviceSyncCommand;
import com.example.gym.device.domain.Gateway;
import com.example.gym.device.domain.MemberDeviceMapping;
import com.example.gym.device.domain.SecurityEvent;
import java.time.Instant;

/** Response projections for the device domain (never expose internal numeric ids). */
public final class DeviceResponses {

    private DeviceResponses() {
    }

    public record GatewayCreated(
            String id,
            String name,
            String status,
            String token,
            Instant createdAt) {

        public static GatewayCreated from(Gateway g, String token) {
            return new GatewayCreated(g.getPublicId(), g.getName(), g.getStatus().name(), token,
                    g.getCreatedAt());
        }
    }

    public record GatewayView(
            String id,
            String name,
            String status,
            Instant lastHeartbeatAt,
            Instant lastRegisteredAt,
            String agentVersion,
            Instant createdAt) {

        public static GatewayView from(Gateway g) {
            return new GatewayView(g.getPublicId(), g.getName(), g.getStatus().name(),
                    g.getLastHeartbeatAt(), g.getLastRegisteredAt(), g.getAgentVersion(), g.getCreatedAt());
        }
    }

    public record DeviceView(
            String id,
            String name,
            String role,
            String host,
            Integer port,
            String model,
            String serialNumber,
            String firmware,
            String connectionState,
            Instant lastSeenAt,
            boolean gatewayAssigned,
            Instant createdAt) {

        public static DeviceView from(Device d) {
            return new DeviceView(d.getPublicId(), d.getName(), d.getRole().name(), d.getHost(),
                    d.getPort(), d.getModel(), d.getSerialNumber(), d.getFirmware(),
                    d.getConnectionState().name(), d.getLastSeenAt(), d.getGatewayId() != null,
                    d.getCreatedAt());
        }
    }

    public record MappingView(
            String id,
            String deviceUserId,
            String enrollmentStatus,
            String syncState,
            Instant enrolledAt,
            Instant createdAt) {

        public static MappingView from(MemberDeviceMapping m) {
            return new MappingView(m.getPublicId(), m.getDeviceUserId(), m.getEnrollmentStatus().name(),
                    m.getSyncState().name(), m.getEnrolledAt(), m.getCreatedAt());
        }
    }

    public record SyncCommandView(
            String id,
            String type,
            String state,
            int attemptCount,
            int maxAttempts,
            Instant nextAttemptAt,
            String lastError,
            String correlationId,
            Instant dispatchedAt,
            Instant acknowledgedAt,
            Instant completedAt,
            Instant createdAt) {

        public static SyncCommandView from(DeviceSyncCommand c) {
            return new SyncCommandView(c.getPublicId(), c.getType().name(), c.getState().name(),
                    c.getAttemptCount(), c.getMaxAttempts(), c.getNextAttemptAt(), c.getLastError(),
                    c.getCorrelationId(), c.getDispatchedAt(), c.getAcknowledgedAt(),
                    c.getCompletedAt(), c.getCreatedAt());
        }
    }

    public record DeviceHealth(
            String id,
            String deviceConnectionState,
            String gatewayStatus,
            boolean gatewaySessionOnline,
            Instant lastSeenAt,
            Instant lastSuccessfulSyncAt,
            long pendingCommandCount,
            Long attendanceLastRecNo,
            Instant attendanceLastEventAt) {
    }

    public record AttendanceView(
            String id,
            Instant occurredAt,
            String direction,
            String method,
            String result,
            String deviceUserId,
            Long deviceRecNo,
            boolean memberLinked,
            Instant createdAt) {

        public static AttendanceView from(AttendanceEvent e) {
            return new AttendanceView(e.getPublicId(), e.getOccurredAt(), e.getDirection().name(),
                    e.getMethod(), e.getResult().name(), e.getDeviceUserId(), e.getDeviceRecNo(),
                    e.getMemberId() != null, e.getCreatedAt());
        }
    }

    public record SecurityEventView(
            String id,
            String type,
            Instant occurredAt,
            String details,
            boolean acknowledged,
            Instant createdAt) {

        public static SecurityEventView from(SecurityEvent s) {
            return new SecurityEventView(s.getPublicId(), s.getType(), s.getOccurredAt(),
                    s.getDetails(), s.isAcknowledged(), s.getCreatedAt());
        }
    }
}
