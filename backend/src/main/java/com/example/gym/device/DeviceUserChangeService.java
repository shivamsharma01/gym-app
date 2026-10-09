package com.example.gym.device;

import com.example.gym.device.domain.Device;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * A reader report is an observation. It is stored for review. It does not change the member,
 * create a member, or write another reader.
 */
@Service
public class DeviceUserChangeService {

    private final PendingEnrollmentService pendingEnrollments;

    public DeviceUserChangeService(PendingEnrollmentService pendingEnrollments) {
        this.pendingEnrollments = pendingEnrollments;
    }

    @Transactional
    public void apply(Device device, JsonNode payload) {
        pendingEnrollments.observe(device, payload);
    }
}
