package com.example.gym.device;

import com.example.gym.device.domain.Device;
import com.example.gym.device.repo.DeviceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically enqueues RECONCILE_DEVICE so attendance gaps and user drift are repaired without
 * waiting for a manual Sync Now.
 */
@Component
@ConditionalOnProperty(prefix = "app.gateway.outbox", name = "dispatcher-enabled",
        havingValue = "true", matchIfMissing = true)
public class AutoReconcileScheduler {

    private static final Logger log = LoggerFactory.getLogger(AutoReconcileScheduler.class);

    private final DeviceRepository deviceRepository;
    private final DeviceService deviceService;
    private final GatewayMessageDedupeService dedupeService;

    public AutoReconcileScheduler(DeviceRepository deviceRepository,
                                  DeviceService deviceService,
                                  GatewayMessageDedupeService dedupeService) {
        this.deviceRepository = deviceRepository;
        this.deviceService = deviceService;
        this.dedupeService = dedupeService;
    }

    @Scheduled(fixedDelayString = "${app.gateway.auto-reconcile-interval-ms:900000}")
    public void enqueueReconciles() {
        int n = 0;
        for (Device device : deviceRepository.findAll()) {
            if (device.getGatewayId() != null) {
                deviceService.enqueueReconcileIfAbsent(device);
                n++;
            }
        }
        dedupeService.purgeExpired();
        if (n > 0) {
            log.debug("Auto-reconcile pass considered {} device(s)", n);
        }
    }
}
