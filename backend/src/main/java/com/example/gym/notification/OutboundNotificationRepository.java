package com.example.gym.notification;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboundNotificationRepository extends JpaRepository<OutboundNotification, Long> {

    Optional<OutboundNotification> findByPublicId(String publicId);

    Page<OutboundNotification> findByTenantIdOrderByCreatedAtDesc(Long tenantId, Pageable pageable);
}
