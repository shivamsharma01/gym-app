package com.example.gym;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.gym.security.domain.Permission;
import com.example.gym.security.domain.PermissionCatalog;
import com.example.gym.security.domain.Role;
import com.example.gym.support.AbstractIntegrationTest;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Verifies the Flyway-seeded permission catalogue and system-role → permission mappings. */
class RbacSeedIT extends AbstractIntegrationTest {

    @Test
    void permissionCatalogueMatchesEnum() {
        Set<String> dbPermissions = roleRepositoryPermissions();
        Set<String> enumPermissions = java.util.Arrays.stream(PermissionCatalog.values())
                .map(Enum::name)
                .collect(Collectors.toSet());
        assertThat(dbPermissions).isEqualTo(enumPermissions);
    }

    @Test
    void superAdminHasEveryPermission() {
        Role superAdmin = roleRepository.findByNameAndTenantIdIsNull("SUPER_ADMIN").orElseThrow();
        assertThat(superAdmin.getPermissions()).hasSize(PermissionCatalog.values().length);
    }

    @Test
    void gymAdminHasEveryPermissionAndGymOwnerIsGone() {
        Role gymAdmin = roleRepository.findByNameAndTenantIdIsNull("GYM_ADMIN").orElseThrow();
        assertThat(gymAdmin.getPermissions()).hasSize(PermissionCatalog.values().length);
        assertThat(roleRepository.findByNameAndTenantIdIsNull("GYM_OWNER")).isEmpty();
        assertThat(roleRepository.findByNameAndTenantIdIsNull("FRONT_DESK")).isEmpty();
    }

    private Set<String> roleRepositoryPermissions() {
        Role superAdmin = roleRepository.findByNameAndTenantIdIsNull("SUPER_ADMIN").orElseThrow();
        return superAdmin.getPermissions().stream()
                .map(Permission::getName)
                .collect(Collectors.toSet());
    }
}
