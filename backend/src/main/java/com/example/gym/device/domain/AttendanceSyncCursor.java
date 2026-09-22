package com.example.gym.device.domain;

import com.example.gym.common.domain.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Per-device attendance watermark (§10). On reconnect/reconciliation the gateway queries device
 * records after this point, so no window is missed and none is re-processed.
 */
@Entity
@Table(name = "attendance_sync_cursor")
public class AttendanceSyncCursor extends TenantAwareEntity {

    @Column(name = "device_id", nullable = false, unique = true)
    private Long deviceId;

    @Column(name = "last_rec_no")
    private Long lastRecNo;

    @Column(name = "last_event_at")
    private Instant lastEventAt;

    @Column(name = "reconciliation_required", nullable = false)
    private boolean reconciliationRequired = false;

    protected AttendanceSyncCursor() {
    }

    public AttendanceSyncCursor(Long tenantId, Long deviceId) {
        setTenantId(tenantId);
        this.deviceId = deviceId;
    }

    public Long getDeviceId() {
        return deviceId;
    }

    public Long getLastRecNo() {
        return lastRecNo;
    }

    public void setLastRecNo(Long lastRecNo) {
        this.lastRecNo = lastRecNo;
    }

    public Instant getLastEventAt() {
        return lastEventAt;
    }

    public void setLastEventAt(Instant lastEventAt) {
        this.lastEventAt = lastEventAt;
    }

    public boolean isReconciliationRequired() {
        return reconciliationRequired;
    }

    public void setReconciliationRequired(boolean reconciliationRequired) {
        this.reconciliationRequired = reconciliationRequired;
    }
}
