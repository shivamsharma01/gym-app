package com.example.gym.notification.whatsapp;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

@ConfigurationProperties(prefix = "whatsapp")
@Getter
@Setter
public class WhatsAppProperties {

	private String baseUrl = "https://graph.facebook.com";

	private String apiVersion;

	private String phoneNumberId;

	private String accessToken;
}