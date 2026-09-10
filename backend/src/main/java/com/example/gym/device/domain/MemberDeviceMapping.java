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
 * Links an application {@code Member} to their identity on a specific device. Stores only the
 * opaque device-user id and enrolment/sync status — never a face image or template (data
 * minimisation; biometrics remain on the device).
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
}
