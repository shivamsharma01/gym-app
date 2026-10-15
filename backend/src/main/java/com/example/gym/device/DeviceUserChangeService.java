package com.example.gym.device;

import com.example.gym.device.domain.Device;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * A reader report is an observation. It is stored for review. It does not change the member,
 * create a member, or write another reader. It is processed under the reader's lock, in the
 * reader's scan order: an observation older than what the server already has changes nothing.
 * Everything after the lock is read from the latest commits, so a seed or decision committed while
 * this observation waited for the lock is seen.
 */
@Service
public class DeviceUserChangeService {

    private final PendingEnrollmentService pendingEnrollments;
    private final ObservationOrder order;

    public DeviceUserChangeService(PendingEnrollmentService pendingEnrollments, ObservationOrder order) {
        this.pendingEnrollments = pendingEnrollments;
        this.order = order;
    }

    /** @throws ObservationOrder.Unordered when the observation has no usable scan stamp */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void apply(Device device, JsonNode payload) {
        ObservationOrder.Scan scan = order.admit(device, payload);
        if (!scan.stale()) {
            pendingEnrollments.observe(device, payload, scan);
        }
    }
}
