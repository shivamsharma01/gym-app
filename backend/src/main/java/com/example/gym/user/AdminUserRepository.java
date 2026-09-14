package com.example.gym.user;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AdminUserRepository extends JpaRepository<AdminUser, Long> {

    Optional<AdminUser> findByUsername(String username);

    Optional<AdminUser> findByUsernameOrEmail(String username, String email);

    Optional<AdminUser> findByPublicId(String publicId);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    Page<AdminUser> findByTenantId(Long tenantId, Pageable pageable);

    @Query("select (count(u) > 0) from AdminUser u join u.roles r where r.name = :roleName")
    boolean existsWithRoleName(String roleName);
}
