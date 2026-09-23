package com.example.gym.device.domain;

import com.example.gym.common.domain.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * Last-known access-user enumeration for a device (from RECONCILIATION_RESULT). Used by roster
 * import; never stores biometrics.
 */
@Entity
@Table(name = "device_user_snapshot")
public class DeviceUserSnapshotRow extends TenantAwareEntity {

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "device_user_id", nullable = false, length = 64)
    private String deviceUserId;

    @Column(name = "name", length = 120)
    private String name;

    @Column(name = "frozen", nullable = false)
    private boolean frozen;

    @Column(name = "valid_from")
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private LocalDate validTo;

    protected DeviceUserSnapshotRow() {
    }

    public DeviceUserSnapshotRow(Long tenantId, Long deviceId, String deviceUserId, String name,
                                 boolean frozen, LocalDate validFrom, LocalDate validTo) {
        setTenantId(tenantId);
        this.deviceId = deviceId;
        this.deviceUserId = deviceUserId;
        this.name = name;
        this.frozen = frozen;
        this.validFrom = validFrom;
        this.validTo = validTo;
    }

    public Long getDeviceId() {
        return deviceId;
    }

    public String getDeviceUserId() {
        return deviceUserId;
    }

    public String getName() {
        return name;
    }

    public boolean isFrozen() {
        return frozen;
    }

    public LocalDate getValidFrom() {
        return validFrom;
    }

    public LocalDate getValidTo() {
        return validTo;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setFrozen(boolean frozen) {
        this.frozen = frozen;
    }

    public void setValidFrom(LocalDate validFrom) {
        this.validFrom = validFrom;
    }

    public void setValidTo(LocalDate validTo) {
        this.validTo = validTo;
    }
}
