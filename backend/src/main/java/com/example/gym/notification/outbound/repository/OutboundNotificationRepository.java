package com.example.gym.notification.outbound.repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.gym.notification.channel.NotificationChannel;
import com.example.gym.notification.outbound.OutboundNotification;
import com.example.gym.notification.utils.NotificationStatus;

public interface OutboundNotificationRepository extends JpaRepository<OutboundNotification, Long> {

	Optional<OutboundNotification> findByPublicId(String publicId);

	Optional<OutboundNotification> findByProviderMessageId(String providerMessageId);

	Page<OutboundNotification> findByTenantIdOrderByCreatedAtDesc(Long tenantId, Pageable pageable);

	boolean existsByMembershipIdAndTemplateKeyAndChannel(Long membershipId, String templateKey,
			NotificationChannel channel);

	List<OutboundNotification> findByTenantIdAndStatusAndAttemptCountLessThan(Long tenantId, NotificationStatus status,
			int maxAttempts, Pageable pageable);

	boolean existsByTenantIdAndMemberIdAndAnnouncementIdAndChannel(Long tenantId, Long memberId, Long announcementId,
			NotificationChannel channel);

	List<OutboundNotification> findTop100ByStatusAndScheduledAtLessThanEqualOrderByScheduledAtAsc(
			NotificationStatus queued, LocalDateTime now);

	@Query("""
			    select o
			    from OutboundNotification o
			    where o.tenantId = :tenantId
			      and o.createdAt >= :from
			      and o.createdAt < :to
			    order by o.createdAt desc
			""")
	List<OutboundNotification> findReportRows(@Param("tenantId") Long tenantId, @Param("from") Instant from,
			@Param("to") Instant to);
}
