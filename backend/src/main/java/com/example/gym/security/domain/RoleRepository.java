package com.example.gym.security.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, Long> {

    Optional<Role> findByNameAndTenantIdIsNull(String name);

    Optional<Role> findByPublicId(String publicId);

    /** System roles plus roles owned by the given tenant. */
    List<Role> findByTenantIdIsNullOrTenantId(Long tenantId);
}
