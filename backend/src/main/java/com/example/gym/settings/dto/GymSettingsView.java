package com.example.gym.settings.dto;

public record GymSettingsView(
        String tenantId,
        String name,
        String slug,
        String tagline,
        String about,
        String phone,
        String email,
        String address,
        String hours) {
}
