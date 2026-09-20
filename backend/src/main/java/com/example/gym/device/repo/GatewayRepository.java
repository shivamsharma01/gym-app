package com.example.gym.device.repo;

import com.example.gym.device.domain.Gateway;
import com.example.gym.device.domain.GatewayStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GatewayRepository extends JpaRepository<Gateway, Long> {

    Optional<Gateway> findByPublicId(String publicId);

    Optional<Gateway> findByTokenHash(String tokenHash);

    Optional<Gateway> findByNextTokenHash(String nextTokenHash);

    Optional<Gateway> findByEnrollmentTokenHash(String enrollmentTokenHash);

    Page<Gateway> findByTenantId(Long tenantId, Pageable pageable);

    List<Gateway> findByStatusAndLastHeartbeatAtBefore(GatewayStatus status, Instant cutoff);
}
