package com.example.gym.device.domain;

import com.example.gym.common.domain.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One open difference for a mapped user on one reader. It keeps the server, reader, and baseline
 * snapshots. It does not change the member.
 */
@Entity
@Table(name = "device_review_item")
public class DeviceReviewItem extends TenantAwareEntity {

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "device_user_id", nullable = false, length = 64)
    private String deviceUserId;

    @Column(name = "baseline_name", length = 127)
    private String baselineName;

    @Column(name = "baseline_name_ex", length = 127)
    private String baselineNameEx;

    @Column(name = "baseline_authority", length = 32)
    private String baselineAuthority;

    @Column(name = "server_name", length = 127)
    private String serverName;

    @Column(name = "server_name_ex", length = 127)
    private String serverNameEx;

    @Column(name = "server_authority", length = 32)
    private String serverAuthority;

    @Column(name = "reader_name", length = 127)
    private String readerName;

    @Column(name = "reader_name_ex", length = 127)
    private String readerNameEx;

    @Column(name = "reader_authority", length = 32)
    private String readerAuthority;

    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;

    protected DeviceReviewItem() {
    }

    public DeviceReviewItem(Long tenantId, Long deviceId, Long memberId, String deviceUserId) {
        setTenantId(tenantId);
        this.deviceId = deviceId;
        this.memberId = memberId;
        this.deviceUserId = deviceUserId;
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

    public String getBaselineName() {
        return baselineName;
    }

    public void setBaselineName(String baselineName) {
        this.baselineName = baselineName;
    }

    public String getBaselineNameEx() {
        return baselineNameEx;
    }

    public void setBaselineNameEx(String baselineNameEx) {
        this.baselineNameEx = baselineNameEx;
    }

    public String getBaselineAuthority() {
        return baselineAuthority;
    }

    public void setBaselineAuthority(String baselineAuthority) {
        this.baselineAuthority = baselineAuthority;
    }

    public String getServerName() {
        return serverName;
    }

    public void setServerName(String serverName) {
        this.serverName = serverName;
    }

    public String getServerNameEx() {
        return serverNameEx;
    }

    public void setServerNameEx(String serverNameEx) {
        this.serverNameEx = serverNameEx;
    }

    public String getServerAuthority() {
        return serverAuthority;
    }

    public void setServerAuthority(String serverAuthority) {
        this.serverAuthority = serverAuthority;
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
