package com.example.gym.face;

import com.example.gym.common.domain.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The single current face photo of a member (a resized JPEG on the faces volume). Each change bumps
 * {@code faceVersion}; devices report which version they hold via the member-device mapping.
 */
@Entity
@Table(name = "member_face")
public class MemberFace extends TenantAwareEntity {

    @Column(name = "member_id", nullable = false, unique = true)
    private Long memberId;

    @Column(name = "object_key", nullable = false, length = 255)
    private String objectKey;

    @Column(name = "sha256", nullable = false, length = 64)
    private String sha256;

    @Column(name = "face_version", nullable = false)
    private int faceVersion;

    @Column(name = "size_bytes", nullable = false)
    private int sizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16)
    private FaceSource source = FaceSource.MANUAL;

    @Column(name = "source_device_id")
    private Long sourceDeviceId;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    protected MemberFace() {
    }

    public MemberFace(Long tenantId, Long memberId) {
        setTenantId(tenantId);
        this.memberId = memberId;
        this.faceVersion = 0;
    }

    public Long getMemberId() {
        return memberId;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public String getSha256() {
        return sha256;
    }

    public int getFaceVersion() {
        return faceVersion;
    }

    public int getSizeBytes() {
        return sizeBytes;
    }

    public FaceSource getSource() {
        return source;
    }

    public Long getSourceDeviceId() {
        return sourceDeviceId;
    }

    public Instant getChangedAt() {
        return changedAt;
    }

    /** Points this face at a newly stored image and bumps the version. */
    public void replace(String objectKey, String sha256, int sizeBytes, FaceSource source,
                        Long sourceDeviceId, Instant changedAt) {
        this.faceVersion = this.faceVersion + 1;
        this.objectKey = objectKey;
        this.sha256 = sha256;
        this.sizeBytes = sizeBytes;
        this.source = source;
        this.sourceDeviceId = sourceDeviceId;
        this.changedAt = changedAt;
    }
}
