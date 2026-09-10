package com.example.gym.device;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Marks ONLINE gateways OFFLINE when heartbeats stop arriving. */
@Component
public class GatewayHeartbeatWatchdog {

    private static final Logger log = LoggerFactory.getLogger(GatewayHeartbeatWatchdog.class);

    private final GatewayService gatewayService;
    private final GatewayProperties properties;

    public GatewayHeartbeatWatchdog(GatewayService gatewayService, GatewayProperties properties) {
        this.gatewayService = gatewayService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.gateway.heartbeat-timeout-seconds:90}000")
    public void markStale() {
        java.time.Instant cutoff = java.time.Instant.now()
                .minusSeconds(Math.max(properties.getHeartbeatTimeoutSeconds(), 1));
        int n = gatewayService.markStaleOffline(cutoff);
        if (n > 0) {
            log.info("Marked {} gateway(s) OFFLINE after missed heartbeat", n);
        }
    }
}
