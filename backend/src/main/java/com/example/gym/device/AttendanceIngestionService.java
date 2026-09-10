package com.example.gym.device;

import com.example.gym.device.domain.AccessDirection;
import com.example.gym.device.domain.AccessResult;
import com.example.gym.device.domain.AttendanceEvent;
import com.example.gym.device.domain.AttendanceSyncCursor;
import com.example.gym.device.domain.Device;
import com.example.gym.device.domain.DeviceRole;
import com.example.gym.device.domain.MemberDeviceMapping;
import com.example.gym.device.domain.SecurityEvent;
import com.example.gym.device.repo.AttendanceEventRepository;
import com.example.gym.device.repo.AttendanceSyncCursorRepository;
import com.example.gym.device.repo.MemberDeviceMappingRepository;
import com.example.gym.device.repo.SecurityEventRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Normalizes and persists device access events (§10). De-duplicates by a per-device fingerprint
 * (stable record number when available, else a conservative composite), maps the device user to an
 * application member, resolves direction from the device role, advances the per-device watermark,
 * and raises a security event for denied/unknown-credential access.
 */
@Service
public class AttendanceIngestionService {

    private final AttendanceEventRepository attendanceRepository;
    private final AttendanceSyncCursorRepository cursorRepository;
    private final MemberDeviceMappingRepository mappingRepository;
    private final SecurityEventRepository securityEventRepository;

    public AttendanceIngestionService(AttendanceEventRepository attendanceRepository,
                                      AttendanceSyncCursorRepository cursorRepository,
                                      MemberDeviceMappingRepository mappingRepository,
                                      SecurityEventRepository securityEventRepository) {
        this.attendanceRepository = attendanceRepository;
        this.cursorRepository = cursorRepository;
        this.mappingRepository = mappingRepository;
        this.securityEventRepository = securityEventRepository;
    }

    /** @return the persisted event, or empty when it was a duplicate. */
    @Transactional
    public Optional<AttendanceEvent> ingest(Device device, String deviceUserId, Instant occurredAt,
                                            String method, boolean granted, Long deviceRecNo) {
        Long tenantId = device.getTenantId();
        String fingerprint = fingerprint(deviceUserId, occurredAt, method, granted, deviceRecNo);

        if (attendanceRepository.existsByTenantIdAndDeviceIdAndFingerprint(
                tenantId, device.getId(), fingerprint)) {
            return Optional.empty();
        }

        MemberDeviceMapping mapping = deviceUserId == null ? null
                : mappingRepository.findByDeviceIdAndDeviceUserId(device.getId(), deviceUserId).orElse(null);
        Long memberId = mapping == null ? null : mapping.getMemberId();

        AccessResult result = granted ? AccessResult.GRANTED : AccessResult.DENIED;
        AttendanceEvent event = new AttendanceEvent(tenantId, device.getId(), memberId, deviceUserId,
                occurredAt, directionFor(device.getRole()), method, result, deviceRecNo, fingerprint);

        try {
            attendanceRepository.save(event);
        } catch (DataIntegrityViolationException dup) {
            // Lost a race on the unique dedupe key — another ingest already persisted it.
            return Optional.empty();
        }

        advanceWatermark(tenantId, device.getId(), deviceRecNo, occurredAt);
        maybeRaiseSecurityEvent(tenantId, device.getId(), granted, memberId, deviceUserId, occurredAt);
        return Optional.of(event);
    }

    private void advanceWatermark(Long tenantId, Long deviceId, Long deviceRecNo, Instant occurredAt) {
        AttendanceSyncCursor cursor = cursorRepository.findByDeviceId(deviceId)
                .orElseGet(() -> new AttendanceSyncCursor(tenantId, deviceId));
        if (deviceRecNo != null && (cursor.getLastRecNo() == null || deviceRecNo > cursor.getLastRecNo())) {
            cursor.setLastRecNo(deviceRecNo);
        }
        if (occurredAt != null && (cursor.getLastEventAt() == null
                || occurredAt.isAfter(cursor.getLastEventAt()))) {
            cursor.setLastEventAt(occurredAt);
        }
        cursorRepository.save(cursor);
    }

    private void maybeRaiseSecurityEvent(Long tenantId, Long deviceId, boolean granted, Long memberId,
                                         String deviceUserId, Instant occurredAt) {
        if (!granted) {
            securityEventRepository.save(new SecurityEvent(tenantId, deviceId, "ACCESS_DENIED",
                    occurredAt, "Access denied for deviceUserId=" + deviceUserId));
        } else if (memberId == null) {
            securityEventRepository.save(new SecurityEvent(tenantId, deviceId, "UNKNOWN_CREDENTIAL",
                    occurredAt, "Granted access for unmapped deviceUserId=" + deviceUserId));
        }
    }

    private AccessDirection directionFor(DeviceRole role) {
        return switch (role) {
            case ENTRANCE -> AccessDirection.IN;
            case EXIT -> AccessDirection.OUT;
            case UNSPECIFIED -> AccessDirection.UNKNOWN;
        };
    }

    private String fingerprint(String deviceUserId, Instant occurredAt, String method, boolean granted,
                               Long deviceRecNo) {
        if (deviceRecNo != null) {
            return "rec:" + deviceRecNo;
        }
        long epoch = occurredAt == null ? 0L : occurredAt.toEpochMilli();
        return "cmp:" + deviceUserId + "|" + epoch + "|" + method + "|" + (granted ? "G" : "D");
    }
}
