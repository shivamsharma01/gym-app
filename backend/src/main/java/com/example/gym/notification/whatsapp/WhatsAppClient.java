package com.example.gym.notification.whatsapp;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.example.gym.notification.outbound.OutboundNotification;
import com.example.gym.notification.whatsapp.WhatsAppTemplateRequest.Parameter;

@Component
public class WhatsAppClient {

	private final RestClient restClient;
	private final WhatsAppProperties properties;

	public WhatsAppClient(RestClient.Builder restClientBuilder, WhatsAppProperties properties) {

		this.restClient = restClientBuilder.baseUrl(properties.getBaseUrl()).build();

		this.properties = properties;
	}

	public String sendTemplate(OutboundNotification notification) {

		validateConfiguration();

		String url = String.format("/%s/%s/messages", properties.getApiVersion(), properties.getPhoneNumberId());

		// for time being it is hard coded because this is test account.
		// 1. Generate the request object using the updated factory method
		WhatsAppTemplateRequest request = WhatsAppTemplateRequest.from(notification);

		// 2. This will now successfully clear and inject the test data!
		request.template().setComponents(List.of(
		        new com.example.gym.notification.whatsapp.WhatsAppTemplateRequest.Component(
		                "body",
		                List.of(
		                        new Parameter("text", "John Doe"),
		                        new Parameter("text", "123456"),
		                        new Parameter("text", "Sep 16, 2026")
		                )
		        )
		));
		WhatsAppResponse response = restClient.post().uri(url)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getAccessToken())
				.contentType(MediaType.APPLICATION_JSON).body(request).retrieve().body(WhatsAppResponse.class);

		if (response == null || response.messages() == null || response.messages().isEmpty()
				|| response.messages().get(0) == null || response.messages().get(0).id() == null
				|| response.messages().get(0).id().isBlank()) {

			throw new IllegalStateException("WhatsApp API returned no message ID");
		}

		return response.messages().get(0).id();
	}

	private void validateConfiguration() {

		if (properties.getApiVersion() == null || properties.getApiVersion().isBlank()) {

			throw new IllegalStateException("whatsapp.api-version is not configured");
		}

		if (properties.getPhoneNumberId() == null || properties.getPhoneNumberId().isBlank()) {

			throw new IllegalStateException("whatsapp.phone-number-id is not configured");
		}

		if (properties.getAccessToken() == null || properties.getAccessToken().isBlank()) {

			throw new IllegalStateException("whatsapp.access-token is not configured");
		}
	}
}