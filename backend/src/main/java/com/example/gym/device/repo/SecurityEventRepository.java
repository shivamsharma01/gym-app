package com.example.gym.device.repo;

import com.example.gym.device.domain.SecurityEvent;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SecurityEventRepository extends JpaRepository<SecurityEvent, Long> {

    Optional<SecurityEvent> findByPublicId(String publicId);

    Page<SecurityEvent> findByTenantIdOrderByOccurredAtDesc(Long tenantId, Pageable pageable);
}
