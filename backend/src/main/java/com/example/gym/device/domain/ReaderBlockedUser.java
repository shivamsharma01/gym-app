package com.example.gym.device.domain;

import com.example.gym.common.domain.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** A device user id the reader already holds, so the next allocation does not reuse it. */
@Entity
@Table(name = "reader_blocked_user")
public class ReaderBlockedUser extends TenantAwareEntity {

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "device_user_id", nullable = false, length = 64)
    private String deviceUserId;

    protected ReaderBlockedUser() {
    }

    public ReaderBlockedUser(Long tenantId, Long deviceId, String deviceUserId) {
        setTenantId(tenantId);
        this.deviceId = deviceId;
        this.deviceUserId = deviceUserId;
    }

    public Long getDeviceId() {
        return deviceId;
    }

    public String getDeviceUserId() {
        return deviceUserId;
    }
}
