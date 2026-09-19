package com.example.gym.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangeOwnPasswordRequest(
        @NotBlank String currentPassword,
        @NotBlank @Size(min = 10, max = 100) String newPassword) {
}
