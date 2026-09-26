package com.example.gym.notification.whatsapp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.example.gym.notification.outbound.OutboundNotification;
import com.example.gym.notification.outbound.OutboundNotificationParameter;

public record WhatsAppTemplateRequest(String messaging_product, String to, String type, Template template) {

	public record Template(String name, Language language, List<Component> components) {
	}

	public record Language(String code) {
	}

	public record Component(String type, List<Parameter> parameters) {
	}

	public record Parameter(String type, String text) {

		public static Parameter text(String value) {
			return new Parameter("text", value == null ? "" : value);
		}
	}

	public static WhatsAppTemplateRequest from(OutboundNotification notification) {

		if (notification.getWhatsappTemplateName() == null || notification.getWhatsappTemplateName().isBlank()) {

			throw new IllegalStateException(
					"WhatsApp template name is not configured for template " + notification.getTemplateKey());
		}

		String language = notification.getWhatsappLanguage();

		if (language == null || language.isBlank()) {
			language = "en_US";
		}

		List<OutboundNotificationParameter> storedParameters = notification.getParameters();

		List<Parameter> bodyParameters = storedParameters == null ? List.of()
				: storedParameters.stream()
						.sorted(Comparator.comparing(OutboundNotificationParameter::getParameterOrder))
						.map(parameter -> Parameter.text(parameter.getParameterValue())).toList();

		List<Component> components = new ArrayList<>();

		if (!bodyParameters.isEmpty()) {

			components.add(new Component("body", bodyParameters));
		}

		return new WhatsAppTemplateRequest("whatsapp", normalizePhoneNumber(notification.getRecipient()), "template",
				new Template(notification.getWhatsappTemplateName(), new Language(language), components));
	}

	private static String normalizePhoneNumber(String phone) {

		if (phone == null || phone.isBlank()) {
			throw new IllegalArgumentException("WhatsApp recipient phone number is required");
		}

		String normalized = phone.trim().replaceAll("[^0-9]", "");

		if (normalized.isBlank()) {
			throw new IllegalArgumentException("WhatsApp recipient phone number is invalid");
		}

		return normalized;
	}
}
