package com.example.gym.settings.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateGymSettings(
        @NotBlank @Size(max = 150) String name,
        @Size(max = 200) String tagline,
        @Size(max = 4000) String about,
        @Size(max = 32) String phone,
        @Email @Size(max = 200) String email,
        @Size(max = 300) String address,
        @Size(max = 300) String hours) {
}
