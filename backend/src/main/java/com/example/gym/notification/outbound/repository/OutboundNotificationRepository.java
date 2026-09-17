package com.example.gym.notification.outbound.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.example.gym.notification.channel.NotificationChannel;
import com.example.gym.notification.outbound.OutboundNotification;
import com.example.gym.notification.utils.NotificationStatus;

public interface OutboundNotificationRepository extends JpaRepository<OutboundNotification, Long> {

	Optional<OutboundNotification> findByPublicId(String publicId);

	Optional<OutboundNotification> findByProviderMessageId(String providerMessageId);

	boolean existsByTenantIdAndMemberIdAndTemplateKeyAndChannelAndCreatedAtAfter(Long tenantId, Long memberId,
			String templateKey, NotificationChannel channel, Instant createdAfter);

	Page<OutboundNotification> findByTenantIdOrderByCreatedAtDesc(Long tenantId, Pageable pageable);

	boolean existsByMembershipIdAndTemplateKeyAndChannel(Long membershipId, String templateKey,
			NotificationChannel channel);

	List<OutboundNotification> findByTenantIdAndStatusAndAttemptCountLessThan(Long tenantId, NotificationStatus status,
			int maxAttempts);

	boolean existsByTenantIdAndMemberIdAndTemplateKeyAndChannelAndBody(Long tenantId, Long id, String announcement,
			NotificationChannel whatsapp, String body);

	boolean existsByTenantIdAndMemberIdAndAnnouncementIdAndChannel(Long tenantId, Long memberId, Long announcementId,
			NotificationChannel channel);

}
