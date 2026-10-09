package com.example.gym.device;

import com.example.gym.common.logging.FlowLog;
import com.example.gym.device.domain.Device;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * A reconcile user list is an observation for attendance context. It does not create, update, or
 * remove members.
 */
@Service
public class DeviceReconciliationService {

    @Transactional
    public void applyDeviceUserSnapshot(Device device, JsonNode payload) {
        FlowLog.info("reconcile", "device={} user list is an observation, not a member write",
                device.getPublicId());
    }
}
