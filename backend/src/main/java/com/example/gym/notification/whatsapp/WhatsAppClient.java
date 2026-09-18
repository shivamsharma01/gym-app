package com.example.gym.notification.whatsapp;

import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.example.gym.notification.outbound.OutboundNotification;
import com.example.gym.notification.whatsapp.WhatsAppTemplateRequest.Parameter;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class WhatsAppClient {

	private final RestClient restClient;
	private final WhatsAppProperties properties;

	public WhatsAppClient(RestClient.Builder restClientBuilder, WhatsAppProperties properties) {

		this.restClient = restClientBuilder.baseUrl(properties.getBaseUrl()).build();

		this.properties = properties;

		log.info("WhatsAppClient initialized: baseUrl={}, apiVersion={}, phoneNumberIdConfigured={}",
				properties.getBaseUrl(), properties.getApiVersion(),
				properties.getPhoneNumberId() != null && !properties.getPhoneNumberId().isBlank());
	}

	public String sendTemplate(OutboundNotification notification) {

		log.info(
				"Starting WhatsApp template delivery: notificationId={}, tenantId={}, memberId={}, membershipId={}, templateKey={}",
				notification.getPublicId(), notification.getTenantId(), notification.getMemberId(),
				notification.getMembershipId(), notification.getTemplateKey());

		validateConfiguration();

		String url = String.format("/%s/%s/messages", properties.getApiVersion(), properties.getPhoneNumberId());

		log.debug("WhatsApp API request prepared: notificationId={}, apiVersion={}, phoneNumberId={}",
				notification.getPublicId(), properties.getApiVersion(), properties.getPhoneNumberId());

		// for time being it is hard coded because this is test account.
		// 1. Generate the request object using the updated factory method
		WhatsAppTemplateRequest request = WhatsAppTemplateRequest.from(notification);

		// 2. This will now successfully clear and inject the test data!
		request.template()
				.setComponents(List.of(new com.example.gym.notification.whatsapp.WhatsAppTemplateRequest.Component(
						"body", List.of(new Parameter("text", "John Doe"), new Parameter("text", "123456"),
								new Parameter("text", "Sep 16, 2026")))));
		try {

			WhatsAppResponse response = restClient.post().uri(url)
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getAccessToken())
					.contentType(MediaType.APPLICATION_JSON).body(request).retrieve().body(WhatsAppResponse.class);

			log.debug("WhatsApp API response received: notificationId={}, responsePresent={}",
					notification.getPublicId(), response != null);

			if (response == null) {

				log.error("WhatsApp API returned null response: notificationId={}", notification.getPublicId());

				throw new IllegalStateException("WhatsApp API returned null response");
			}

			if (response.messages() == null || response.messages().isEmpty()) {

				log.error("WhatsApp API returned no messages: notificationId={}", notification.getPublicId());

				throw new IllegalStateException("WhatsApp API returned no messages");
			}

			if (response.messages().get(0) == null || response.messages().get(0).id() == null
					|| response.messages().get(0).id().isBlank()) {

				log.error("WhatsApp API returned message without ID: notificationId={}", notification.getPublicId());

				throw new IllegalStateException("WhatsApp API returned no message ID");
			}

			String providerMessageId = response.messages().get(0).id();

			log.info(
					"WhatsApp message sent successfully: notificationId={}, tenantId={}, memberId={}, providerMessageId={}",
					notification.getPublicId(), notification.getTenantId(), notification.getMemberId(),
					providerMessageId);

			return providerMessageId;

		} catch (Exception ex) {

			log.error(
					"WhatsApp delivery failed: notificationId={}, tenantId={}, memberId={}, membershipId={}, templateKey={}, error={}",
					notification.getPublicId(), notification.getTenantId(), notification.getMemberId(),
					notification.getMembershipId(), notification.getTemplateKey(), ex.getMessage(), ex);

			throw ex;
		}
	}

	private void validateConfiguration() {

		if (properties.getApiVersion() == null || properties.getApiVersion().isBlank()) {

			log.error("WhatsApp configuration error: api-version is missing");

			throw new IllegalStateException("whatsapp.api-version is not configured");
		}

		if (properties.getPhoneNumberId() == null || properties.getPhoneNumberId().isBlank()) {

			log.error("WhatsApp configuration error: phone-number-id is missing");

			throw new IllegalStateException("whatsapp.phone-number-id is not configured");
		}

		if (properties.getAccessToken() == null || properties.getAccessToken().isBlank()) {

			log.error("WhatsApp configuration error: access-token is missing");

			throw new IllegalStateException("whatsapp.access-token is not configured");
		}
		
		log.debug("WhatsApp configuration validated successfully");
	}
}