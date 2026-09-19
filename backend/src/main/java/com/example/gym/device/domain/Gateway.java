package com.example.gym.device.domain;

import com.example.gym.common.domain.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A device gateway process running on a gym LAN. It is the only component that speaks the native
 * SDK; it connects outbound to the backend over WSS. The backend stores no device credentials —
 * those live with the gateway on the LAN.
 *
 * <p>Staff create a gateway and receive a one-time enrollment token. The Windows agent exchanges
 * that for a long-lived operational credential ({@code tokenHash}), which may be rotated via
 * {@code nextTokenHash} without locking the agent out.
 */
@Entity
@Table(name = "gateway")
public class Gateway extends TenantAwareEntity {

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private GatewayStatus status = GatewayStatus.UNKNOWN;

    @Column(name = "last_heartbeat_at")
    private Instant lastHeartbeatAt;

    @Column(name = "last_registered_at")
    private Instant lastRegisteredAt;

    @Column(name = "agent_version", length = 40)
    private String agentVersion;

    /** SHA-256 hex of the current operational credential. Placeholder until first enrollment. */
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "enrollment_token_hash", length = 64)
    private String enrollmentTokenHash;

    @Column(name = "enrollment_expires_at")
    private Instant enrollmentExpiresAt;

    @Column(name = "enrollment_consumed_at")
    private Instant enrollmentConsumedAt;

    @Column(name = "token_expires_at")
    private Instant tokenExpiresAt;

    /** SHA-256 hex of a pending rotated credential; promoted on first successful auth with it. */
    @Column(name = "next_token_hash", length = 64)
    private String nextTokenHash;

    protected Gateway() {
    }

    public Gateway(Long tenantId, String name, String tokenHash) {
        setTenantId(tenantId);
        this.name = name;
        this.status = GatewayStatus.UNKNOWN;
        this.tokenHash = tokenHash;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public GatewayStatus getStatus() {
        return status;
    }

    public void setStatus(GatewayStatus status) {
        this.status = status;
    }

    public Instant getLastHeartbeatAt() {
        return lastHeartbeatAt;
    }

    public void setLastHeartbeatAt(Instant lastHeartbeatAt) {
        this.lastHeartbeatAt = lastHeartbeatAt;
    }

    public Instant getLastRegisteredAt() {
        return lastRegisteredAt;
    }

    public void setLastRegisteredAt(Instant lastRegisteredAt) {
        this.lastRegisteredAt = lastRegisteredAt;
    }

    public String getAgentVersion() {
        return agentVersion;
    }

    public void setAgentVersion(String agentVersion) {
        this.agentVersion = agentVersion;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public String getEnrollmentTokenHash() {
        return enrollmentTokenHash;
    }

    public void setEnrollmentTokenHash(String enrollmentTokenHash) {
        this.enrollmentTokenHash = enrollmentTokenHash;
    }

    public Instant getEnrollmentExpiresAt() {
        return enrollmentExpiresAt;
    }

    public void setEnrollmentExpiresAt(Instant enrollmentExpiresAt) {
        this.enrollmentExpiresAt = enrollmentExpiresAt;
    }

    public Instant getEnrollmentConsumedAt() {
        return enrollmentConsumedAt;
    }

    public void setEnrollmentConsumedAt(Instant enrollmentConsumedAt) {
        this.enrollmentConsumedAt = enrollmentConsumedAt;
    }

    public Instant getTokenExpiresAt() {
        return tokenExpiresAt;
    }

    public void setTokenExpiresAt(Instant tokenExpiresAt) {
        this.tokenExpiresAt = tokenExpiresAt;
    }

    public String getNextTokenHash() {
        return nextTokenHash;
    }

    public void setNextTokenHash(String nextTokenHash) {
        this.nextTokenHash = nextTokenHash;
    }
}
