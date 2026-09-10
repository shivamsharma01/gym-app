package com.example.gym.security.domain;

import com.example.gym.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * A fine-grained, globally-defined permission (authority). The catalogue is seeded by Flyway and
 * kept in sync with {@link PermissionCatalog}.
 */
@Entity
@Table(name = "permission")
public class Permission extends BaseEntity {

    @Column(name = "name", nullable = false, unique = true, length = 64)
    private String name;

    @Column(name = "description", length = 200)
    private String description;

    protected Permission() {
    }

    public Permission(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }
}
