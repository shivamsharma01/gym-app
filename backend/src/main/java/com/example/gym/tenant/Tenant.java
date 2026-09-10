package com.example.gym.tenant;

import com.example.gym.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * A gym organisation. Root of tenant isolation: every tenant-owned entity references
 * {@code tenant.id}. Includes minimal profile fields; richer {@code GymProfile} arrives in Phase 2.
 */
@Entity
@Table(name = "tenant")
public class Tenant extends BaseEntity {

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    /** URL-safe unique identifier for the tenant (e.g. "downtown-fitness"). */
    @Column(name = "slug", nullable = false, unique = true, length = 80)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TenantStatus status = TenantStatus.ACTIVE;

    protected Tenant() {
    }

    public Tenant(String name, String slug) {
        this.name = name;
        this.slug = slug;
        this.status = TenantStatus.ACTIVE;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public TenantStatus getStatus() {
        return status;
    }

    public void setStatus(TenantStatus status) {
        this.status = status;
    }
}
