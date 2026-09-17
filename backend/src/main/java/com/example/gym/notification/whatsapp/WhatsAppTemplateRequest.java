package com.example.gym.notification.whatsapp;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.example.gym.notification.outbound.OutboundNotification;

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

		if (notification.getWhatsappTemplateName() == null || notification.getWhatsappTemplateName().isBlank()) {

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
		return List.of();
	}

	private static String normalizePhoneNumber(String phone) {

		if (phone == null || phone.isBlank()) {
			throw new IllegalArgumentException("WhatsApp recipient phone number is required");
		}

		/*
		 * Meta expects the phone number in international format, generally without '+'.
		 *
		 * Example: +919876543210 -> 919876543210
		 */
		return phone.trim().replaceAll("[^0-9]", "");
	}
}