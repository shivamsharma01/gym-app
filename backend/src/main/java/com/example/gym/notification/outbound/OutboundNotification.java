package com.example.gym.notification.outbound;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.example.gym.common.domain.TenantAwareEntity;
import com.example.gym.notification.channel.NotificationChannel;
import com.example.gym.notification.utils.NotificationStatus;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "outbound_notification")
@Getter
@Setter
@NoArgsConstructor
public class OutboundNotification extends TenantAwareEntity {

	@Enumerated(EnumType.STRING)
	@Column(name = "channel", nullable = false, length = 16)
	private NotificationChannel channel;

	@Column(name = "template_key", nullable = false, length = 100)
	private String templateKey;

	@Column(name = "recipient", nullable = false, length = 200)
	private String recipient;

	@Column(name = "subject", length = 300)
	private String subject;

	@Column(name = "body", columnDefinition = "TEXT")
	private String body;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 16)
	private NotificationStatus status = NotificationStatus.QUEUED;

	@Column(name = "attempt_count", nullable = false)
	private int attemptCount = 0;

	@Column(name = "last_error", columnDefinition = "TEXT")
	private String lastError;

	@Column(name = "provider_message_id", length = 200)
	private String providerMessageId;

	@Column(name = "member_id")
	private Long memberId;

	@Column(name = "membership_id")
	private Long membershipId;

	@Column(name = "announcement_id")
	private Long announcementId;

	@Column(name = "sent_at")
	private Instant sentAt;

	@Column(name = "delivered_at")
	private Instant deliveredAt;

	@Column(name = "read_at")
	private Instant readAt;
	
	@Column(name = "scheduled_at")
	private LocalDateTime scheduledAt;

	@Column(name = "whatsapp_template_name", length = 100)
	private String whatsappTemplateName;

	@Column(name = "whatsapp_language", length = 20)
	private String whatsappLanguage;

	@OneToMany(mappedBy = "notification", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("parameterOrder ASC")
	private List<OutboundNotificationParameter> parameters = new ArrayList<>();

	public OutboundNotification(Long tenantId, Long memberId, NotificationChannel channel, String templateKey,
			String recipient, String subject, String body, String whatsappTemplateName, String whatsappLanguage,
			Long membershipId, Long announcementId) {

		setTenantId(tenantId);

		this.memberId = memberId;
		this.channel = channel;
		this.templateKey = templateKey;
		this.recipient = recipient;
		this.subject = subject;
		this.body = body;
		this.whatsappTemplateName = whatsappTemplateName;
		this.whatsappLanguage = whatsappLanguage;
		this.membershipId = membershipId;
		this.announcementId = announcementId;

		this.status = NotificationStatus.QUEUED;
		this.attemptCount = 0;
		this.scheduledAt = LocalDateTime.now();
	}

	public void addParameter(OutboundNotificationParameter parameter) {

		if (parameter == null) {
			return;
		}

		parameter.setNotification(this);
		this.parameters.add(parameter);
	}

	public void clearParameters() {
		parameters.clear();
	}

}
