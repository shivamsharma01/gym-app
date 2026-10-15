package com.example.gym.device;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Drops gateway message ids whose replay window has passed. */
@Component
@ConditionalOnProperty(prefix = "app.gateway.outbox", name = "dispatcher-enabled",
        havingValue = "true", matchIfMissing = true)
public class GatewayMessageDedupePurge {

    private final GatewayMessageDedupeService dedupeService;

    public GatewayMessageDedupePurge(GatewayMessageDedupeService dedupeService) {
        this.dedupeService = dedupeService;
    }

    @Scheduled(fixedDelayString = "${app.gateway.dedupe-purge-interval-ms:900000}")
    public void purgeExpired() {
        dedupeService.purgeExpired();
    }
}
