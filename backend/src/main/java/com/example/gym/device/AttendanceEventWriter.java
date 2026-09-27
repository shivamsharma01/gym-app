package com.example.gym.device;

import com.example.gym.device.domain.AttendanceEvent;
import com.example.gym.device.repo.AttendanceEventRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inserts an attendance row in its own transaction. When a copy of the same event was stored at the
 * same moment (unique fingerprint), the insert fails with DataIntegrityViolationException and only
 * this small transaction rolls back; the caller's transaction stays usable. Catching the exception
 * inside the caller's transaction instead would leave it marked rollback-only.
 */
@Component
public class AttendanceEventWriter {

    private final AttendanceEventRepository attendanceRepository;

    public AttendanceEventWriter(AttendanceEventRepository attendanceRepository) {
        this.attendanceRepository = attendanceRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AttendanceEvent insert(AttendanceEvent event) {
        return attendanceRepository.saveAndFlush(event);
    }
}
