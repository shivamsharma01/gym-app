package com.example.gym.device.domain;

import com.example.gym.common.domain.TenantAwareEntity;
import com.example.gym.membership.DeviceSyncState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Links an application {@code Member} to their identity on a specific device: the device-user id,
 * enrolment/sync status and which face photo version the device holds. Never a face template.
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

    @Enumerated(EnumType.STRING)
    @Column(name = "enrollment_status", nullable = false, length = 20)
    private EnrollmentStatus enrollmentStatus = EnrollmentStatus.PENDING_ENROLL;

    @Enumerated(EnumType.STRING)
    @Column(name = "sync_state", nullable = false, length = 16)
    private DeviceSyncState syncState = DeviceSyncState.NOT_SYNCED;

    @Column(name = "enrolled_at")
    private Instant enrolledAt;

    /** Face version the device is known to hold (null = none). */
    @Column(name = "face_version_synced")
    private Integer faceVersionSynced;

    @Enumerated(EnumType.STRING)
    @Column(name = "face_sync_state", nullable = false, length = 16)
    private DeviceSyncState faceSyncState = DeviceSyncState.NOT_SYNCED;

    @Column(name = "face_last_error", length = 500)
    private String faceLastError;

    protected MemberDeviceMapping() {
    }

    public MemberDeviceMapping(Long tenantId, Long memberId, Long deviceId, String deviceUserId) {
        setTenantId(tenantId);
        this.memberId = memberId;
        this.deviceId = deviceId;
        this.deviceUserId = deviceUserId;
        this.enrollmentStatus = EnrollmentStatus.PENDING_ENROLL;
        this.syncState = DeviceSyncState.NOT_SYNCED;
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

    public EnrollmentStatus getEnrollmentStatus() {
        return enrollmentStatus;
    }

    public void setEnrollmentStatus(EnrollmentStatus enrollmentStatus) {
        this.enrollmentStatus = enrollmentStatus;
    }

    public DeviceSyncState getSyncState() {
        return syncState;
    }

    public void setSyncState(DeviceSyncState syncState) {
        this.syncState = syncState;
    }

    public Instant getEnrolledAt() {
        return enrolledAt;
    }

    public void setEnrolledAt(Instant enrolledAt) {
        this.enrolledAt = enrolledAt;
    }

    public Integer getFaceVersionSynced() {
        return faceVersionSynced;
    }

    public void setFaceVersionSynced(Integer faceVersionSynced) {
        this.faceVersionSynced = faceVersionSynced;
    }

    public DeviceSyncState getFaceSyncState() {
        return faceSyncState;
    }

    public void setFaceSyncState(DeviceSyncState faceSyncState) {
        this.faceSyncState = faceSyncState;
    }

    public String getFaceLastError() {
        return faceLastError;
    }

    public void setFaceLastError(String faceLastError) {
        this.faceLastError = faceLastError == null || faceLastError.length() <= 500
                ? faceLastError : faceLastError.substring(0, 500);
    }
}
