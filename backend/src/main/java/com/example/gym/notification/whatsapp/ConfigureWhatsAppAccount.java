package com.example.gym.notification.whatsapp;

import jakarta.validation.constraints.NotBlank;

public record ConfigureWhatsAppAccount(

		@NotBlank String businessAccountId,

		@NotBlank String phoneNumberId,

		@NotBlank String accessToken,

		String apiVersion

) {
}
