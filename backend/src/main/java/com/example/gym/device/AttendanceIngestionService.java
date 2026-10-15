package com.example.gym.device;

import com.example.gym.common.logging.FlowLog;
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
import com.example.gym.live.StaffLiveBroadcast;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores punches the gateway read from a reader's log for one time window. Rows are append-only:
 * a punch already stored is skipped, never rewritten. A punch is credited through the reader's
 * mapping; an unmapped device user id is stored without a member. Member and mapping rows are only read.
 */
@Service
public class AttendanceIngestionService {

    private final AttendanceEventRepository attendanceRepository;
    private final AttendanceSyncCursorRepository cursorRepository;
    private final MemberDeviceMappingRepository mappingRepository;
    private final ApplicationEventPublisher events;
    private final AttendanceEventWriter writer;

    public AttendanceIngestionService(AttendanceEventRepository attendanceRepository,
                                      AttendanceSyncCursorRepository cursorRepository,
                                      MemberDeviceMappingRepository mappingRepository,
                                      ApplicationEventPublisher events,
                                      AttendanceEventWriter writer) {
        this.attendanceRepository = attendanceRepository;
        this.cursorRepository = cursorRepository;
        this.mappingRepository = mappingRepository;
        this.events = events;
        this.writer = writer;
    }

    /** One punch as read from the reader log. {@code occurredAt} is the reader's stuTime taken as UTC. */
    public record Punch(String deviceUserId, Instant occurredAt, String method, boolean granted,
                        Long recNo, String denyReason) {
    }

    /** The punches newly stored and the reader's last stored punch time after this window. */
    public record WindowResult(int stored, Instant lastEventAt) {
    }

    /**
     * Stores every punch of the window that is not stored yet. The cursor moves to the latest punch
     * that is now stored, and never backwards. An empty window writes nothing.
     *
     * <p>A punch dated after the server's clock is stored but does not move the cursor: the next
     * window would start after it and skip real punches until that time arrives.
     *
     * <p>Not one transaction: each punch commits on its own with its security event, then the
     * cursor. A window cut short leaves the cursor where it was, and the resent punches are skipped.
     */
    public WindowResult storeWindow(Device device, List<Punch> punches) {
        if (punches.isEmpty()) {
            return new WindowResult(0, lastEventAt(device));
        }
        Instant now = Instant.now();
        int stored = 0;
        int future = 0;
        Instant latest = null;
        for (Punch punch : punches) {
            if (ingest(device, punch).isPresent()) {
                stored++;
            }
            if (punch.occurredAt().isAfter(now)) {
                future++;
            } else if (latest == null || punch.occurredAt().isAfter(latest)) {
                latest = punch.occurredAt();
            }
        }
        if (future > 0) {
            FlowLog.warn("attendance", "device={} sent {} punch(es) dated after server time {}; stored, cursor not moved by them",
                    device.getPublicId(), future, now);
        }
        return new WindowResult(stored, latest == null ? lastEventAt(device) : advanceCursor(device, latest));
    }

    @Transactional(readOnly = true)
    public Instant lastEventAt(Device device) {
        return cursorRepository.findByDeviceId(device.getId())
                .map(AttendanceSyncCursor::getLastEventAt)
                .orElse(null);
    }

