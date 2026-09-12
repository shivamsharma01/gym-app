package com.example.gym.notification.dto;

import com.example.gym.notification.NotificationChannel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpsertTemplate(
        @NotBlank @Size(max = 64) String templateKey,
        @NotNull NotificationChannel channel,
        @Size(max = 200) String subject,
        @NotBlank @Size(max = 4000) String body) {
}
