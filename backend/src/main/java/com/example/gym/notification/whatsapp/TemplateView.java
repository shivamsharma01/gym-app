package com.example.gym.notification.whatsapp;

import java.util.List;

import com.example.gym.notification.channel.NotificationChannel;
import com.example.gym.notification.template.NotificationTemplate;

public record TemplateView(

		String publicId,

		String templateKey,

		NotificationChannel channel,

		String subject,

		String body,

		boolean active,

		String whatsappTemplateName,

		String whatsappLanguage,

		List<WhatsappVariableView> whatsappVariables

) {

	public static TemplateView from(NotificationTemplate template) {

		return new TemplateView(template.getPublicId(), template.getTemplateKey(), template.getChannel(),
				template.getSubject(), template.getBody(), template.isActive(), template.getWhatsappTemplateName(),
				template.getWhatsappLanguage(),
				template.getWhatsappVariables().stream().map(WhatsappVariableView::from).toList());
	}
}
