package com.example.gym.settings;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GymProfileRepository extends JpaRepository<GymProfile, Long> {

    Optional<GymProfile> findByTenantId(Long tenantId);
}
