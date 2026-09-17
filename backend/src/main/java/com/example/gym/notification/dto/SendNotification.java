package com.example.gym.notification.dto;

import com.example.gym.notification.channel.NotificationChannel;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SendNotification(
        @NotBlank String memberId,
        String membershipId,
        @NotBlank String templateKey,
        @NotNull NotificationChannel channel) {
}
