package com.example.gym.settings.dto;

public record GymSettingsView(
        String tenantId,
        String name,
        String slug,
        String displayName,
        String tagline,
        String about,
        String phone,
        String email,
        String address,
        String hours,
        String logoUrl,
        String heroImageUrl,
        String trainingImageUrl,
        String facilitiesImageUrl,
        String sectionTrainingTitle,
        String sectionTrainingBody,
        String sectionFacilitiesTitle,
        String sectionFacilitiesBody) {
}
