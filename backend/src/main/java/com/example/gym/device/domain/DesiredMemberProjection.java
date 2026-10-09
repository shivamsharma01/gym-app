package com.example.gym.device.domain;

import com.example.gym.common.domain.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * The member this reader should hold at one revision. The gateway writes this record and reads it
 * back. The row does not store a public id for the adapter.
 */
@Entity
@Table(name = "desired_member")
public class DesiredMemberProjection extends TenantAwareEntity {

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    /** Null only for a rejected enrollment: the device user is removed and no member is created. */
    @Column(name = "member_id")
    private Long memberId;

    @Column(name = "revision", nullable = false)
    private long revision;

    @Column(name = "device_user_id", nullable = false, length = 64)
    private String deviceUserId;

    @Column(name = "present_on_reader", nullable = false)
    private boolean presentOnReader;

    /** A staff link keeps this reader id and replaces the user already stored there. */
    @Column(name = "keep_device_user_id", nullable = false)
    private boolean keepDeviceUserId;

    @Column(name = "reader_name", nullable = false, length = 31)
    private String readerName;

    @Column(name = "reader_name_ex", length = 127)
    private String readerNameEx;

    @Column(name = "user_status", nullable = false)
    private int userStatus;

    @Column(name = "valid_from", nullable = false, length = 40)
    private String validFrom;

    @Column(name = "valid_to", nullable = false, length = 40)
    private String validTo;

    @Column(name = "authority", nullable = false, length = 32)
    private String authority;

    @Column(name = "door_num", nullable = false)
    private int doorNum;

    @Column(name = "time_section_num", nullable = false)
    private int timeSectionNum;

    /** False means the user stays on the reader and the face photo does not. */
    @Column(name = "face_present", nullable = false)
    private boolean facePresent = true;

    @Column(name = "face_sha256", nullable = false, length = 64)
    private String faceSha256;

    @Column(name = "observed_face_sha256", length = 64)
    private String observedFaceSha256;

    protected DesiredMemberProjection() {
    }

    public DesiredMemberProjection(Long tenantId, Long deviceId, Long memberId) {
        setTenantId(tenantId);
        this.deviceId = deviceId;
        this.memberId = memberId;
    }

    public Long getDeviceId() {
        return deviceId;
    }

    public Long getMemberId() {
        return memberId;
    }

    public long getRevision() {
        return revision;
    }

    public void setRevision(long revision) {
        this.revision = revision;
    }

    public String getDeviceUserId() {
        return deviceUserId;
    }

    public void setDeviceUserId(String deviceUserId) {
        this.deviceUserId = deviceUserId;
    }

    public boolean isPresentOnReader() {
        return presentOnReader;
    }

    public void setPresentOnReader(boolean presentOnReader) {
        this.presentOnReader = presentOnReader;
    }

    public boolean isKeepDeviceUserId() {
        return keepDeviceUserId;
    }

    public void setKeepDeviceUserId(boolean keepDeviceUserId) {
        this.keepDeviceUserId = keepDeviceUserId;
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

    public int getDoorNum() {
        return doorNum;
    }

    public void setDoorNum(int doorNum) {
        this.doorNum = doorNum;
    }

    public int getTimeSectionNum() {
        return timeSectionNum;
    }

    public void setTimeSectionNum(int timeSectionNum) {
        this.timeSectionNum = timeSectionNum;
    }

    public boolean isFacePresent() {
        return facePresent;
    }

    public void setFacePresent(boolean facePresent) {
        this.facePresent = facePresent;
    }

    public String getFaceSha256() {
        return faceSha256;
    }

    public void setFaceSha256(String faceSha256) {
        this.faceSha256 = faceSha256;
    }

    public String getObservedFaceSha256() {
        return observedFaceSha256;
    }

    public void setObservedFaceSha256(String observedFaceSha256) {
        this.observedFaceSha256 = observedFaceSha256;
    }
}
