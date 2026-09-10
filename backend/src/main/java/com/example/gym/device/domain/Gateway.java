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

    /** SHA-256 hex of the gateway's authentication token. The plaintext is shown once at create. */
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

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
}
