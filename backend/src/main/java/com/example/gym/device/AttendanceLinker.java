package com.example.gym.device;

import com.example.gym.common.logging.FlowLog;
import com.example.gym.device.domain.MemberDeviceMapping;
import com.example.gym.device.repo.AttendanceEventRepository;
import com.example.gym.live.StaffLiveBroadcast;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Credits attendance stored before a reader's user was mapped (e.g. a punch that arrived ahead of
 * the reader's user report). Matches on the reader's own id, never the member serial, and only
 * fills events that have no member yet.
 */
@Component
public class AttendanceLinker {

    static final String LINKED_BROADCAST = "ATTENDANCE_LINKED";
    private static final Object BROADCAST_SENT = new Object();

    private final AttendanceEventRepository attendanceRepository;
    private final ApplicationEventPublisher events;

    public AttendanceLinker(AttendanceEventRepository attendanceRepository, ApplicationEventPublisher events) {
        this.attendanceRepository = attendanceRepository;
        this.events = events;
    }

    /** Must run inside the transaction that saved {@code mapping}. */
    public int linkEarlierEvents(MemberDeviceMapping mapping) {
        int linked = attendanceRepository.linkUnmatched(mapping.getTenantId(), mapping.getDeviceId(),
                mapping.getDeviceUserId(), mapping.getMemberId());
        if (linked > 0) {
            FlowLog.info("attendance", "linked {} earlier event(s) device={} user={} member={}",
                    linked, mapping.getDeviceId(), mapping.getDeviceUserId(), mapping.getMemberId());
            broadcastOncePerTransaction(mapping.getTenantId());
        }
        return linked;
    }

    private void broadcastOncePerTransaction(Long tenantId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            if (TransactionSynchronizationManager.hasResource(BROADCAST_SENT)) {
                return;
            }
            TransactionSynchronizationManager.bindResource(BROADCAST_SENT, Boolean.TRUE);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(BROADCAST_SENT);
                }
            });
        }
        events.publishEvent(new StaffLiveBroadcast(tenantId, LINKED_BROADCAST, Map.of()));
    }
}
