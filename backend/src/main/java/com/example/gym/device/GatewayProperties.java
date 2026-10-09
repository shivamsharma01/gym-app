package com.example.gym.device;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the device-gateway link and the sync outbox dispatcher. */
@ConfigurationProperties(prefix = "app.gateway")
public class GatewayProperties {

    /** Minutes without a heartbeat after which a gateway is considered OFFLINE. */
    private long heartbeatTimeoutSeconds = 90;

    /**
     * When true, commands are applied by the in-process simulator instead of waiting for a LAN
     * gateway. Never enable in production — it is a development/demo stand-in, not hardware.
     */
    private boolean simulatorEnabled = false;

    /** How long a one-time enrollment token remains usable after gateway create/reissue. */
    private Duration enrollmentTtl = Duration.ofHours(24);

    /** Lifetime of an operational gateway credential after enroll or rotate. */
    private Duration credentialTtl = Duration.ofDays(90);

    /**
     * Whether devices refuse entry outside a user's valid-from / valid-to dates by themselves
     * (TrueFace does). When true, a paid membership that has not started yet is sent enabled with
     * its dates; when false it is sent disabled and enabled by the access check on its start day.
     */
    private boolean devicesEnforceValidityDates = true;

    private final Outbox outbox = new Outbox();

    public boolean isDevicesEnforceValidityDates() {
        return devicesEnforceValidityDates;
    }

    public void setDevicesEnforceValidityDates(boolean devicesEnforceValidityDates) {
        this.devicesEnforceValidityDates = devicesEnforceValidityDates;
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

    public Duration getEnrollmentTtl() {
        return enrollmentTtl;
    }

    public void setEnrollmentTtl(Duration enrollmentTtl) {
        this.enrollmentTtl = enrollmentTtl;
    }

    public Duration getCredentialTtl() {
        return credentialTtl;
    }

    public void setCredentialTtl(Duration credentialTtl) {
        this.credentialTtl = credentialTtl;
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
        /** How long a DISPATCHED command may wait for SYNC_RESULT before reclaim. */
        private Duration dispatchTimeout = Duration.ofMinutes(2);
        /** How often a command waiting for an offline gateway is checked again (no attempt is used). */
        private Duration offlineRecheck = Duration.ofMinutes(1);

        public Duration getOfflineRecheck() {
            return offlineRecheck;
        }

        public void setOfflineRecheck(Duration offlineRecheck) {
            this.offlineRecheck = offlineRecheck;
        }

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

        public Duration getDispatchTimeout() {
            return dispatchTimeout;
        }

        public void setDispatchTimeout(Duration dispatchTimeout) {
            this.dispatchTimeout = dispatchTimeout;
        }
    }

    /** How long gateway messageId entries are retained for replay dedupe. */
    private Duration messageDedupeTtl = Duration.ofHours(24);

    /** Interval for automatic attendance/user reconcile enqueue (0 disables). */
    private Duration autoReconcileInterval = Duration.ofMinutes(15);

    public Duration getMessageDedupeTtl() {
        return messageDedupeTtl;
    }

    public void setMessageDedupeTtl(Duration messageDedupeTtl) {
        this.messageDedupeTtl = messageDedupeTtl;
    }

    public Duration getAutoReconcileInterval() {
        return autoReconcileInterval;
    }

    public void setAutoReconcileInterval(Duration autoReconcileInterval) {
        this.autoReconcileInterval = autoReconcileInterval;
    }
}
