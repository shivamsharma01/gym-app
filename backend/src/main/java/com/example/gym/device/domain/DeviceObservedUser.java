package com.example.gym.device.domain;

import com.example.gym.common.domain.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * What a trusted reader list last showed for one device user id. This is not the roster-import cache.
 */
@Entity
@Table(name = "device_observed_user")
public class DeviceObservedUser extends TenantAwareEntity {

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "device_user_id", nullable = false, length = 64)
    private String deviceUserId;

    @Column(name = "reader_name", length = 127)
    private String readerName;

    @Column(name = "reader_name_ex", length = 127)
    private String readerNameEx;

    @Column(name = "user_status", nullable = false)
    private int userStatus;

    @Column(name = "valid_from", length = 40)
    private String validFrom;

    @Column(name = "valid_to", length = 40)
    private String validTo;

    @Column(name = "authority", length = 32)
    private String authority;

    @Column(name = "face_sha256", length = 64)
    private String faceSha256;

    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;

    protected DeviceObservedUser() {
    }

    public DeviceObservedUser(Long tenantId, Long deviceId, String deviceUserId) {
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

    public String getReaderName() {
        return readerName;
    }

    public void setReaderName(String readerName) {
        this.readerName = readerName;
    }

    public String getReaderNameEx() {
        return readerNameEx;
    }

    public void setReaderNameEx(String readerNameEx) {
        this.readerNameEx = readerNameEx;
    }

    public int getUserStatus() {
        return userStatus;
    }

    public void setUserStatus(int userStatus) {
        this.userStatus = userStatus;
    }

    public String getValidFrom() {
        return validFrom;
    }

    public void setValidFrom(String validFrom) {
        this.validFrom = validFrom;
    }

    public String getValidTo() {
        return validTo;
    }

    public void setValidTo(String validTo) {
        this.validTo = validTo;
    }

    public String getAuthority() {
        return authority;
    }

    public void setAuthority(String authority) {
        this.authority = authority;
    }

    public String getFaceSha256() {
        return faceSha256;
    }

    public void setFaceSha256(String faceSha256) {
        this.faceSha256 = faceSha256;
    }

    public Instant getObservedAt() {
        return observedAt;
    }

    public void setObservedAt(Instant observedAt) {
        this.observedAt = observedAt;
    }
}
