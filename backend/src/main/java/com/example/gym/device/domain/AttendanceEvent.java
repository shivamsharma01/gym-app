package com.example.gym.device.domain;

import com.example.gym.common.domain.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A normalized access/attendance event ingested from a device (real-time or via reconciliation).
 * De-duplication is enforced by a unique {@code (tenant_id, device_id, fingerprint)} — the
 * fingerprint prefers the device's stable record number and otherwise falls back to a conservative
 * composite (§10), so a real-time event and a later reconciliation of the same physical event never
 * double-count.
 */
@Entity
@Table(name = "attendance_event")
public class AttendanceEvent extends TenantAwareEntity {

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    /** Resolved application member; null when the device user could not be mapped. */
    @Column(name = "member_id")
    private Long memberId;

    @Column(name = "device_user_id", length = 64)
    private String deviceUserId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 8)
    private AccessDirection direction = AccessDirection.UNKNOWN;

    @Column(name = "method", length = 32)
    private String method;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false, length = 8)
    private AccessResult result = AccessResult.UNKNOWN;

    /** Stable per-device record number when supplied by the SDK (nRecNo); null otherwise. */
    @Column(name = "device_rec_no")
    private Long deviceRecNo;

    @Column(name = "fingerprint", nullable = false, length = 120)
    private String fingerprint;

    /** Device nErrorCode / deny reason when result is DENIED; null for grants. */
    @Column(name = "deny_reason", length = 64)
    private String denyReason;

    protected AttendanceEvent() {
    }

    public AttendanceEvent(Long tenantId, Long deviceId, Long memberId, String deviceUserId,
                           Instant occurredAt, AccessDirection direction, String method,
                           AccessResult result, Long deviceRecNo, String fingerprint) {
        this(tenantId, deviceId, memberId, deviceUserId, occurredAt, direction, method, result,
                deviceRecNo, fingerprint, null);
    }

    public AttendanceEvent(Long tenantId, Long deviceId, Long memberId, String deviceUserId,
                           Instant occurredAt, AccessDirection direction, String method,
                           AccessResult result, Long deviceRecNo, String fingerprint,
                           String denyReason) {
        setTenantId(tenantId);
        this.deviceId = deviceId;
        this.memberId = memberId;
        this.deviceUserId = deviceUserId;
        this.occurredAt = occurredAt;
        this.direction = direction;
        this.method = method;
        this.result = result;
        this.deviceRecNo = deviceRecNo;
        this.fingerprint = fingerprint;
        this.denyReason = denyReason;
    }

    public Long getDeviceId() {
        return deviceId;
    }

    public Long getMemberId() {
        return memberId;
    }

    public String getDeviceUserId() {
        return deviceUserId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public AccessDirection getDirection() {
        return direction;
    }

    public String getMethod() {
        return method;
    }

    public AccessResult getResult() {
        return result;
    }

    public Long getDeviceRecNo() {
        return deviceRecNo;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public String getDenyReason() {
        return denyReason;
    }

    public void setDeviceRecNo(Long deviceRecNo) {
        this.deviceRecNo = deviceRecNo;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public void setDenyReason(String denyReason) {
        this.denyReason = denyReason;
    }
}
