package com.example.gym.notification.whatsapp;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record WhatsappVariableRequest(
        @NotNull WhatsappVariable variable,
        @NotNull @Positive Integer order
) {
}
