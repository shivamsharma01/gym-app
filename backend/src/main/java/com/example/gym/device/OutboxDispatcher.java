package com.example.gym.device;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically drains the sync outbox. Persistence-backed (not fire-and-forget threads from
 * controllers, §9). Disabled in tests so command dispatch can be driven deterministically.
 */
@Component
@ConditionalOnProperty(prefix = "app.gateway.outbox", name = "dispatcher-enabled",
        havingValue = "true", matchIfMissing = true)
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final DeviceSyncService deviceSyncService;

    public OutboxDispatcher(DeviceSyncService deviceSyncService) {
        this.deviceSyncService = deviceSyncService;
    }

    @Scheduled(fixedDelayString = "${app.gateway.outbox.dispatch-interval-ms:10000}")
    public void drain() {
        try {
            int dispatched = deviceSyncService.dispatchDue();
            if (dispatched > 0) {
                log.debug("Dispatched {} device sync command(s)", dispatched);
            }
        } catch (RuntimeException ex) {
            log.error("Outbox dispatch cycle failed", ex);
        }
    }
}
