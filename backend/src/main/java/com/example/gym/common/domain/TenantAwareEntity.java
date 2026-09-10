package com.example.gym.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;

/**
 * Base for tenant-owned entities. Every tenant-scoped table carries a non-updatable
 * {@code tenant_id}. Tenant ownership must be verified on every read/write — never load a
 * tenant-owned entity by id alone (see {@code TenantGuard}).
 */
@MappedSuperclass
public abstract class TenantAwareEntity extends BaseEntity {

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }
}
