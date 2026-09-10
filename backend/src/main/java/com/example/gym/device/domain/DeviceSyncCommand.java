package com.example.gym.device.domain;

import com.example.gym.common.domain.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Transactional-outbox work item (§9). A business change and its sync command are written in the
 * same transaction; a dispatcher later delivers it to the gateway with retry/backoff, and the
 * gateway's {@code SYNC_RESULT} drives it to a terminal state. Idempotency is keyed on
 * {@code correlationId}.
 */
@Entity
@Table(name = "device_sync_command")
public class DeviceSyncCommand extends TenantAwareEntity {

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "member_id")
    private Long memberId;

    @Column(name = "membership_id")
    private Long membershipId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private SyncCommandType type;

    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private SyncCommandState state = SyncCommandState.PENDING;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "correlation_id", nullable = false, length = 36)
    private String correlationId;

    @Column(name = "dispatched_at")
    private Instant dispatchedAt;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected DeviceSyncCommand() {
    }

    public DeviceSyncCommand(Long tenantId, Long deviceId, Long memberId, Long membershipId,
                             SyncCommandType type, String payload, String correlationId,
                             int maxAttempts, Instant nextAttemptAt) {
        setTenantId(tenantId);
        this.deviceId = deviceId;
        this.memberId = memberId;
        this.membershipId = membershipId;
        this.type = type;
        this.payload = payload;
        this.correlationId = correlationId;
        this.maxAttempts = maxAttempts;
        this.nextAttemptAt = nextAttemptAt;
        this.state = SyncCommandState.PENDING;
    }

    public Long getDeviceId() {
        return deviceId;
    }

    public Long getMemberId() {
        return memberId;
    }

    public Long getMembershipId() {
        return membershipId;
    }

    public SyncCommandType getType() {
        return type;
    }

    public String getPayload() {
        return payload;
    }

    public SyncCommandState getState() {
        return state;
    }

    public void setState(SyncCommandState state) {
        this.state = state;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(int attemptCount) {
        this.attemptCount = attemptCount;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public void setNextAttemptAt(Instant nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Instant getDispatchedAt() {
        return dispatchedAt;
    }

    public void setDispatchedAt(Instant dispatchedAt) {
        this.dispatchedAt = dispatchedAt;
    }

    public Instant getAcknowledgedAt() {
        return acknowledgedAt;
    }

    public void setAcknowledgedAt(Instant acknowledgedAt) {
        this.acknowledgedAt = acknowledgedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }
}
