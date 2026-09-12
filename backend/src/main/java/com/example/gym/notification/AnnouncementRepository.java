package com.example.gym.notification;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnnouncementRepository extends JpaRepository<Announcement, Long> {

    Optional<Announcement> findByPublicId(String publicId);

    List<Announcement> findByTenantIdOrderByCreatedAtDesc(Long tenantId);

    List<Announcement> findByTenantIdAndPublishedTrueOrderByCreatedAtDesc(Long tenantId);
}
