package com.example.gym.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateUserRequest(
        @NotBlank @Size(min = 3, max = 100) String username,
        @NotBlank @Email @Size(max = 200) String email,
        @NotBlank @Size(max = 150) String fullName,
        @NotBlank @Size(min = 10, max = 100) String password,
        @NotEmpty List<@NotBlank String> roles,
        /** Required only when the caller is a platform SUPER_ADMIN (no tenant of their own). */
        String tenantId) {
}
