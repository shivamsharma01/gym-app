package com.example.gym.notification.dto;

import java.time.Instant;

import com.example.gym.notification.outbound.OutboundNotification;

public record OutboundView(String id, String channel, String templateKey, String recipient, String status,
		int attemptCount, String lastError, Long membershipId, Long announcementId, String whatsappTemplateName,
		String whatsappLanguage, Instant sentAt, Instant deliveredAt, Instant readAt, Instant createdAt) {

	public static OutboundView from(OutboundNotification n) {
		return new OutboundView(n.getPublicId(), n.getChannel().name(), n.getTemplateKey(), n.getRecipient(),
				n.getStatus().name(), n.getAttemptCount(), n.getLastError(), n.getMembershipId(), n.getAnnouncementId(),
				n.getWhatsappTemplateName(), n.getWhatsappLanguage(), n.getSentAt(), n.getDeliveredAt(), n.getReadAt(),
				n.getCreatedAt());
	}
}
