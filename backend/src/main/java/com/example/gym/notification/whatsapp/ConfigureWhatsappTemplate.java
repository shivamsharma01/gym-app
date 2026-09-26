package com.example.gym.notification.whatsapp;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record ConfigureWhatsappTemplate(

        @NotBlank
        String templateKey,

        @NotBlank
        String whatsappTemplateName,

        @NotBlank
        String whatsappLanguage,

        @NotEmpty
        List<WhatsappVariableRequest> variables

) {
}

