package com.example.gym.face;

import com.example.gym.common.domain.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** A face image the gateway read from a device and uploaded, referenced by DEVICE_USER_CHANGED. */
@Entity
@Table(name = "gateway_face_upload")
public class GatewayFaceUpload extends TenantAwareEntity {

    @Column(name = "gateway_id", nullable = false)
    private Long gatewayId;

    @Column(name = "object_key", nullable = false, length = 255)
    private String objectKey;

    @Column(name = "sha256", nullable = false, length = 64)
    private String sha256;

    @Column(name = "size_bytes", nullable = false)
    private int sizeBytes;

    @Column(name = "consumed", nullable = false)
    private boolean consumed;

    protected GatewayFaceUpload() {
    }

    public GatewayFaceUpload(Long tenantId, Long gatewayId, String objectKey, String sha256, int sizeBytes) {
        setTenantId(tenantId);
        this.gatewayId = gatewayId;
        this.objectKey = objectKey;
        this.sha256 = sha256;
        this.sizeBytes = sizeBytes;
    }

    public Long getGatewayId() {
        return gatewayId;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public String getSha256() {
        return sha256;
    }

    public int getSizeBytes() {
        return sizeBytes;
    }

    public boolean isConsumed() {
        return consumed;
    }

    public void markConsumed() {
        this.consumed = true;
    }
}
