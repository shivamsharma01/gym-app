package com.example.gym.device.domain;

import com.example.gym.common.domain.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A security-relevant device signal: denied access, unknown credential, tamper/alarm, or
 * connectivity change. Surfaced to the security-alerts view (permission SECURITY_ALERT_VIEW).
 */
@Entity
@Table(name = "security_event")
public class SecurityEvent extends TenantAwareEntity {

    @Column(name = "device_id")
    private Long deviceId;

    @Column(name = "type", nullable = false, length = 48)
    private String type;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "details", columnDefinition = "TEXT")
    private String details;

    @Column(name = "acknowledged", nullable = false)
    private boolean acknowledged = false;

    protected SecurityEvent() {
    }

    public SecurityEvent(Long tenantId, Long deviceId, String type, Instant occurredAt, String details) {
        setTenantId(tenantId);
        this.deviceId = deviceId;
        this.type = type;
        this.occurredAt = occurredAt;
        this.details = details;
        this.acknowledged = false;
    }

    public Long getDeviceId() {
        return deviceId;
    }

    public String getType() {
        return type;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getDetails() {
        return details;
    }

    public boolean isAcknowledged() {
        return acknowledged;
    }

    public void setAcknowledged(boolean acknowledged) {
        this.acknowledged = acknowledged;
    }
}
