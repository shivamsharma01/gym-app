package com.example.gym.device.domain;

import com.example.gym.common.domain.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

/** One reader's observation on a shared conflict. A second reader does not replace it. */
@Entity
@Table(name = "device_review_snapshot")
public class DeviceReviewSnapshot extends TenantAwareEntity {

    @Column(name = "review_item_id", nullable = false)
    private Long reviewItemId;

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "device_user_id", nullable = false, length = 64)
    private String deviceUserId;

    @Column(name = "reader_name", length = 127)
    private String readerName;

    @Column(name = "reader_name_ex", length = 127)
    private String readerNameEx;

    @Column(name = "reader_authority", length = 32)
    private String readerAuthority;

    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;

    protected DeviceReviewSnapshot() {
    }

    public DeviceReviewSnapshot(Long tenantId, Long reviewItemId, Long deviceId, String deviceUserId) {
        setTenantId(tenantId);
        this.reviewItemId = reviewItemId;
        this.deviceId = deviceId;
        this.deviceUserId = deviceUserId;
    }

    public Long getReviewItemId() {
        return reviewItemId;
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

    public String getReaderAuthority() {
        return readerAuthority;
    }

    public void setReaderAuthority(String readerAuthority) {
        this.readerAuthority = readerAuthority;
    }

    public Instant getObservedAt() {
        return observedAt;
    }

    public void setObservedAt(Instant observedAt) {
        this.observedAt = observedAt;
    }
}
