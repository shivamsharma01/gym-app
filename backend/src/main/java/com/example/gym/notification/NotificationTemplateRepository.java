package com.example.gym.notification;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, Long> {

    Optional<NotificationTemplate> findByPublicId(String publicId);

    List<NotificationTemplate> findByTenantIdOrderByTemplateKeyAsc(Long tenantId);

    Optional<NotificationTemplate> findByTenantIdAndTemplateKeyAndChannel(
            Long tenantId, String templateKey, NotificationChannel channel);

    boolean existsByTenantIdAndTemplateKeyAndChannel(
            Long tenantId, String templateKey, NotificationChannel channel);
}