    /** @return the stored punch, or empty when this punch was already stored. */
    private Optional<AttendanceEvent> ingest(Device device, Punch punch) {
        Long tenantId = device.getTenantId();
        String fingerprint = fingerprint(punch);
        if (attendanceRepository.existsByTenantIdAndDeviceIdAndFingerprint(tenantId, device.getId(), fingerprint)
                || storedBeforePolling(tenantId, device.getId(), punch)) {
            return Optional.empty();
        }

        String deviceUserId = punch.deviceUserId();
        MemberDeviceMapping mapping = deviceUserId == null ? null
                : mappingRepository.findByDeviceIdAndDeviceUserId(device.getId(), deviceUserId)
                        .or(() -> mappingRepository.findFirstByDeviceIdAndPendingDeviceUserId(
                                device.getId(), deviceUserId))
                        .orElse(null);
        Long memberId = mapping == null ? null : mapping.getMemberId();
        AccessResult result = punch.granted() ? AccessResult.GRANTED : AccessResult.DENIED;
        String denyReason = punch.granted() ? null : punch.denyReason();

        AttendanceEvent event = new AttendanceEvent(tenantId, device.getId(), memberId, deviceUserId,
                punch.occurredAt(), directionFor(device.getRole()), punch.method(), result, punch.recNo(),
                fingerprint, denyReason);
        try {
            event = writer.insert(event, securityEvent(tenantId, device.getId(), punch.granted(), memberId,
                    deviceUserId, punch.occurredAt(), denyReason));
        } catch (DataIntegrityViolationException dup) {
            // Stored by a concurrent copy of the same window.
            return Optional.empty();
        }

        events.publishEvent(new StaffLiveBroadcast(tenantId, punch.granted() ? "ATTENDANCE" : "ACCESS_DENIED",
                Map.of(
                        "deviceId", device.getPublicId(),
                        "deviceUserId", deviceUserId == null ? "" : deviceUserId,
                        "result", result.name(),
                        "direction", event.getDirection().name(),
                        "occurredAt", punch.occurredAt().toString(),
                        "memberLinked", memberId != null,
                        "denyReason", denyReason == null ? "" : denyReason)));
        if (punch.granted()) {
            FlowLog.debug("attendance", "granted device={} user={} direction={} memberLinked={}",
                    device.getPublicId(), deviceUserId, event.getDirection(), memberId != null);
        } else {
            FlowLog.info("attendance", "denied device={} user={} direction={} reason={} memberLinked={}",
                    device.getPublicId(), deviceUserId, event.getDirection(), denyReason, memberId != null);
        }
        return Optional.of(event);
    }

    /**
     * The same punch stored before the poll existed, under the record-number key or the live-event key.
     * A record number alone is not trusted: the stored time must match too.
     */
    private boolean storedBeforePolling(Long tenantId, Long deviceId, Punch punch) {
        if (punch.recNo() != null && attendanceRepository
                .findByTenantIdAndDeviceIdAndFingerprint(tenantId, deviceId, "rec:" + punch.recNo())
                .filter(e -> punch.occurredAt().equals(e.getOccurredAt()))
                .isPresent()) {
            return true;
        }
        String live = "cmp:" + punch.deviceUserId() + "|" + punch.occurredAt().toEpochMilli() + "|"
                + punch.method() + "|" + (punch.granted() ? "G" : "D");
        return attendanceRepository.existsByTenantIdAndDeviceIdAndFingerprint(tenantId, deviceId, live);
    }

    private Instant advanceCursor(Device device, Instant latest) {
        try {
            return writer.advanceCursor(device.getTenantId(), device.getId(), latest);
        } catch (DataIntegrityViolationException firstCursorRace) {
            return writer.advanceCursor(device.getTenantId(), device.getId(), latest);
        }
    }

    private static SecurityEvent securityEvent(Long tenantId, Long deviceId, boolean granted, Long memberId,
                                               String deviceUserId, Instant occurredAt, String denyReason) {
        if (!granted) {
            String details = "Access denied for deviceUserId=" + deviceUserId;
            if (denyReason != null) {
                details = details + " reason=" + denyReason;
            }
            return new SecurityEvent(tenantId, deviceId, "ACCESS_DENIED", occurredAt, details);
        }
        if (memberId == null) {
            return new SecurityEvent(tenantId, deviceId, "UNKNOWN_CREDENTIAL",
                    occurredAt, "Granted access for unmapped deviceUserId=" + deviceUserId);
        }
        return null;
    }

    private AccessDirection directionFor(DeviceRole role) {
        return switch (role) {
            case ENTRANCE -> AccessDirection.IN;
            case EXIT -> AccessDirection.OUT;
            case UNSPECIFIED -> AccessDirection.UNKNOWN;
        };
    }

    /**
     * Who punched, when, how, the result and the record number. The record number alone is not the
     * key: whether it survives a reader reboot or a full log is not established.
     */
    static String fingerprint(Punch punch) {
        return "pt:" + punch.deviceUserId() + "|" + punch.occurredAt().toEpochMilli() + "|" + punch.method()
                + "|" + (punch.granted() ? "G" : "D") + "|" + (punch.recNo() == null ? "-" : punch.recNo());
    }

    /** Reader error code of a denied punch as a reason. */
    public static String denyReason(Integer errorCode) {
        if (errorCode == null) {
            return null;
        }
        return switch (errorCode) {
            case 0x00 -> null;
            case 0x10 -> "UNAUTHORIZED";
            case 0x14 -> "VALIDITY_PERIOD";
            case 0x20, 0x21 -> "PERIOD_ERROR";
            case 0x23 -> "OVERDUE";
            default -> "ERR_0x" + Integer.toHexString(errorCode).toUpperCase();
        };
    }
}
