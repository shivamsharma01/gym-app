package com.example.gym.device.domain;

import com.example.gym.common.domain.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Links an application {@code Member} to their identity on a specific device: the device-user id
 * that addresses them there. Never a face template.
 */
@Entity
@Table(name = "member_device_mapping")
public class MemberDeviceMapping extends TenantAwareEntity {

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    /** The user id used to address this member on the device. */
    @Column(name = "device_user_id", nullable = false, length = 64)
    private String deviceUserId;

    /**
     * The serial this reader is moving the member to. The member keeps {@link #deviceUserId} until
     * the reader confirms the new user exists.
     */
    @Column(name = "pending_device_user_id", length = 64)
    private String pendingDeviceUserId;

    protected MemberDeviceMapping() {
    }

    public MemberDeviceMapping(Long tenantId, Long memberId, Long deviceId, String deviceUserId) {
        setTenantId(tenantId);
        this.memberId = memberId;
        this.deviceId = deviceId;
        this.deviceUserId = deviceUserId;
    }

    public Long getMemberId() {
        return memberId;
    }

    public Long getDeviceId() {
        return deviceId;
    }

    public String getDeviceUserId() {
        return deviceUserId;
    }

    public void setDeviceUserId(String deviceUserId) {
        this.deviceUserId = deviceUserId;
    }

    public String getPendingDeviceUserId() {
        return pendingDeviceUserId;
    }

    public void setPendingDeviceUserId(String pendingDeviceUserId) {
        this.pendingDeviceUserId = pendingDeviceUserId;
    }
}
