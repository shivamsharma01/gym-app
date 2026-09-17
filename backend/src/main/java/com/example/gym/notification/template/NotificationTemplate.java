package com.example.gym.notification.template;

import com.example.gym.common.domain.TenantAwareEntity;
import com.example.gym.notification.channel.NotificationChannel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "notification_template")
@Getter
@Setter
@NoArgsConstructor
public class NotificationTemplate extends TenantAwareEntity {

	@Column(name = "template_key", nullable = false, length = 100)
	private String templateKey;

	@Enumerated(EnumType.STRING)
	@Column(name = "channel", nullable = false, length = 16)
	private NotificationChannel channel;

	@Column(name = "subject", length = 300)
	private String subject;

	@Column(name = "body", columnDefinition = "TEXT")
	private String body;

	@Column(name = "whatsapp_template_name", length = 100)
	private String whatsappTemplateName;

	@Column(name = "whatsapp_language", length = 20)
	private String whatsappLanguage;

	@Column(name = "active", nullable = false)
	private boolean active = true;
	
	public NotificationTemplate(
            Long tenantId,
            String templateKey,
            NotificationChannel channel,
            String subject,
            String body) {

        setTenantId(tenantId);
        this.templateKey = templateKey;
        this.channel = channel;
        this.subject = subject;
        this.body = body;
        this.active = true;
    }

    public NotificationTemplate(
            Long tenantId,
            String templateKey,
            NotificationChannel channel,
            String subject,
            String body,
            String whatsappTemplateName,
            String whatsappLanguage) {

        setTenantId(tenantId);
        this.templateKey = templateKey;
        this.channel = channel;
        this.subject = subject;
        this.body = body;
        this.whatsappTemplateName = whatsappTemplateName;
        this.whatsappLanguage = whatsappLanguage;
        this.active = true;
    }
}