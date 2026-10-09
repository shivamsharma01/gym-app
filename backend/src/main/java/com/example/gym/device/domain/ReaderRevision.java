package com.example.gym.device.domain;

import com.example.gym.common.domain.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** Monotonic desired revision and the last revision this reader acknowledged. */
@Entity
@Table(name = "reader_revision")
public class ReaderRevision extends TenantAwareEntity {

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "desired_revision", nullable = false)
    private long desiredRevision;

    @Column(name = "applied_revision", nullable = false)
    private long appliedRevision;

    protected ReaderRevision() {
    }

    public ReaderRevision(Long tenantId, Long deviceId) {
        setTenantId(tenantId);
        this.deviceId = deviceId;
    }

    public Long getDeviceId() {
        return deviceId;
    }

    public long getDesiredRevision() {
        return desiredRevision;
    }

    public long getAppliedRevision() {
        return appliedRevision;
    }

    public long bumpDesired() {
        desiredRevision++;
        return desiredRevision;
    }

    public void setAppliedRevision(long appliedRevision) {
        this.appliedRevision = appliedRevision;
    }
}
