package com.example.gym.notification.dto;

import com.example.gym.notification.NotificationChannel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SendNotification(
        @NotBlank String memberId,
        @NotBlank String templateKey,
        @NotNull NotificationChannel channel) {
}
