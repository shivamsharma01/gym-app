package com.example.gym.device.domain;

import com.example.gym.common.domain.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A physical access-control terminal (TrueFace3000 / Dahua). The backend stores connection
 * coordinates and reported metadata/health only — never the device password (that stays with the
 * gateway on the LAN, per the security model).
 */
@Entity
@Table(name = "device")
public class Device extends TenantAwareEntity {

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    /** Owning gateway (internal id); null until assigned. */
    @Column(name = "gateway_id")
    private Long gatewayId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private DeviceRole role = DeviceRole.UNSPECIFIED;

    @Column(name = "host", length = 120)
    private String host;

    @Column(name = "port")
    private Integer port;

    @Column(name = "model", length = 80)
    private String model;

    @Column(name = "serial_number", length = 80)
    private String serialNumber;

    @Column(name = "firmware", length = 80)
    private String firmware;

    @Enumerated(EnumType.STRING)
    @Column(name = "connection_state", nullable = false, length = 16)
    private DeviceConnectionState connectionState = DeviceConnectionState.UNKNOWN;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    protected Device() {
    }

    public Device(Long tenantId, String name, DeviceRole role) {
        setTenantId(tenantId);
        this.name = name;
        this.role = role == null ? DeviceRole.UNSPECIFIED : role;
        this.connectionState = DeviceConnectionState.UNKNOWN;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Long getGatewayId() {
        return gatewayId;
    }

    public void setGatewayId(Long gatewayId) {
        this.gatewayId = gatewayId;
    }

    public DeviceRole getRole() {
        return role;
    }

    public void setRole(DeviceRole role) {
        this.role = role == null ? DeviceRole.UNSPECIFIED : role;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getSerialNumber() {
        return serialNumber;
    }

    public void setSerialNumber(String serialNumber) {
        this.serialNumber = serialNumber;
    }

    public String getFirmware() {
        return firmware;
    }

    public void setFirmware(String firmware) {
        this.firmware = firmware;
    }

    public DeviceConnectionState getConnectionState() {
        return connectionState;
    }

    public void setConnectionState(DeviceConnectionState connectionState) {
        this.connectionState = connectionState;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(Instant lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }
}
