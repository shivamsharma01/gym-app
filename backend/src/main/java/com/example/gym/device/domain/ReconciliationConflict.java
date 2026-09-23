package com.example.gym.device.domain;

import com.example.gym.common.domain.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Auditable mismatch between MySQL desired authorization and a device's user list.
 * Extra device users are never auto-imported; staff must resolve explicitly.
 */
@Entity
@Table(name = "reconciliation_conflict")
public class ReconciliationConflict extends TenantAwareEntity {

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "device_user_id", nullable = false, length = 64)
    private String deviceUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "conflict_type", nullable = false, length = 32)
    private ReconciliationConflictType conflictType;

    @Column(name = "details", columnDefinition = "TEXT")
    private String details;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ReconciliationConflictStatus status = ReconciliationConflictStatus.OPEN;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected ReconciliationConflict() {
    }

    public ReconciliationConflict(Long tenantId, Long deviceId, String deviceUserId,
                                  ReconciliationConflictType conflictType, String details) {
        setTenantId(tenantId);
        this.deviceId = deviceId;
        this.deviceUserId = deviceUserId;
        this.conflictType = conflictType;
        this.details = details;
        this.status = ReconciliationConflictStatus.OPEN;
    }

    public Long getDeviceId() {
        return deviceId;
    }

    public String getDeviceUserId() {
        return deviceUserId;
    }

    public ReconciliationConflictType getConflictType() {
        return conflictType;
    }

    public String getDetails() {
        return details;
    }

    public ReconciliationConflictStatus getStatus() {
        return status;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void resolve() {
        this.status = ReconciliationConflictStatus.RESOLVED;
        this.resolvedAt = Instant.now();
    }

    public void dismiss() {
        this.status = ReconciliationConflictStatus.DISMISSED;
        this.resolvedAt = Instant.now();
    }
}
