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
import com.example.gym.live.StaffLiveBroadcast;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Normalizes and persists device access events (§10). Prefers stable {@code rec:{nRecNo}}
 * fingerprints; when a later reconcile supplies a recNo for a live composite event, upgrades that
 * row instead of inserting a duplicate.
 */
@Service
public class AttendanceIngestionService {

    private final AttendanceEventRepository attendanceRepository;
    private final AttendanceSyncCursorRepository cursorRepository;
    private final MemberDeviceMappingRepository mappingRepository;
    private final SecurityEventRepository securityEventRepository;
    private final ApplicationEventPublisher events;

    public AttendanceIngestionService(AttendanceEventRepository attendanceRepository,
                                      AttendanceSyncCursorRepository cursorRepository,
                                      MemberDeviceMappingRepository mappingRepository,
                                      SecurityEventRepository securityEventRepository,
                                      ApplicationEventPublisher events) {
        this.attendanceRepository = attendanceRepository;
        this.cursorRepository = cursorRepository;
        this.mappingRepository = mappingRepository;
        this.securityEventRepository = securityEventRepository;
        this.events = events;
    }

    /** @return the persisted event, or empty when it was a duplicate. */
    @Transactional
    public Optional<AttendanceEvent> ingest(Device device, String deviceUserId, Instant occurredAt,
                                            String method, boolean granted, Long deviceRecNo) {
        return ingest(device, deviceUserId, occurredAt, method, granted, deviceRecNo, null);
    }

    @Transactional
    public Optional<AttendanceEvent> ingest(Device device, String deviceUserId, Instant occurredAt,
                                            String method, boolean granted, Long deviceRecNo,
                                            String denyReason) {
        Long tenantId = device.getTenantId();
        AccessResult result = granted ? AccessResult.GRANTED : AccessResult.DENIED;

        if (deviceRecNo != null) {
            String recFp = "rec:" + deviceRecNo;
            if (attendanceRepository.existsByTenantIdAndDeviceIdAndFingerprint(
                    tenantId, device.getId(), recFp)) {
                return Optional.empty();
            }
            Optional<AttendanceEvent> upgrade = tryUpgradeComposite(
                    tenantId, device.getId(), deviceUserId, occurredAt, method, result, deviceRecNo,
                    denyReason);
            if (upgrade.isPresent()) {
                advanceWatermark(tenantId, device.getId(), deviceRecNo, occurredAt);
                clearReconciliationRequired(device.getId());
                return upgrade;
            }
        }

        String fingerprint = fingerprint(deviceUserId, occurredAt, method, granted, deviceRecNo);
        if (attendanceRepository.existsByTenantIdAndDeviceIdAndFingerprint(
                tenantId, device.getId(), fingerprint)) {
            return Optional.empty();
        }

        MemberDeviceMapping mapping = deviceUserId == null ? null
                : mappingRepository.findByDeviceIdAndDeviceUserId(device.getId(), deviceUserId).orElse(null);
        Long memberId = mapping == null ? null : mapping.getMemberId();

        AttendanceEvent event = new AttendanceEvent(tenantId, device.getId(), memberId, deviceUserId,
                occurredAt, directionFor(device.getRole()), method, result, deviceRecNo, fingerprint,
                granted ? null : denyReason);

        try {
            attendanceRepository.save(event);
        } catch (DataIntegrityViolationException dup) {
            return Optional.empty();
        }

        advanceWatermark(tenantId, device.getId(), deviceRecNo, occurredAt);
        clearReconciliationRequired(device.getId());
        maybeRaiseSecurityEvent(tenantId, device.getId(), granted, memberId, deviceUserId, occurredAt,
                denyReason);
        events.publishEvent(new StaffLiveBroadcast(tenantId, granted ? "ATTENDANCE" : "ACCESS_DENIED",
                Map.of(
                        "deviceId", device.getPublicId(),
                        "deviceUserId", deviceUserId == null ? "" : deviceUserId,
                        "result", result.name(),
                        "direction", event.getDirection().name(),
                        "occurredAt", occurredAt == null ? "" : occurredAt.toString(),
                        "memberLinked", memberId != null,
                        "denyReason", denyReason == null ? "" : denyReason)));
        return Optional.of(event);
    }

    @Transactional
    public void markReconciliationRequired(Long tenantId, Long deviceId, boolean required) {
        AttendanceSyncCursor cursor = cursorRepository.findByDeviceId(deviceId)
                .orElseGet(() -> new AttendanceSyncCursor(tenantId, deviceId));
        cursor.setReconciliationRequired(required);
        cursorRepository.save(cursor);
    }

    private Optional<AttendanceEvent> tryUpgradeComposite(Long tenantId, Long deviceId,
                                                          String deviceUserId, Instant occurredAt,
                                                          String method, AccessResult result,
                                                          Long deviceRecNo, String denyReason) {
        if (deviceUserId == null || occurredAt == null) {
            return Optional.empty();
        }
        Optional<AttendanceEvent> existing = attendanceRepository
                .findFirstByTenantIdAndDeviceIdAndDeviceUserIdAndOccurredAtAndMethodAndResultAndDeviceRecNoIsNull(
                        tenantId, deviceId, deviceUserId, occurredAt, method, result);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        AttendanceEvent event = existing.get();
        event.setDeviceRecNo(deviceRecNo);
        event.setFingerprint("rec:" + deviceRecNo);
        if (denyReason != null && event.getDenyReason() == null) {
            event.setDenyReason(denyReason);
        }
        return Optional.of(attendanceRepository.save(event));
    }

    private void clearReconciliationRequired(Long deviceId) {
        cursorRepository.findByDeviceId(deviceId).ifPresent(cursor -> {
            if (cursor.isReconciliationRequired()) {
                cursor.setReconciliationRequired(false);
                cursorRepository.save(cursor);
            }
        });
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
                                         String deviceUserId, Instant occurredAt, String denyReason) {
        if (!granted) {
            String details = "Access denied for deviceUserId=" + deviceUserId;
            if (denyReason != null) {
                details = details + " reason=" + denyReason;
            }
            securityEventRepository.save(new SecurityEvent(tenantId, deviceId, "ACCESS_DENIED",
                    occurredAt, details));
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
