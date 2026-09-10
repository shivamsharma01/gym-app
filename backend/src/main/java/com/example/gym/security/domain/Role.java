package com.example.gym.security.domain;

import com.example.gym.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import java.util.HashSet;
import java.util.Set;

/**
 * A role bundles permissions. System roles ({@code isSystem=true}, {@code tenantId=null}) are the
 * built-in templates seeded by Flyway; tenants may define custom roles in later phases.
 */
@Entity
@Table(name = "role")
public class Role extends BaseEntity {

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Column(name = "description", length = 200)
    private String description;

    /** {@code null} for system/global roles; set for tenant-defined roles. */
    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "is_system", nullable = false)
    private boolean system;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "role_permission",
            joinColumns = @JoinColumn(name = "role_id"),
            inverseJoinColumns = @JoinColumn(name = "permission_id"))
    private Set<Permission> permissions = new HashSet<>();

    protected Role() {
    }

    public Role(String name, String description, boolean system, Long tenantId) {
        this.name = name;
        this.description = description;
        this.system = system;
        this.tenantId = tenantId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public boolean isSystem() {
        return system;
    }

    public Set<Permission> getPermissions() {
        return permissions;
    }
}
