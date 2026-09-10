package com.example.gym.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * An immutable audit record. Written for security-relevant and state-changing actions. Contains
 * no secrets, tokens, passwords or biometric data — only who/what/when/outcome plus safe details.
 */
@Entity
@Table(name = "audit_log")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true, updatable = false, length = 36)
    private String publicId;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "actor_username", length = 100)
    private String actorUsername;

    @Column(name = "action", nullable = false, length = 80)
    private String action;

    @Column(name = "resource_type", length = 80)
    private String resourceType;

    @Column(name = "resource_id", length = 80)
    private String resourceId;

    @Column(name = "result", nullable = false, length = 20)
    private String result;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "user_agent", length = 256)
    private String userAgent;

    @Column(name = "correlation_id", length = 36)
    private String correlationId;

    @Column(name = "details", columnDefinition = "TEXT")
    private String details;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AuditLog() {
    }

    @PrePersist
    void onCreate() {
        if (publicId == null) {
            publicId = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    // Builder-style setters used by AuditService.
    public AuditLog tenantId(Long v) { this.tenantId = v; return this; }
    public AuditLog actorUserId(Long v) { this.actorUserId = v; return this; }
    public AuditLog actorUsername(String v) { this.actorUsername = v; return this; }
    public AuditLog action(String v) { this.action = v; return this; }
    public AuditLog resourceType(String v) { this.resourceType = v; return this; }
    public AuditLog resourceId(String v) { this.resourceId = v; return this; }
    public AuditLog result(String v) { this.result = v; return this; }
    public AuditLog ipAddress(String v) { this.ipAddress = v; return this; }
    public AuditLog userAgent(String v) { this.userAgent = v; return this; }
    public AuditLog correlationId(String v) { this.correlationId = v; return this; }
    public AuditLog details(String v) { this.details = v; return this; }

    public Long getId() { return id; }
    public String getPublicId() { return publicId; }
    public Long getTenantId() { return tenantId; }
    public Long getActorUserId() { return actorUserId; }
    public String getActorUsername() { return actorUsername; }
    public String getAction() { return action; }
    public String getResourceType() { return resourceType; }
    public String getResourceId() { return resourceId; }
    public String getResult() { return result; }
    public String getIpAddress() { return ipAddress; }
    public String getUserAgent() { return userAgent; }
    public String getCorrelationId() { return correlationId; }
    public String getDetails() { return details; }
    public Instant getCreatedAt() { return createdAt; }
}
