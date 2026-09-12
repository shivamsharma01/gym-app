package com.example.gym.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateAnnouncement(
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 4000) String body,
        boolean published) {
}
