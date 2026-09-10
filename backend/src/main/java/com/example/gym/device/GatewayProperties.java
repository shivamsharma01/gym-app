package com.example.gym.device;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the device-gateway link and the sync outbox dispatcher. */
@ConfigurationProperties(prefix = "app.gateway")
public class GatewayProperties {

    /**
     * Shared token a gateway must present during the WebSocket handshake. Empty disables the check
     * (dev only) — production must set a strong value via the environment.
     */
    private String sharedToken = "";

    /** Minutes without a heartbeat after which a gateway is considered OFFLINE. */
    private long heartbeatTimeoutSeconds = 90;

    /**
     * When true, commands are applied by the in-process simulator instead of waiting for a LAN
     * gateway. Never enable in production — it is a development/demo stand-in, not hardware.
     */
    private boolean simulatorEnabled = false;

    private final Outbox outbox = new Outbox();

    public String getSharedToken() {
        return sharedToken;
    }

    public void setSharedToken(String sharedToken) {
        this.sharedToken = sharedToken;
    }

    public long getHeartbeatTimeoutSeconds() {
        return heartbeatTimeoutSeconds;
    }

    public void setHeartbeatTimeoutSeconds(long heartbeatTimeoutSeconds) {
        this.heartbeatTimeoutSeconds = heartbeatTimeoutSeconds;
    }

    public boolean isSimulatorEnabled() {
        return simulatorEnabled;
    }

    public void setSimulatorEnabled(boolean simulatorEnabled) {
        this.simulatorEnabled = simulatorEnabled;
    }

    public Outbox getOutbox() {
        return outbox;
    }

    /** Retry policy for the sync command outbox. */
    public static class Outbox {
        private boolean dispatcherEnabled = true;
        private int batchSize = 50;
        private int maxAttempts = 6;
        private Duration baseBackoff = Duration.ofSeconds(5);
        private Duration maxBackoff = Duration.ofMinutes(10);
        private Duration jitter = Duration.ofSeconds(1);

        public boolean isDispatcherEnabled() {
            return dispatcherEnabled;
        }

        public void setDispatcherEnabled(boolean dispatcherEnabled) {
            this.dispatcherEnabled = dispatcherEnabled;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Duration getBaseBackoff() {
            return baseBackoff;
        }

        public void setBaseBackoff(Duration baseBackoff) {
            this.baseBackoff = baseBackoff;
        }

        public Duration getMaxBackoff() {
            return maxBackoff;
        }

        public void setMaxBackoff(Duration maxBackoff) {
            this.maxBackoff = maxBackoff;
        }

        public Duration getJitter() {
            return jitter;
        }

        public void setJitter(Duration jitter) {
            this.jitter = jitter;
        }
    }
}
