package com.example.gym.device.domain;

import com.example.gym.common.domain.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * The last reader record that matched the server. Later observations are compared with this, not
 * with a timestamp.
 */
@Entity
@Table(name = "device_reader_baseline")
public class DeviceReaderBaseline extends TenantAwareEntity {

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

    @Column(name = "face_sha256", length = 64)
    private String faceSha256;

    @Column(name = "verified_epoch", length = 64)
    private String verifiedEpoch;

    @Column(name = "verified_seq")
    private Long verifiedSeq;

    @Column(name = "authority", length = 32)
    private String authority;

    protected DeviceReaderBaseline() {
    }

    public DeviceReaderBaseline(Long tenantId, Long deviceId, String deviceUserId) {
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

    public String getVerifiedEpoch() {
        return verifiedEpoch;
    }

    /** The last scan the gateway had taken when it read this record back; null when unknown. */
    public Long getVerifiedSeq() {
        return verifiedSeq;
    }

    public void verifiedAfter(String epoch, Long seq) {
        this.verifiedEpoch = epoch;
        this.verifiedSeq = seq;
    }
}
