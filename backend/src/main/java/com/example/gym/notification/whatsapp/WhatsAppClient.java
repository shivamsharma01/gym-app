package com.example.gym.notification.whatsapp;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.example.gym.notification.outbound.OutboundNotification;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class WhatsAppClient {

	private final RestClient restClient;
	private final TenantWhatsAppConfigurationRepository configurationRepository;
	private final WhatsAppProperties properties;

	public WhatsAppClient(RestClient.Builder restClientBuilder,
			TenantWhatsAppConfigurationRepository configurationRepository, WhatsAppProperties properties) {

		this.restClient = restClientBuilder.baseUrl(properties.getBaseUrl()).build();

		this.configurationRepository = configurationRepository;
		this.properties = properties;
	}

	public String sendTemplate(OutboundNotification notification) {

		TenantWhatsAppConfiguration configuration = configurationRepository
				.findByTenantIdAndActiveTrue(notification.getTenantId()).orElseThrow(() -> new IllegalStateException(
						"WhatsApp is not configured for tenant " + notification.getTenantId()));

		String apiVersion = configuration.getApiVersion();

		if (apiVersion == null || apiVersion.isBlank()) {
			apiVersion = properties.getDefaultApiVersion();
		}

		if (apiVersion == null || apiVersion.isBlank()) {
			throw new IllegalStateException("WhatsApp API version is not configured");
		}

		if (configuration.getPhoneNumberId() == null || configuration.getPhoneNumberId().isBlank()) {

			throw new IllegalStateException(
					"WhatsApp phone number ID is not configured for tenant " + notification.getTenantId());
		}

		if (configuration.getAccessToken() == null || configuration.getAccessToken().isBlank()) {

			throw new IllegalStateException(
					"WhatsApp access token is not configured for tenant " + notification.getTenantId());
		}

		String url = String.format("/%s/%s/messages", apiVersion, configuration.getPhoneNumberId());

		WhatsAppTemplateRequest request = WhatsAppTemplateRequest.from(notification);

		log.info("Sending WhatsApp template: tenantId={}, notificationId={}, template={}, phoneNumberId={}",
				notification.getTenantId(), notification.getPublicId(), notification.getWhatsappTemplateName(),
				configuration.getPhoneNumberId());

		try {

			WhatsAppResponse response = restClient.post().uri(url)
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + configuration.getAccessToken())
					.contentType(MediaType.APPLICATION_JSON).body(request).retrieve().body(WhatsAppResponse.class);

			if (response == null) {
				throw new IllegalStateException("WhatsApp API returned null response");
			}

			if (response.messages() == null || response.messages().isEmpty()) {

				throw new IllegalStateException("WhatsApp API returned no messages");
			}

			if (response.messages().get(0) == null || response.messages().get(0).id() == null
					|| response.messages().get(0).id().isBlank()) {

				throw new IllegalStateException("WhatsApp API returned no message ID");
			}

			String providerMessageId = response.messages().get(0).id();

			log.info("WhatsApp message sent: tenantId={}, notificationId={}, providerMessageId={}",
					notification.getTenantId(), notification.getPublicId(), providerMessageId);

			return providerMessageId;

		} catch (Exception ex) {

			log.error("WhatsApp delivery failed: tenantId={}, notificationId={}, error={}", notification.getTenantId(),
					notification.getPublicId(), ex.getMessage(), ex);

			throw ex;
		}
	}
}
