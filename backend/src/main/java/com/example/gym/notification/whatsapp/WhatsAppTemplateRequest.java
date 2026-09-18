package com.example.gym.notification.whatsapp;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.example.gym.notification.outbound.OutboundNotification;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public record WhatsAppTemplateRequest(String messaging_product, String to, String type, Template template) {

	public record Template(String name, Language language, List<Component> components) {

		public void setComponents(List<Component> of) {
			try {
				// Clear the existing items inside the list reference
				this.components.clear();
				// Add the new testing items into it
				this.components.addAll(of);
			} catch (UnsupportedOperationException e) {
				throw new IllegalStateException(
						"Failed to inject test data! Ensure you initialized 'Template' with a mutable list like 'new ArrayList<>()' instead of 'List.of()'.",
						e);
			}
		}
	}

	public record Language(String code) {
	}

	public record Component(String type, List<Parameter> parameters) {
	}

	public record Parameter(String type, String text) {
	}

	private static final Pattern VARIABLE = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_.]+)\\s*}}");

	public static WhatsAppTemplateRequest from(OutboundNotification notification) {

		log.debug(
				"Building WhatsApp template request: notificationId={}, tenantId={}, memberId={}, membershipId={}, templateKey={}",
				notification.getPublicId(), notification.getTenantId(), notification.getMemberId(),
				notification.getMembershipId(), notification.getTemplateKey());

		if (notification.getWhatsappTemplateName() == null || notification.getWhatsappTemplateName().isBlank()) {

			log.error("WhatsApp template name missing: notificationId={}, templateKey={}", notification.getPublicId(),
					notification.getTemplateKey());
			throw new IllegalStateException(
					"WhatsApp template name is not configured for template " + notification.getTemplateKey());
		}

		String language = notification.getWhatsappLanguage();

		if (language == null || language.isBlank()) {
			language = "en_US";
		}

		/*
		 * For WhatsApp Cloud API, the body variables are positional.
		 *
		 * Example persisted body:
		 *
		 * Hi {{memberName}}, your membership expires on {{expiryDate}}.
		 *
		 * becomes:
		 *
		 * {{1}} = memberName {{2}} = expiryDate
		 *
		 * We therefore extract variables in their template order from the rendered
		 * notification body.
		 *
		 * IMPORTANT: Your WhatsApp template in Meta must have the same positional
		 * variables.
		 */

		List<Parameter> parameters = extractParameters(notification);

		List<Component> components = parameters.isEmpty() ? new ArrayList<>()
				: new ArrayList<>(List.of(new Component("body", parameters)));

		log.info("WhatsApp request prepared: notificationId={}, templateName={}, language={}, parameterCount={}",
				notification.getPublicId(), notification.getWhatsappTemplateName(), language, parameters.size());

		return new WhatsAppTemplateRequest("whatsapp", normalizePhoneNumber(notification.getRecipient()), "template",
				new Template(notification.getWhatsappTemplateName(), new Language(language), components));
	}

	private static List<Parameter> extractParameters(OutboundNotification notification) {

		/*
		 * The rendered body no longer contains {{variable}} placeholders.
		 *
		 * Therefore this implementation expects the original values to be represented
		 * by a separate convention if dynamic WhatsApp parameters are required.
		 *
		 * For the cleanest implementation, store/render WhatsApp parameters separately
		 * rather than deriving them from body.
		 *
		 * This method is intentionally empty until parameter storage is added.
		 */
		log.debug("No WhatsApp template parameters extracted: notificationId={}, templateKey={}",
				notification.getPublicId(), notification.getTemplateKey());

		return List.of();
	}

	private static String normalizePhoneNumber(String phone) {

		if (phone == null || phone.isBlank()) {

			log.error("WhatsApp recipient phone number is missing");

			throw new IllegalArgumentException("WhatsApp recipient phone number is required");
		}

		String normalized = phone.trim().replaceAll("[^0-9]", "");

		if (normalized.isBlank()) {

			log.error("WhatsApp recipient phone number contains no digits");

			throw new IllegalArgumentException("WhatsApp recipient phone number is invalid");
		}

		log.debug("WhatsApp phone number normalized successfully");

		return normalized;
	}
}