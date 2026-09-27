package com.example.gym.notification.outbound;

import java.time.Instant;
import java.util.List;

import com.example.gym.notification.channel.NotificationChannel;
import com.example.gym.notification.utils.NotificationStatus;

public record OutboundView(

		String publicId,

		NotificationChannel channel,

		String templateKey,

		String recipient,

		String subject,

		String body,

		NotificationStatus status,

		int attemptCount,

		String lastError,

		String providerMessageId,

		Instant createdAt,

		Instant sentAt,

		Instant deliveredAt,

		Instant readAt,

		Long membershipId,

		Long announcementId,

		String whatsappTemplateName,

		String whatsappLanguage,

		List<OutboundParameterView> parameters

) {

	public static OutboundView from(OutboundNotification notification) {

		return new OutboundView(notification.getPublicId(), notification.getChannel(), notification.getTemplateKey(),
				notification.getRecipient(), notification.getSubject(), notification.getBody(),
				notification.getStatus(), notification.getAttemptCount(), notification.getLastError(),
				notification.getProviderMessageId(), notification.getCreatedAt(), notification.getSentAt(),
				notification.getDeliveredAt(), notification.getReadAt(), notification.getMembershipId(),
				notification.getAnnouncementId(), notification.getWhatsappTemplateName(),
				notification.getWhatsappLanguage(),
				notification.getParameters().stream().map(OutboundParameterView::from).toList());
	}
}
