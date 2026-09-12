package com.example.gym.platform.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record EnrollTenantRequest(
        @NotBlank @Size(max = 150) String name,
        @NotBlank
        @Size(min = 2, max = 64)
        @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$", message = "Slug must be lowercase letters, digits, and hyphens")
        String slug,
        @Size(max = 150) String displayName,
        @NotBlank @Size(max = 80) String ownerUsername,
        @NotBlank @Email @Size(max = 200) String ownerEmail,
        @NotBlank @Size(max = 150) String ownerFullName,
        @NotBlank @Size(min = 10, max = 100) String ownerPassword) {
}
