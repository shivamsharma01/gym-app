package com.example.gym.device.domain;

import com.example.gym.common.domain.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A reader's last stored punch time. The gateway's attendance poll starts its next window here, less
 * a small overlap. It only moves forward. Record numbers are not a cursor.
 */
@Entity
@Table(name = "attendance_sync_cursor")
public class AttendanceSyncCursor extends TenantAwareEntity {

    @Column(name = "device_id", nullable = false, unique = true)
    private Long deviceId;

    @Column(name = "last_event_at")
    private Instant lastEventAt;

    protected AttendanceSyncCursor() {
    }

    public AttendanceSyncCursor(Long tenantId, Long deviceId) {
        setTenantId(tenantId);
        this.deviceId = deviceId;
    }

    public Long getDeviceId() {
        return deviceId;
    }

    public Instant getLastEventAt() {
        return lastEventAt;
    }

    public void setLastEventAt(Instant lastEventAt) {
        this.lastEventAt = lastEventAt;
    }
}
