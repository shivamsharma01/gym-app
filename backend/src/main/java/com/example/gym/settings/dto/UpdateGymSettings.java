package com.example.gym.settings.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateGymSettings(
        @NotBlank @Size(max = 150) String name,
        @Size(max = 150) String displayName,
        @Size(max = 200) String tagline,
        @Size(max = 4000) String about,
        @Size(max = 32) String phone,
        @Email @Size(max = 200) String email,
        @Size(max = 300) String address,
        @Size(max = 300) String hours,
        @Size(max = 500) String logoUrl,
        @Size(max = 500) String heroImageUrl,
        @Size(max = 500) String trainingImageUrl,
        @Size(max = 500) String facilitiesImageUrl,
        @Size(max = 120) String sectionTrainingTitle,
        @Size(max = 1000) String sectionTrainingBody,
        @Size(max = 120) String sectionFacilitiesTitle,
        @Size(max = 1000) String sectionFacilitiesBody) {
}
