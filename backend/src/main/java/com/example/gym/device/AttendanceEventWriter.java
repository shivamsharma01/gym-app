package com.example.gym.device;

import com.example.gym.device.domain.AttendanceEvent;
import com.example.gym.device.domain.AttendanceSyncCursor;
import com.example.gym.device.domain.SecurityEvent;
import com.example.gym.device.repo.AttendanceEventRepository;
import com.example.gym.device.repo.AttendanceSyncCursorRepository;
import com.example.gym.device.repo.SecurityEventRepository;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inserts an attendance row in its own transaction. When a copy of the same event was stored at the
 * same moment (unique fingerprint), the insert fails with DataIntegrityViolationException and only
 * this small transaction rolls back; the caller's transaction stays usable. Catching the exception
 * inside the caller's transaction instead would leave it marked rollback-only.
 *
 * <p>Each write is short and holds nothing across punches. A punch row takes a shared lock on its
 * device row; a caller transaction holding such a lock while waiting for these inserts would queue
 * them behind any device update, and that wait is invisible to MySQL's deadlock check.
 */
@Component
public class AttendanceEventWriter {

    private final AttendanceEventRepository attendanceRepository;
    private final SecurityEventRepository securityEvents;
    private final AttendanceSyncCursorRepository cursors;

    public AttendanceEventWriter(AttendanceEventRepository attendanceRepository,
                                 SecurityEventRepository securityEvents,
                                 AttendanceSyncCursorRepository cursors) {
        this.attendanceRepository = attendanceRepository;
        this.securityEvents = securityEvents;
        this.cursors = cursors;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AttendanceEvent insert(AttendanceEvent event) {
        return attendanceRepository.saveAndFlush(event);
    }

    /** The punch and its security event, if any, commit together. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AttendanceEvent insert(AttendanceEvent event, SecurityEvent alarm) {
        AttendanceEvent saved = attendanceRepository.saveAndFlush(event);
        if (alarm != null) {
            securityEvents.save(alarm);
        }
        return saved;
    }

    /** Moves the reader's cursor forward, never back. The first cursor of a reader may race another window. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Instant advanceCursor(Long tenantId, Long deviceId, Instant latest) {
        AttendanceSyncCursor cursor = cursors.lockByDeviceId(deviceId)
                .orElseGet(() -> new AttendanceSyncCursor(tenantId, deviceId));
        if (cursor.getLastEventAt() == null || latest.isAfter(cursor.getLastEventAt())) {
            cursor.setLastEventAt(latest);
            cursors.saveAndFlush(cursor);
        }
        return cursor.getLastEventAt();
    }
}
