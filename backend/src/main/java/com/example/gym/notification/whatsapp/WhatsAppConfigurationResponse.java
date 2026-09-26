package com.example.gym.notification.whatsapp;

public record WhatsAppConfigurationResponse(String id, String publicId, String businessAccountId, String phoneNumberId,
		String apiVersion, boolean active) {
}
