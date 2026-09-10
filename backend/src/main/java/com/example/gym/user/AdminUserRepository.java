package com.example.gym.user;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminUserRepository extends JpaRepository<AdminUser, Long> {

    Optional<AdminUser> findByUsername(String username);

    Optional<AdminUser> findByUsernameOrEmail(String username, String email);

    Optional<AdminUser> findByPublicId(String publicId);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    Page<AdminUser> findByTenantId(Long tenantId, Pageable pageable);
}
